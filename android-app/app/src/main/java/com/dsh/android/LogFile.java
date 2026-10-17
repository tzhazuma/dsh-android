package com.dsh.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * File-backed logger.
 *
 * WHY A FILE AND NOT logcat: this device is a vivo PA2573. Its ROM filters
 * logcat output for third-party apps, so Log.i from our own process is
 * invisible via `adb logcat`. Every diagnostic therefore also goes to a file
 * under the app's files dir, which we can read with
 *   adb shell run-as com.dsh.android cat files/dsh.log
 * This mirrors the lesson already recorded in dsh-android/STATUS.md §4.6.6.
 */
public final class LogFile {
    public static final String NAME = "dsh.log";
    private static File sFile;
    private static final Object LOCK = new Object();

    public static synchronized void init(Context ctx) {
        if (sFile == null) {
            sFile = new File(ctx.getFilesDir(), NAME);
        }
    }

    public static void log(String tag, String msg) {
        Log.i(tag, msg);
        synchronized (LOCK) {
            if (sFile == null) return;
            try (FileOutputStream fos = new FileOutputStream(sFile, true)) {
                String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                fos.write((ts + " [" + tag + "] " + msg + "\n").getBytes("UTF-8"));
            } catch (IOException ignored) {
            }
        }
    }

    public static File file(Context ctx) {
        init(ctx);
        return sFile;
    }

    /** Recursively copy an asset directory into a real directory, mode 0755. */
    public static int extractAssetDir(Context ctx, String assetDir, File dest) throws IOException {
        String[] kids = ctx.getAssets().list(assetDir);
        if (kids == null || kids.length == 0) {
            copyAssetFile(ctx, assetDir, dest);
            return 1;
        }
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("mkdirs failed: " + dest);
        }
        int n = 0;
        for (String kid : kids) {
            String childAsset = assetDir + "/" + kid;
            File childDest = new File(dest, kid);
            n += extractAssetDir(ctx, childAsset, childDest);
        }
        return n;
    }

    private static void copyAssetFile(Context ctx, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        // assets are the ONLY way to ship an executable in an APK; restore the
        // exec bit explicitly (assets lose their mode).
        //noinspection ResultOfMethodCallIgnored
        dest.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        dest.setReadable(true, false);
    }

    private LogFile() {}
}
