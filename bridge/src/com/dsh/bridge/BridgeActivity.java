package com.dsh.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Minimal verification of the documented Termux dispatch channel:
 * our own APK holds com.termux.permission.RUN_COMMAND and starts
 * com.termux/.app.RunCommandService. No UI injection, no focus race.
 *
 * IMPORTANT (learned on emulator-5554, Android 16):
 * Dispatching straight from onResume() is REJECTED with
 *   BackgroundServiceStartNotAllowedException:
 *   "Not allowed to start service Intent {...} to com.termux/.app.RunCommandService
 *    from ... pkg=com.dsh.bridge startFg?=false ... app is in background uid null"
 * The service start races the app's promotion to TOP procstate. So we wait for the
 * first frame + a short delay before dispatching, and keep the activity alive.
 */
public class BridgeActivity extends Activity {
    private static final String TAG = "DshBridge";
    private static final String HOME = "/data/data/com.termux/files/home";
    private boolean fired = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "onCreate");
    }

    @Override protected void onResume() {
        super.onResume();
        if (fired) return;
        fired = true;
        final String[] argv = resolveArgv();
        // Wait until the process is actually promoted to TOP procstate.
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override public void run() {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { dispatch(argv); }
                }, 1200);
            }
        }, 300);
    }

    private String[] resolveArgv() {
        String[] argv = getIntent().getStringArrayExtra("argv");
        if (argv == null) {
            argv = new String[]{"-c",
                "echo BRIDGE_OK $(date) uid=$(id -u) > " + HOME + "/dshx/bridge_result.txt"};
        }
        return argv;
    }

    private void dispatch(String[] argv) {
        Intent i = new Intent();
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.setAction("com.termux.RUN_COMMAND");
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", argv);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "dsh-bridge");
        // Order matters. Measured behaviour:
        //  - emulator-5554 (AOSP Android 16): startService THROWS
        //    BackgroundServiceStartNotAllowedException; startForegroundService works.
        //  - vivo PA2573 (Android 16): startService returns NORMALLY but is SILENTLY
        //    DROPPED by the ROM -- no exception, no ActivityManager log, and Termux
        //    never receives the intent (zero logcat output on the Termux side).
        // So startForegroundService must be the PRIMARY path; startService is only a
        // fallback for platforms where the foreground route is unavailable.
        // Termux's RunCommandService was verified NOT to ANR under startForegroundService.
        try {
            startForegroundService(i);
            Log.i(TAG, "DISPATCH_OK via startForegroundService argv=" + java.util.Arrays.toString(argv));
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService rejected (" + t.getClass().getSimpleName() + "), trying startService");
            try {
                startService(i);
                Log.i(TAG, "DISPATCH_OK via startService argv=" + java.util.Arrays.toString(argv));
            } catch (Throwable t2) {
                Log.e(TAG, "DISPATCH_FAIL " + t2.getClass().getName() + ": " + t2.getMessage(), t2);
            }
        }
        // Keep the activity around briefly so the app stays foreground while the
        // service start is processed, then leave.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, 2500);
    }
}
