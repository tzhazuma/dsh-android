package com.dsh.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the bundled dsh host on the bundled Node runtime.
 *
 * ARCHITECTURE (user-specified): dsh itself is a NATIVE APK. Termux is only the
 * bash execution backend, reached through {@link TermuxDispatch}. Nothing here
 * needs Termux to boot dsh.
 */
public final class DshRuntime {
    private static final String TAG = "DshRuntime";
    /** dsh prints `dsh web: http://127.0.0.1:PORT/?token=...` on stdout. */
    private static final Pattern URL = Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+");
    public static final int PORT = 8899;

    public interface Listener {
        void onUrl(String url);
        void onExit(int rc);
    }

    public static File dshDir(Context ctx) {
        return new File(ctx.getFilesDir(), "dsh");
    }

    public static File rtDir(Context ctx) {
        return new File(ctx.getFilesDir(), "rt");
    }

    /**
     * The environment the bundled Node runtime must run under.
     *
     * LD_LIBRARY_PATH / HOME / TMPDIR are the obvious ones. OPENSSL_CONF is not
     * obvious and was found the hard way:
     *
     * The bundled libcrypto.so.3 comes from Termux, whose OpenSSL build baked
     *   OPENSSLDIR = /data/data/com.termux/files/usr/etc/tls
     * into the library. Our app uid can never read that path, and OpenSSL
     * treats an unreadable config file as a FATAL error:
     *   "OpenSSL configuration error: ... BIO_new_file:Permission denied ...
     *    calling fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf, rb)"
     * which aborts Node before it runs a single line of JavaScript.
     * Pointing OPENSSL_CONF at an empty config we own removes the dependency on
     * Termux's private directory entirely. (Node's TLS trust store is its own
     * bundled CA list, so no real OpenSSL configuration is needed.)
     */
    public static void applyEnv(java.util.Map<String, String> env, Context ctx) {
        File files = ctx.getFilesDir();
        env.put("LD_LIBRARY_PATH", rtDir(ctx).getAbsolutePath());
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", files.getAbsolutePath());
        env.put("PATH", "/system/bin:/system/xbin");
        env.put("OPENSSL_CONF", ensureOpensslConf(ctx).getAbsolutePath());
    }

    /** Create (once) an empty-but-valid OpenSSL config inside our own data dir. */
    public static File ensureOpensslConf(Context ctx) {
        File conf = new File(ctx.getFilesDir(), "openssl.cnf");
        if (!conf.isFile()) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(conf), "UTF-8")) {
                w.write("# dsh-android: intentionally empty.\n"
                        + "# Exists only so the Termux-built libcrypto does not try to\n"
                        + "# read its compiled-in OPENSSLDIR inside Termux's private data.\n");
            } catch (Exception e) {
                LogFile.log(TAG, "could not write openssl.cnf: " + e);
            }
        }
        return conf;
    }

    /**
     * Executables shipped inside the APK bundle must be re-pointed at
     * nativeLibraryDir.
     *
     * Android 10+ forbids execve of files under the app's DATA directory (SELinux
     * untrusted_app has no execute on app_data_file). The check is on the resolved
     * inode, so a *symlink* in the data dir pointing at a real file in
     * nativeLibraryDir (labelled apk_data_file, which IS executable) works fine.
     *
     * This bit us twice, and the second time silently:
     *   - ripgrep: spawnSync -> EACCES.
     *   - landlock-run: probe() reported "unusable", which looks exactly like the
     *     kernel not supporting Landlock. It was really exec being denied.
     *
     * So every bundled executable ships twice: as a real file in the zip (so
     * require.resolve and packaging see it) and as lib&lt;name&gt;.so in jniLibs,
     * then symlinked here.
     */
    public static void linkExecutables(Context ctx, File nativeLibraryDir) {
        String[][] pairs = {
                {"node_modules/@vscode/ripgrep-android-arm64/bin/rg", "librg.so"},
                {"node_modules/@deepseek-ai/node-addon-system-android-arm64/bin/landlock-run",
                        "liblandlockrun.so"},
        };
        for (String[] pair : pairs) {
            File link = new File(dshDir(ctx), pair[0]);
            File target = new File(nativeLibraryDir, pair[1]);
            try {
                if (!target.isFile()) {
                    LogFile.log(TAG, "linkExecutables: missing " + target);
                    continue;
                }
                if (link.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    link.delete();
                }
                android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
                LogFile.log(TAG, "linked " + pair[0] + " -> " + target);
            } catch (Throwable t) {
                LogFile.log(TAG, "linkExecutables failed for " + pair[0] + ": " + t);
            }
        }
    }

    /**
     * Start `dsh web`. Returns the Process so the caller can kill it.
     *
     * --expose-internals is required because node-addon-require-builtin has NO
     * Android implementation upstream (see dsh-android/STATUS.md §5.1); the
     * bundled shim substitutes ordinary require(), which only reaches
     * `internal/...` with that flag.
     */
    public static Process startWeb(Context ctx, File nativeLibraryDir, Listener listener) throws Exception {
        File files = ctx.getFilesDir();
        File node = new File(nativeLibraryDir, "libnode.so");
        File rt = rtDir(ctx);
        File dsh = dshDir(ctx);
        File bin = new File(dsh, "node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!bin.isFile()) throw new IllegalStateException("dsh entry missing: " + bin);

        List<String> cmd = Exec.cmd(
                node.getAbsolutePath(),
                "--expose-internals",
                bin.getAbsolutePath(),
                "web", "--no-open", "--port", String.valueOf(PORT));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dsh);
        applyEnv(pb.environment(), ctx);
        LogFile.log(TAG, "exec: " + String.join(" ", cmd));

        final Process p = pb.start();
        final Writer out = new OutputStreamWriter(new FileOutputStream(
                new File(files, "dsh-web.stdout.log")));

        // stdout: scan for the URL; stderr: log verbatim.
        Thread tOut = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    LogFile.log(TAG, "dsh| " + line);
                    out.write(line + "\n");
                    out.flush();
                    Matcher m = URL.matcher(line);
                    if (m.find() && listener != null) listener.onUrl(m.group());
                }
            } catch (Exception ignored) {
            }
        }, "dsh-stdout");
        Thread tErr = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) LogFile.log(TAG, "dsh! " + line);
            } catch (Exception ignored) {
            }
        }, "dsh-stderr");
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        Thread waiter = new Thread(() -> {
            try {
                int rc = p.waitFor();
                LogFile.log(TAG, "dsh web exited rc=" + rc);
                out.close();
                if (listener != null) listener.onExit(rc);
            } catch (Exception ignored) {
            }
        }, "dsh-wait");
        waiter.setDaemon(true);
        waiter.start();
        return p;
    }

    /** Blocking helper used by the self-test path. */
    public static String runSelfTest(Context ctx, File nativeLibraryDir) throws Exception {
        File rt = rtDir(ctx);
        File node = new File(nativeLibraryDir, "libnode.so");
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder sb = new StringBuilder();
        startWeb(ctx, nativeLibraryDir, new Listener() {
            @Override public void onUrl(String url) { sb.append(url); latch.countDown(); }
            @Override public void onExit(int rc) { latch.countDown(); }
        });
        latch.await();
        return sb.toString();
    }

    private DshRuntime() {}
}
