package com.dsh.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

/**
 * Foreground service that owns the Node process running `dsh web`.
 *
 * WHY A SERVICE: the host is a long-lived server. While it lived in the
 * activity, switching tasks let the system freeze/reclaim it and the Web UI died
 * with it. A foreground service is the only Android construct that promises the
 * process keeps running while the app is not visible.
 *
 * The service is started with startForegroundService() and calls
 * startForeground() immediately, before any slow work, so the platform does not
 * kill us for exceeding the background-start window during the (30 s+) first-run
 * bundle extraction.
 */
public class DshService extends Service {

    private java.io.File VscodeBootFiles ()
    {
        return new java.io.File (getFilesDir (), "bin");
    }

    public static final String TAG = "DshService";
    private static final String CHANNEL = "dsh-host";
    private static final int NOTIFICATION_ID = 1;

    private static volatile Process process;
    private static volatile boolean started;

    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BootLog.init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("starting dsh host..."));
        if (!started) {
            started = true;
            worker = new Thread(this::boot, "dsh-boot");
            worker.start();
        }
        // START_STICKY: if the ROM reclaims us anyway, bring the host back.
        return START_STICKY;
    }

    private Notification notification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "dsh host", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Keeps the dsh Node host alive");
                nm.createNotificationChannel(ch);
            }
        }
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("dsh")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void note(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
        } catch (Throwable ignored) {
        }
    }

    private void boot() {
        File files = getFilesDir();
        File nld = new File(getApplicationInfo().nativeLibraryDir);
        try {
            BootLog.init(this);
            BootLog.say("=== dsh native APK boot (foreground service) ===");
            BootLog.say("targetSdk=" + getApplicationInfo().targetSdkVersion
                    + " nativeLibraryDir=" + nld);

            // ---- 1. runtime shared libraries from assets -> filesDir/rt ----
            File rt = DshRuntime.rtDir(this);
            if (!rt.isDirectory()) {
                int n = LogFile.extractAssetDir(this, "rt", rt);
                BootLog.say("extracted runtime assets: " + n);
            }
            chmodTree(rt);

            // ---- 2. prove we can exec the bundled Node runtime ----
            File node = new File(nld, "libnode.so");
            BootLog.say("node=" + node + " exists=" + node.exists() + " canExec=" + node.canExecute());
            Exec.Result a = Exec.run(
                    Exec.cmd(node.getAbsolutePath(), "-e",
                            "console.log('NODE_OK ' + process.version + ' ' + process.platform + '/' + process.arch)"),
                    files,
                    env -> DshRuntime.applyEnv(env, this), 30000);
            BootLog.say("node exec: " + a.toString().trim());

            // ---- 3. unpack the dsh bundle ----
            File dsh = DshRuntime.dshDir(this);
            // v2: the bundle now carries the Android hard-link fallback for
            // dsh-session-persistence-jsonl (SELinux denies link(2) in app data).
            File marker = new File(dsh, ".extracted-v2");
            if (!marker.isFile()) {
                BootLog.say("extracting dsh bundle (this takes a moment)...");
                long t0 = System.currentTimeMillis();
                int n = ZipExtractor.extract(this, "dsh-bundle.zip", dsh);
                BootLog.say("extracted " + n + " files in " + (System.currentTimeMillis() - t0) + " ms");
                //noinspection ResultOfMethodCallIgnored
                marker.createNewFile();
            } else {
                BootLog.say("dsh bundle already extracted");
            }

            // The Termux userland needs no external app: the prefix, proot and an Ubuntu rootfs
            // ship in this APK, the same way they do in the VS Code port. Without it every shell
            // step is Android's toybox -- no python, no clang, no apt -- and reaching the real
            // Termux app over the RUN_COMMAND bridge is a fallback, not the foundation.
            // Packages first: tar comes from packages-aarch64.zip and ensure() needs it to unpack
            // the rootfs. Getting this order wrong shows up as "Cannot run program tar:
            // Permission denied", which reads like a permissions problem rather than a missing
            // step.
            TermuxBootstrap.ensurePackages(this);
            TermuxBootstrap.ensure(this);

            // ---- 3b. executables must live in nativeLibraryDir, not here ----
            DshRuntime.linkExecutables(this, nld);

            // Prove the shell shim is executable. A script at files/bin/bash cannot work --
            // Android 10+ refuses execve on app_data_file, so the shim is libbashsh.so in
            // nativeLibraryDir linked into PATH. If this line reports EACCES again, that
            // assumption has broken.
            try {
                String sh = new File(VscodeBootFiles(), "bash").getAbsolutePath();
                Exec.Result r = Exec.run(
                        java.util.Arrays.asList(sh, "-c", "echo BASHSELFTEST_OK; uname -m"),
                        getFilesDir(), env -> DshRuntime.applyEnv(env, this), 15000);
                // Round-trip a command through Termux. RUN_COMMAND alone is one-way, so this
            // exercises the file handshake that actually returns stdout.
            try {
            TermuxTool.ensureReceiver(this);
                TermuxBridge.Result tr = TermuxBridge.run(this,
                        "echo BRIDGE_OK; uname -m; echo $TERMUX_VERSION", 20000);
                BootLog.say("termux bridge ok=" + tr.ok + " rc=" + tr.rc
                        + " out=" + tr.out.trim().replace('\n', '|')
                        + (tr.error != null ? " err=" + tr.error : ""));
            } catch (Throwable t) {
                BootLog.say("termux bridge threw " + t);
            }
            BootLog.say("bash selftest rc=" + r.rc + " out="
                        + r.out.trim().replace('\n', '|') + " err=" + r.err.trim());
            } catch (Throwable t) {
                BootLog.say("bash selftest failed: " + t);
            }

            // ---- 3c. the SAF command-line front door used by dsh ----
            SafBridge.installCli(this);

            // ---- 4. Termux is the bash backend, nothing more ----
            BootLog.say("Termux dispatch: " + TermuxDispatch.dispatchEcho(this).trim());

            // ---- 5. start the dsh host ----
            BootLog.say("starting dsh web (port is picked inside startWeb) ...");
            process = DshRuntime.startWeb(this, nld, new DshRuntime.Listener() {
                @Override public void onUrl(String url) {
                    BootLog.say("DSH_WEB_URL " + url);
                    BootLog.setUrl(url);
                    note("running on " + url);
                }
                @Override public void onExit(int rc) {
                    BootLog.say("dsh web process exited rc=" + rc);
                    BootLog.setDone(true);
                }
            });
            BootLog.say("dsh web launched, pid=" + (process != null ? "ok" : "?"));
            BootLog.say("=== boot done ===");
        } catch (Throwable t) {
            BootLog.say("BOOT_EXCEPTION " + t);
        }
    }

    private void chmodTree(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) chmodTree(f);
            else {
                //noinspection ResultOfMethodCallIgnored
                f.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
            }
        }
    }

    public static Process process() {
        return process;
    }

    @Override
    public void onDestroy() {
        BootLog.say("DshService destroyed");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep running: the whole point of the foreground service.
        BootLog.say("task removed; service stays alive");
        super.onTaskRemoved(rootIntent);
    }
}
