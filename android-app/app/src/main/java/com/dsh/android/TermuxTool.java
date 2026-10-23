package com.dsh.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Let the model reach Termux.
 *
 * <p>dsh's own shell is Android's toybox: it has no python, no clang, no apt. Termux has all of
 * them, but it lives under another uid, so the only way across is the RUN_COMMAND bridge that
 * {@link TermuxBridge} already implements.
 *
 * <p>The bridge is Java-side, and a dsh Tool runs in the Node half of this app, so the two need a
 * channel that does not depend on either runtime. It is a broadcast in and a file out: anything
 * that can run {@code am broadcast} can ask for a command, and the reply lands in
 * {@code files/termux-result} where the Node process can read it -- both halves share this app's
 * uid, so no permission is involved.
 */
public final class TermuxTool {

    public static final String ACTION = "com.dsh.android.TERMUX_RUN";
    private static final String EXTRA_CMD = "cmd";
    private static boolean registered;

    private TermuxTool() { }

    public static synchronized void ensureReceiver(Context ctx) {
        if (registered) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String cmd = intent.getStringExtra(EXTRA_CMD);
                if (cmd == null || cmd.trim().isEmpty()) return;
                // Off the main thread: the bridge waits up to its timeout for Termux to answer.
                new Thread(() -> run(context, cmd), "termux-tool").start();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(r, filter);
        }
        registered = true;
    }

    private static void run(Context ctx, String cmd) {
        TermuxBridge.Result res = TermuxBridge.run(ctx, cmd, 120_000);
        // A tiny hand-rolled JSON object: the payload is one command's output, and quoting it
        // through node's JSON.parse on the other side is all the structure this needs.
        String body = "{\"ok\":" + res.ok
                + ",\"rc\":" + res.rc
                + ",\"out\":\"" + escape(res.out) + "\""
                + (res.error == null ? "" : ",\"error\":\"" + escape(res.error) + "\"")
                + "}";
        File out = new File(ctx.getFilesDir(), "termux-result");
        File tmp = new File(ctx.getFilesDir(), "termux-result.tmp");
        try (FileOutputStream w = new FileOutputStream(tmp)) {
            w.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            BootLog.say("termux tool: could not write result: " + e);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(out);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}
