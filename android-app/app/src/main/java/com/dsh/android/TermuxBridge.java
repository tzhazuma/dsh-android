package com.dsh.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command in Termux and gets its output back.
 *
 * <p>RUN_COMMAND is fire-and-forget: the intent starts a command and never returns its stdout.
 * The obvious fix -- rendezvous through a file under /sdcard -- does not work here, because this
 * app targets API 35 and the ROM refuses MANAGE_EXTERNAL_STORAGE through appops, so dsh cannot
 * write there at all:
 *
 * <pre>
 *   shared storage is not writable (mounted); the bridge needs /storage/emulated/0/dsh-bridge
 * </pre>
 *
 * <p>So the reply comes back as a broadcast instead. Termux needs no permission to send one, and
 * dsh needs no permission to receive its own action. The command and the output both travel
 * base64-encoded, which keeps quotes, newlines and binary bytes out of the shell and out of the
 * intent extras.
 *
 * <p>A random token per call is echoed back, so another app broadcasting the same action cannot
 * complete a call it did not answer.
 */
public final class TermuxBridge {

    private static final String ACTION = "com.dsh.android.TERMUX_RESULT";
    private static final String EXTRA_TOKEN = "token";
    private static final String EXTRA_RC = "rc";
    private static final String EXTRA_OUT = "out";
    /** Broadcast extras ride a binder transaction; keep well under its limit. */
    private static final int MAX_OUTPUT = 60 * 1024;

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static boolean registered;

    private TermuxBridge() { }

    public static final class Result {
        public boolean ok;
        public int rc = -1;
        public String out = "";
        public String error = null;
    }

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int rc = -1;
        volatile String out = "";
    }

    /** Register once; safe to call repeatedly. */
    public static synchronized void ensureReceiver(Context ctx) {
        if (registered) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String token = intent.getStringExtra(EXTRA_TOKEN);
                if (token == null) return;
                Pending p = PENDING.remove(token);
                if (p == null) return;
                p.rc = intent.getIntExtra(EXTRA_RC, -1);
                p.out = decode(intent.getStringExtra(EXTRA_OUT));
                p.latch.countDown();
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

    /** Run {@code command} inside Termux, waiting up to {@code timeoutMs} for its output. */
    public static Result run(Context ctx, String command, long timeoutMs) {
        Result r = new Result();
        ensureReceiver(ctx);
        String token = Long.toHexString(System.nanoTime()) + Integer.toHexString(
                (int) (Math.random() * 0x7fffffff));
        String b64 = Base64.encodeToString(command.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        // Termux's bash runs this: decode the command, run it, decode nothing on the way back
        // except base64-encoding the output so quotes and newlines survive the broadcast.
        String script = ""
                + "cmd=$(echo " + b64 + " | base64 -d)\n"
                + "out=$(bash -c \"$cmd\" 2>&1); rc=$?\n"
                + "enc=$(printf %s \"$out\" | base64 -w0 2>/dev/null || printf %s \"$out\" | base64 | tr -d '\\n')\n"
                + "am broadcast -a " + ACTION
                + " --es " + EXTRA_TOKEN + " " + token
                + " --ei " + EXTRA_RC + " $rc"
                + " --es " + EXTRA_OUT + " \"$enc\" >/dev/null 2>&1\n";
        Pending p = new Pending();
        PENDING.put(token, p);
        String err = TermuxDispatch.dispatch(ctx, script, "dsh-bridge");
        if (err != null && err.contains("DISPATCH_FAILED")) {
            PENDING.remove(token);
            r.error = err.trim();
            return r;
        }
        try {
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                PENDING.remove(token);
                r.error = "timed out after " + timeoutMs + " ms; is Termux installed and enabled?";
                return r;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.error = "interrupted";
            return r;
        }
        r.ok = true;
        r.rc = p.rc;
        r.out = p.out.length() > MAX_OUTPUT ? p.out.substring(0, MAX_OUTPUT) : p.out;
        return r;
    }

    private static String decode(String b64) {
        if (b64 == null) return "";
        try {
            return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
