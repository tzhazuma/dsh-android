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
        pb.environment().put("LD_LIBRARY_PATH", rt.getAbsolutePath());
        pb.environment().put("HOME", files.getAbsolutePath());
        pb.environment().put("TMPDIR", files.getAbsolutePath());
        pb.environment().put("PATH", "/system/bin:/system/xbin");
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
