package com.dsh.android;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the dsh bundle (assets/dsh-bundle.zip) into the app's data dir.
 *
 * WHY A ZIP: the bundle is ~281 MB / ~40k files of node_modules. Shipping it as
 * an uncompressed asset tree would bloat the APK, and hand-writing a tar parser
 * in Java would have to cope with GNU/PAX long-name headers. java.util.zip
 * handles long paths natively, so the build pipeline only has to re-zip the
 * tree once.
 */
public final class ZipExtractor {
    /** Extract {@code assetZip} into {@code destDir}; returns the file count. */
    public static int extract(Context ctx, String assetZip, File destDir) throws IOException {
        int n = 0;
        try (InputStream raw = ctx.getAssets().open(assetZip);
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                // zip-slip guard
                if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("zip entry escapes target: " + e.getName());
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("mkdirs failed: " + parent);
                    }
                    try (BufferedOutputStream bos =
                                 new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                        int r;
                        while ((r = zin.read(buf)) > 0) bos.write(buf, 0, r);
                    }
                    if (e.getName().endsWith(".node")) {
                        //noinspection ResultOfMethodCallIgnored
                        out.setExecutable(true, false);
                    }
                    n++;
                }
                zin.closeEntry();
            }
        }
        return n;
    }

    private ZipExtractor() {}
}
