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
    /**
     * dsh prints `dsh web: http://127.0.0.1:PORT/?token=...` on stdout.
     *
     * <p>The port is no longer a constant. It was 8899, and on this device 8899 is already
     * held by Termux's own listener: `dsh web` then died with
     * {@code EADDRINUSE: address already in use 127.0.0.1:8899} two seconds after launch,
     * the webserver plugin never activated, and the WebView stayed empty with nothing but
     * "rc=1" in the log. A port that another application happens to own is not something
     * this app can promise, so it searches for a free one instead.
     */
    private static final Pattern URL = Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+");
    public static final int FIRST_PORT = 8900;
    public static final int LAST_PORT = 8920;

    private static volatile int port = FIRST_PORT;

    /** The port the current (or next) dsh web host binds. */
    public static int port() {
        return port;
    }

    /**
     * Bind-test each port in range and return the first that works.
     *
     * <p>Binding and immediately closing leaves a race -- another process could take the
     * port in between -- but Node re-checks anyway, and a lost race only costs one retry
     * on the next launch. Being wrong about a *fixed* port is worse: it fails every time.
     */
    public static int pickFreePort() {
        for (int p = FIRST_PORT; p <= LAST_PORT; p++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket()) {
                s.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("127.0.0.1"), p));
                port = p;
                return p;
            }
            catch (Throwable ignored) {
                // taken (or reserved); try the next one
            }
        }
        port = FIRST_PORT;
        return port;
    }

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
        // filesDir/bin holds the `saf` command (the SAF contract, usable from
        // dsh's shell) -- putting it on PATH is what makes `saf open` work from
        // any command dsh runs.
        env.put("PATH", "/system/bin:/system/xbin:" + new File(files, "bin").getAbsolutePath());
        // Prefer the prefix this APK ships: real bash, python, clang, apt. The toybox shim below
        // stays as the fallback for the very first moments before the unpack finishes.
        File bundledBash = new File(files, "usr/bin/bash");
        File bundledPrefix = new File(files, "usr");
        if (bundledBash.isFile()) {
            env.put("PREFIX", bundledPrefix.getAbsolutePath());
            env.put("SHELL", bundledBash.getAbsolutePath());
            env.put("TERM", "xterm-256color");

        // curl and everything built on libcurl has a CA path compiled in pointing at the Termux
        // prefix (/data/data/com.termux/files/usr/etc/tls/cert.pem), which this uid cannot read:
        // every TLS request then fails with "error adding trust anchors from file". The variable
        // overrides that path, so setting it here fixes curl for the whole process tree --
        // package managers included, which is why pkg/apt judged every mirror bad.
        java.io.File caFile = new java.io.File(new java.io.File(files, "usr"), "etc/tls/cert.pem");
        if (caFile.isFile()) {
            env.put("CURL_CA_BUNDLE", caFile.getAbsolutePath());
            env.put("SSL_CERT_FILE", caFile.getAbsolutePath());
        }
            env.put("PATH", bundledPrefix.getAbsolutePath() + "/bin:"
                    + bundledPrefix.getAbsolutePath() + "/bin/applets:/system/bin");
            // Both, runtime first: the prefix's own libs are needed by bash/python/clang, and the
            // rt directory holds libnode's dependencies (libcares, libcrypto, libicu...). Setting
            // this to the prefix alone made libnode fail to link -- "library libcares.so not
            // found" -- so the server never started and the UI sat on a blank page.
            env.put("LD_LIBRARY_PATH", rtDir(ctx).getAbsolutePath()
                    + ":" + bundledPrefix.getAbsolutePath() + "/lib");
            env.put("TMPDIR", new File(files, "tmp").getAbsolutePath());
            // apt has /data/data/com.termux/files/usr/etc/apt compiled in and ignores PREFIX, so
            // without APT_CONFIG every apt call answers "Unable to determine a suitable packaging
            // system type" -- which reads like a broken install rather than a missing variable.
            env.put("APT_CONFIG", new File(bundledPrefix, "etc/apt/apt.conf").getAbsolutePath());
            env.put("HOME", files.getAbsolutePath());
        } else {
            env.put("SHELL", ensureShim(ctx, "bash").getAbsolutePath());
            ensureShim(ctx, "sh");
        }
        // The SAF CLI needs to find filesDir without guessing: HOME is filesDir,
        // but make it explicit.
        // Termux is installed on many of these devices and is the only place a real bash lives.
        // Its own uid is the only one allowed to execute files there, so these are *pointers* for
        // tooling and for the RUN_COMMAND bridge -- not something this process can exec directly.
        env.put("TERMUX_PREFIX", "/data/data/com.termux/files/usr");
        env.put("TERMUX_BASH", "/data/data/com.termux/files/usr/bin/bash");
        // Make the Termux bridge one command instead of a broadcast incantation. An agent inside
        // this app otherwise has no way to know that `am broadcast -a com.dsh.android.TERMUX_RUN`
        // exists, and the reply file is JSON that has to be unwrapped by hand.
        ensureTermuxCommand(ctx);
        ensureTermuxTool(ctx);
        env.put("DSH_SAF_DIR", files.getAbsolutePath());
        env.put("OPENSSL_CONF", ensureOpensslConf(ctx).getAbsolutePath());
    }

    /**
     * Install (once) a tiny shell shim in filesDir/bin under `name`, pointing at `target`.
     *
     * Android has no bash — only toybox at /system/bin/sh — but dsh's tool descriptions and most
     * of the commands an agent writes start with `bash`. A missing bash makes every shell step
     * fail, which the agent reports as "this device has no shell", and then it refuses to edit or
     * run anything. filesDir/bin is already on PATH, so a one-line exec shim is enough: the
     * script is a real file the app owns, and `/system/bin/sh` is always executable.
     */

    /**
     * Install (once) the model-facing termux_run tool and register it in the profile composition.
     *
     * <p>The `termux` shell command is enough for a human, but a model cannot discover it and has no
     * schema to fill in. dsh's tools are Cordis plugin rows: the package has to sit where its own
     * `@deepseek-ai/dsh-tools` import resolves (the bundle's node_modules), and the profile's
     * cordis.patch.yml has to insert a row for it. Both are done here so a fresh install has the
     * tool without anyone editing YAML over adb.
     *
     * <p>Two details cost real debugging time and are encoded below: the row must be an
     * `- insert:` patch (a bare top-level row is treated as an override and skipped with only a
     * warning), and the `name` must be an absolute path to the plugin's entry file, because a
     * package specifier is resolved from the profile directory, where the bundle's node_modules is
     * not on the path.
     */
    private static void ensureTermuxTool (Context ctx)
    {
        File files = ctx.getFilesDir ();
        File dir = new File (files, "dsh/node_modules/@deepseek-ai/dsh-tool-termux");
        File entry = new File (dir, "lib/index.js");
        if (!entry.isFile ())
          {
            if (!dir.isDirectory () && !dir.mkdirs ()) return;
            try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream (
                     ctx.getAssets ().open ("dsh-tool-termux.tar")))
              {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[1 << 15];
                while ((e = in.getNextEntry ()) != null)
                  {
                    String rel = e.getName ().replaceFirst ("^ttermux/", "");
                    if (rel.isEmpty () || e.isDirectory ()) continue;
                    File out = new File (dir, rel);
                    File parent = out.getParentFile ();
                    if (parent != null && !parent.isDirectory () && !parent.mkdirs ()) continue;
                    try (java.io.FileOutputStream w = new java.io.FileOutputStream (out))
                      {
                        int n;
                        while ((n = in.read (buf)) > 0) w.write (buf, 0, n);
                      }
                  }
              }
            catch (java.io.IOException ex)
              {
                LogFile.log (TAG, "could not unpack the termux tool: " + ex);
                return;
              }
          }

        File patch = new File (files, ".dsh/profiles/web/cordis.patch.yml");
        if (!patch.isFile ()) return;
        try
          {
            String text = new String (java.nio.file.Files.readAllBytes (patch.toPath ()),
                                      java.nio.charset.StandardCharsets.UTF_8);
            if (text.contains ("tool-termux")) return;
            String row = ""
                    + "- insert:\n"
                    + "    - id: tool-termux\n"
                    + "      name: \"" + entry.getAbsolutePath () + "\"\n"
                    + "      config:\n"
                    + "        filesDir: " + files.getAbsolutePath () + "\n"
                    + "        timeoutMs: 120000\n";
            try (java.io.FileOutputStream w = new java.io.FileOutputStream (patch, true))
              {
                w.write (row.getBytes (java.nio.charset.StandardCharsets.UTF_8));
              }
            LogFile.log (TAG, "registered the termux_run tool in the web profile");
          }
        catch (java.io.IOException ex)
          {
            LogFile.log (TAG, "could not register the termux tool: " + ex);
          }
    }

    /**
     * Install (once) a `termux` command: run a command in Termux and print its output.
     *
     * <p>dsh's own shell is toybox -- no python, no clang, no apt. Termux has them but runs under
     * another uid, so the only route is the RUN_COMMAND bridge this app already implements, which
     * is a broadcast in and a JSON file out. That is fine for the code and useless for a model that
     * just wants to run a command, so this wraps it:
     *
     * <pre>
     *   termux 'python3 -c "print(6*7)"'   # -> 42
     * </pre>
     *
     * <p>The reply is read back from files/termux-result, which both halves of this app share.
     */
    private static void ensureTermuxCommand (Context ctx)
    {
        File script = new File(new File(ctx.getFilesDir(), "bin"), "termux");
        if (script.isFile ()) return;
        File parent = script.getParentFile ();
        if (parent != null && !parent.isDirectory () && !parent.mkdirs ()) return;
        String body = ""
                + "#!/system/bin/sh\n"
                + "# Run a command inside Termux and print what it produced.\n"
                + "# Usage: termux '<shell command>'\n"
                + "F=" + ctx.getFilesDir ().getAbsolutePath () + "\n"
                + "RES=\"$F/termux-result\"\n"
                + "rm -f \"$RES\"\n"
                + "am broadcast -a com.dsh.android.TERMUX_RUN --es cmd \"$1\" >/dev/null 2>&1\n"
                + "i=0\n"
                + "while [ ! -f \"$RES\" ] && [ $i -lt 60 ]; do sleep 0.2; i=$((i+1)); done\n"
                + "if [ ! -f \"$RES\" ]; then echo \"termux: no reply (is the dsh service running?)\" >&2; exit 1; fi\n"
                + "# {\"ok\":true,\"rc\":0,\"out\":\"...\"} -- print out and exit with rc.\n"
                + "O=$(sed -n 's/.*\"out\":\"\\(.*\\)\",\"rc\".*/\\1/p' \"$RES\")\n"
                + "R=$(sed -n 's/.*\"rc\":\\([0-9-]*\\)\\.*/\\1/p' \"$RES\")\n"
                + "printf '%b\\n' \"$O\"\n"
                + "exit ${R:-0}\n";
        try (java.io.FileOutputStream w = new java.io.FileOutputStream (script)) {
            w.write (body.getBytes (java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            LogFile.log (TAG, "could not write termux command: " + e);
        }
        //noinspection ResultOfMethodCallIgnored
        script.setExecutable (true, false);
    }

    public static File ensureShim (Context ctx, String name)
    {
        File dir = new File(ctx.getFilesDir(), "bin");
        dir.mkdirs();
        File link = new File(dir, name);
        File real = new File(ctx.getApplicationInfo().nativeLibraryDir, "libbashsh.so");
        try {
            java.nio.file.Files.deleteIfExists(link.toPath());
            android.system.Os.symlink(real.getAbsolutePath(), link.getAbsolutePath());
        } catch (Throwable t) {
            LogFile.log(TAG, "could not link " + name + " -> " + real + ": " + t);
        }
        return link;
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
                // unlink unconditionally: exists() follows the link, so a stale
                // link would otherwise fail symlink() with EEXIST and be logged
                // as an error on every boot.
                try {
                    java.nio.file.Files.deleteIfExists(
                            java.nio.file.Paths.get(link.getAbsolutePath()));
                } catch (Throwable ignored) {
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
                "web", "--no-open", "--port", String.valueOf(pickFreePort()));

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
