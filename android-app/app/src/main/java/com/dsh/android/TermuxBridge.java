package com.dsh.android;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runs a command in Termux and gets its output back.
 *
 * <p>Termux's RUN_COMMAND intent is fire-and-forget: it starts a command but never returns its
 * stdout, which is why dsh could hand work to Termux and learn nothing about the result. The two
 * apps share no private storage, so the handshake goes through a directory on shared storage:
 *
 * <pre>
 *   dsh     writes  cmd-&lt;id&gt;.sh
 *   dsh     sends   RUN_COMMAND bash .../runner.sh &lt;id&gt;
 *   Termux  runs    cmd-&lt;id&gt;.sh, writes out-&lt;id&gt;.txt and rc-&lt;id&gt;.txt
 *   dsh     polls for out-&lt;id&gt;.txt
 * </pre>
 *
 * <p>Requires com.termux.permission.RUN_COMMAND (already granted) and, on Termux's side, read
 * access to shared storage -- without it the runner cannot see the request at all.
 */
public final class TermuxBridge {

    private static final String DIR_NAME = "dsh-bridge";

    private TermuxBridge() { }

    public static final class Result {
        public boolean ok;
        public int rc = -1;
        public String out = "";
        public String error = null;
    }

    public static File dir(Context ctx) {
        File base = Environment.getExternalStorageDirectory();
        File d = new File(base, DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Write the runner Termux executes; idempotent, called before every request. */
    public static void installRunner(Context ctx) {
        File runner = new File(dir(ctx), "runner.sh");
        String sh = ""
                + "#!/data/data/com.termux/files/usr/bin/bash\n"
                + "# Reads cmd-<id>.sh, runs it, writes out-<id>.txt and rc-<id>.txt.\n"
                + "id=\"$1\"\n"
                + "d=/sdcard/" + DIR_NAME + "\n"
                + "[ -f \"$d/cmd-$id.sh\" ] || exit 3\n"
                + "bash \"$d/cmd-$id.sh\" > \"$d/out-$id.txt\" 2>&1\n"
                + "echo $? > \"$d/rc-$id.txt\"\n";
        try (FileOutputStream w = new FileOutputStream(runner)) {
            w.write(sh.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            BootLog.say("termux bridge: could not write runner: " + e);
        }
    }

    /** Run {@code command} in Termux, waiting up to {@code timeoutMs} for its output. */
    public static Result run(Context ctx, String command, long timeoutMs) {
        Result r = new Result();
        File d = dir(ctx);
        installRunner(ctx);
        String id = Long.toString(System.currentTimeMillis());
        File cmd = new File(d, "cmd-" + id + ".sh");
        File out = new File(d, "out-" + id + ".txt");
        File rc = new File(d, "rc-" + id + ".txt");
        try (FileOutputStream w = new FileOutputStream(cmd)) {
            w.write(command.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            r.error = "could not write the command: " + e;
            return r;
        }
        String dispatched = TermuxDispatch.dispatch(ctx,
                "bash " + d.getAbsolutePath() + "/runner.sh " + id, "dsh-bridge");
        if (dispatched != null) {
            r.error = dispatched;
            return r;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (rc.isFile()) {
                try {
                    r.rc = Integer.parseInt(read(rc).trim());
                } catch (Exception ignored) {
                    r.rc = -1;
                }
                r.out = read(out);
                r.ok = true;
                // Clean up so the shared directory does not grow without bound.
                //noinspection ResultOfMethodCallIgnored
                cmd.delete();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                //noinspection ResultOfMethodCallIgnored
                rc.delete();
                return r;
            }
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        r.error = "timed out after " + timeoutMs + " ms (is Termux running, with storage access?)";
        return r;
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
