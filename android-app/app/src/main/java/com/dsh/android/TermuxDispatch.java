package com.dsh.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;

/**
 * The Termux half of the architecture: our APK holds
 * com.termux.permission.RUN_COMMAND and pushes bash work to Termux's
 * RunCommandService. Termux is the *execution backend*, not dsh's host.
 *
 * Hard-won facts encoded here (see dsh-android/STATUS.md §4.6.6):
 *   - RunCommandService is a Service, not a Receiver, and the permission is
 *     `dangerous`, so `adb shell` can never drive it. Only our own APK can.
 *   - `startService` is refused on AOSP (BackgroundServiceStartNotAllowed)
 *     and SILENTLY DROPPED by vivo. `startForegroundService` is the only
 *     path that works on both, so it is tried first.
 *   - Termux caches `allow-external-apps` at process start; after changing the
 *     property the user must `am force-stop com.termux`.
 */
public final class TermuxDispatch {
    private static final String TAG = "DshTermux";

    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    public static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    public static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    public static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";

    /** Dispatch `bash -c <script>` to Termux; Termux writes the result itself. */
    public static String dispatch(Context ctx, String script, String label) {
        StringBuilder sb = new StringBuilder();
        boolean granted = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
        sb.append("permission ").append(PERMISSION).append(" granted=").append(granted).append('\n');

        Intent i = new Intent(ACTION_RUN_COMMAND);
        i.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
        i.putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash");
        i.putExtra(EXTRA_ARGUMENTS, new String[]{"-c", script});
        i.putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home");
        i.putExtra(EXTRA_BACKGROUND, true);
        i.putExtra(EXTRA_SESSION_ACTION, "0");
        i.putExtra(EXTRA_COMMAND_LABEL, label);

        boolean fg = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
                fg = true;
            } else {
                ctx.startService(i);
            }
            sb.append("DISPATCH_OK via ").append(fg ? "startForegroundService" : "startService").append('\n');
        } catch (Throwable t) {
            sb.append("fg dispatch failed: ").append(t).append('\n');
            try {
                ctx.startService(i);
                sb.append("DISPATCH_OK via startService (fallback)\n");
            } catch (Throwable t2) {
                sb.append("DISPATCH_FAILED ").append(t2).append('\n');
            }
        }
        return sb.toString();
    }

    /** Proof-of-life: run a command in Termux that leaves a result file behind. */
    public static String dispatchEcho(Context a) {
        File proof = new File(a.getFilesDir(), "termux-result.txt");
        String script = "echo \"BRIDGE_OK $(date) uid=$(id -u) pwd=$(pwd)\" > "
                + "/data/data/com.termux/files/home/dshx/termux_proof.txt; "
                + "cat /data/data/com.termux/files/home/dshx/termux_proof.txt > "
                + "/data/data/com.termux/files/home/dshx/android_side.txt";
        String r = dispatch(a, script, "dsh-android-proof");
        LogFile.log(TAG, r);
        LogFile.log(TAG, "proof file on our side: " + proof);
        return r;
    }

    private TermuxDispatch() {}
}
