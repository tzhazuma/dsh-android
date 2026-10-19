package com.dsh.android;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage Access Framework contract shared by all our Android ports.
 *
 * The SIX methods below are the contract, verbatim:
 *   safPickOpen()V  safIsDone()Z  safPickSave(String)V
 *   safSaveDone()Z  safHasSaveUri()Z  safPublish(String)Z
 *
 * RESULTS TRAVEL THROUGH THE FILESYSTEM (no binder, no JNI callbacks):
 *   filesDir/saf-manifest.txt   one local path per line (the imported copies)
 *   filesDir/saf/req            request: seq / op / arg, newline separated
 *   filesDir/saf/res            result:  seq / status / message
 *   filesDir/saf-target-uri.txt the URI chosen by ACTION_CREATE_DOCUMENT
 *
 * dsh is Node, not a JVM host: a Node process cannot call JNI methods on our
 * Activity, so the same six operations are exposed to it as the `saf` command
 * (see installCli) which writes `req` and polls `res`. The Java side stays the
 * contract, unchanged.
 *
 * The vivo ROM fact that matters here: com.android.documentsui is a shim and
 * ACTION_OPEN_DOCUMENT is really served by
 * com.android.filemanager/.selector.view.SelectorHomeActivity. Never test the
 * picker by looking for documentsui.
 */
public final class SafBridge {
    public static final String TAG = "DshSaf";

    public static final int REQ_OPEN = 4701;
    public static final int REQ_SAVE = 4702;
    /** Used by MainActivity's WebView file chooser, not part of the contract. */
    public static final int REQ_WEB = 4703;

    private static final Object LOCK = new Object();
    private static WeakReference<Activity> activity = new WeakReference<>(null);

    private static volatile boolean pickDone;
    private static volatile boolean saveDone;
    private static volatile boolean hasSaveUri;
    private static volatile String lastError = "";
    private static volatile long handledSeq = -1;
    private static volatile boolean resumed;

    private static Thread watcher;

    private SafBridge() {}

    // ------------------------------------------------------------------
    // the six contract methods
    // ------------------------------------------------------------------

    /** Ask the platform picker for one or more readable documents. */
    public static void safPickOpen() {
        enqueue("open", "");
    }

    /** True once the open picker has returned and the manifest was written. */
    public static boolean safIsDone() {
        return pickDone;
    }

    /** Ask the platform picker for a writable destination; name is the title. */
    public static void safPickSave(String suggestedName) {
        enqueue("save", suggestedName == null ? "" : suggestedName);
    }

    /** True once the chosen URI received the bytes from safPublish(). */
    public static boolean safSaveDone() {
        return saveDone;
    }

    /** True when a destination URI has been chosen and is still valid. */
    public static boolean safHasSaveUri() {
        return hasSaveUri;
    }

    /**
     * Stream srcPath to the URI picked by safPickSave().
     *
     * TWO-STAGE SAVE: the caller first writes a scratch file under
     * filesDir/export/, then this waits until the scratch file has stopped
     * growing for 1.5 s before copying it, so a half-written export is never
     * published.
     */
    public static boolean safPublish(String srcPath) {
        File src = new File(srcPath);
        Uri uri = targetUri();
        if (uri == null) {
            lastError = "no destination URI";
            writeResult(currentSeq, "error", lastError);
            return false;
        }
        try {
            awaitStable(src, 30000);
            ContentResolver cr = ctx().getContentResolver();
            long n;
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = cr.openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("openOutputStream returned null");
                n = copy(in, out);
            }
            hasSaveUri = true;
            saveDone = true;
            writeResult(currentSeq, "done", "published " + n + " bytes to " + uri);
            LogFile.log(TAG, "safPublish " + src + " -> " + uri + " (" + n + " bytes)");
            return true;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            saveDone = true;
            writeResult(currentSeq, "error", lastError);
            LogFile.log(TAG, "safPublish failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // wiring
    // ------------------------------------------------------------------

    public static void attach(Activity a) {
        activity = new WeakReference<>(a);
        startWatcher(a.getApplicationContext());
    }

    /** Called from MainActivity.onResume/onPause. */
    public static void setResumed(boolean r) {
        resumed = r;
    }

    public static void detach() {
        activity = new WeakReference<>(null);
    }

    private static Context ctx() {
        Activity a = activity.get();
        if (a != null) return a;
        throw new IllegalStateException("SafBridge has no Activity attached");
    }

    private static File safDir(Context c) {
        File d = new File(c.getFilesDir(), "saf");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File reqFile(Context c) {
        return new File(safDir(c), "req");
    }

    private static File resFile(Context c) {
        return new File(safDir(c), "res");
    }

    private static File manifestFile(Context c) {
        return new File(c.getFilesDir(), "saf-manifest.txt");
    }

    private static File targetFile(Context c) {
        return new File(c.getFilesDir(), "saf-target-uri.txt");
    }

    private static volatile long currentSeq = -1;
    private static volatile String current_op = "open";
    private static volatile String current_arg = "";

    /** arg for a save request is "title\tsourcePath". */
    private static String saveTitle(String arg) {
        int t = arg == null ? -1 : arg.indexOf('\t');
        return t >= 0 ? arg.substring(0, t) : arg;
    }

    /** source path half of a save request arg. */
    private static String saveSource(String arg) {
        int t = arg == null ? -1 : arg.indexOf('\t');
        return t >= 0 ? arg.substring(t + 1) : "";
    }

    private static void enqueue(String op, String arg) {
        try {
            Context c = ctx();
            currentSeq = System.currentTimeMillis();
            current_op = op;
            current_arg = arg;
            if ("open".equals(op)) pickDone = false;
            else saveDone = false;
            File tmp = new File(safDir(c), "req.tmp");
            String body = currentSeq + "\n" + op + "\n" + arg + "\n";
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(body.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(reqFile(c));
            writeResult(currentSeq, "pending", op);
            LogFile.log(TAG, "enqueued " + op + " seq=" + currentSeq + " arg=" + arg);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            LogFile.log(TAG, "enqueue failed: " + t);
        }
    }

    private static void writeResult(long seq, String status, String msg) {
        try {
            Context c = activity.get();
            if (c == null) return;
            File tmp = new File(safDir(c), "res.tmp");
            String body = seq + "\n" + status + "\n" + msg + "\n";
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(body.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(resFile(c));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Watch the request file. This is what makes the Node side work: dsh's
     * `saf` command writes a request, and this thread (whose Activity owns the
     * UI) turns it into startActivityForResult -- via runOnUiThread, or the
     * picker silently never appears.
     */
    private static synchronized void startWatcher(final Context app) {
        if (watcher != null && watcher.isAlive()) return;
        watcher = new Thread(() -> {
            long seen = -1;
            while (true) {
                try {
                    Thread.sleep(300);
                    File rf = reqFile(app);
                    if (!rf.isFile()) continue;
                    String body = readAll(rf);
                    String[] parts = body.split("\n", -1);
                    if (parts.length < 2) continue;
                    long seq = Long.parseLong(parts[0].trim());
                    final String op = parts[1].trim();
                    final String arg = parts.length > 2 ? parts[2] : "";
                    if (seq == seen) continue;
                    seen = seq;
                    currentSeq = seq;
                    current_op = op;
                    current_arg = arg;
                    // Do not launch from the background: Android denies background
                    // activity starts, and the request would sit unanswered while
                    // another app owns the foreground. Wait for our activity.
                    for (int i = 0; i < 600 && !resumed; i++) Thread.sleep(250);
                    final Activity a = activity.get();
                    if (a == null) continue;
                    if (!resumed) {
                        writeResult(seq, "error", "activity never resumed");
                        continue;
                    }
                    a.runOnUiThread(() -> launch(a, op, arg));
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    LogFile.log(TAG, "watcher: " + t);
                }
            }
        }, "saf-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void launch(Activity a, String op, String arg) {
        try {
            if ("save".equals(op)) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String name = saveTitle(arg);
                if (name == null || name.isEmpty()) name = "dsh-export.txt";
                i.setType(mimeFor(name));
                i.putExtra(Intent.EXTRA_TITLE, name);
                a.startActivityForResult(i, REQ_SAVE);
                LogFile.log(TAG, "launched CREATE_DOCUMENT title=" + name);
            } else {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                a.startActivityForResult(i, REQ_OPEN);
                LogFile.log(TAG, "launched OPEN_DOCUMENT");
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            LogFile.log(TAG, "launch failed: " + t);
            writeResult(currentSeq, "error", lastError);
        }
    }

    /** Called by MainActivity.onActivityResult for the contract request codes. */
    public static void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_OPEN) {
            pickDone = true;
            if (resultCode != Activity.RESULT_OK || data == null) {
                writeResult(currentSeq, "cancelled", "open cancelled rc=" + resultCode);
                LogFile.log(TAG, "open cancelled rc=" + resultCode);
                return;
            }
            try {
                List<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                List<String> paths = new ArrayList<>();
                StringBuilder msg = new StringBuilder();
                for (Uri u : uris) {
                    String p = importUri(u);
                    paths.add(p);
                    msg.append(p).append(' ');
                }
                writeManifest(paths);
                writeResult(currentSeq, "done", paths.size() + " file(s) " + msg);
                LogFile.log(TAG, "open done: " + paths);
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                writeResult(currentSeq, "error", lastError);
                LogFile.log(TAG, "open failed: " + t);
            }
        } else if (requestCode == REQ_SAVE) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                saveDone = true;
                writeResult(currentSeq, "cancelled", "save cancelled rc=" + resultCode);
                LogFile.log(TAG, "save cancelled rc=" + resultCode);
                return;
            }
            Uri uri = data.getData();
            hasSaveUri = true;
            try (FileOutputStream fos = new FileOutputStream(targetFile(ctx()))) {
                fos.write(uri.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                LogFile.log(TAG, "could not persist target uri: " + t);
            }
            final String src = saveSource(current_arg);
            LogFile.log(TAG, "save target uri=" + uri + " src=" + src);
            // Publish on a worker thread (stability poll can take seconds).
            new Thread(() -> {
                boolean ok = safPublish(src);
                LogFile.log(TAG, "publish result ok=" + ok + " uri=" + uri);
            }, "saf-publish").start();
        }
    }

    /** Copy a content:// document into filesDir/saf-inbox and return that path. */
    private static String importUri(Uri uri) throws IOException {
        Context c = ctx();
        String name = displayName(uri);
        if (name == null || name.isEmpty()) name = "picked-" + System.currentTimeMillis();
        name = name.replace('/', '_').replace('\\', '_');
        File inbox = new File(c.getFilesDir(), "saf-inbox");
        //noinspection ResultOfMethodCallIgnored
        inbox.mkdirs();
        File out = new File(inbox, name);
        if (out.exists()) {
            out = new File(inbox, System.currentTimeMillis() + "-" + name);
        }
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("openInputStream returned null for " + uri);
            copy(in, os);
        }
        LogFile.log(TAG, "imported " + uri + " -> " + out + " (" + out.length() + " bytes)");
        return out.getAbsolutePath();
    }

    private static void writeManifest(List<String> paths) throws IOException {
        File m = manifestFile(ctx());
        StringBuilder sb = new StringBuilder();
        for (String p : paths) sb.append(p).append('\n');
        try (FileOutputStream fos = new FileOutputStream(m)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String displayName(Uri uri) {
        try (Cursor cur = ctx().getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cur.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
    }

    private static Uri targetUri() {
        try {
            Context c = activity.get();
            if (c == null) return null;
            File f = targetFile(c);
            if (!f.isFile()) return null;
            String s = readAll(f).trim();
            return s.isEmpty() ? null : Uri.parse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void awaitStable(File f, long timeoutMs) throws Exception {
        long t0 = System.currentTimeMillis();
        long last = -1, since = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            long len = f.length();
            if (len != last) {
                last = len;
                since = System.currentTimeMillis();
            } else if (len > 0 && System.currentTimeMillis() - since >= 1500) {
                return;
            }
            Thread.sleep(250);
        }
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
            total += r;
        }
        out.flush();
        return total;
    }

    private static String readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.max(1, f.length())];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------
    // the `saf` command dsh (Node) uses
    // ------------------------------------------------------------------

    private static final String SHELL_CLI =
            "#!/system/bin/sh\n"
            + "# dsh SAF front door: writes filesDir/saf/req, prints the result.\n"
            + "FILES=@FILES@\n"
            + "export HOME=\"$FILES\"\n"
            + "export TMPDIR=\"$FILES\"\n"
            + "export OPENSSL_CONF=\"$FILES/openssl.cnf\"\n"
            + "export DSH_SAF_DIR=\"$FILES\"\n"
            + "export LD_LIBRARY_PATH=\"$FILES/rt\"\n"
            + "exec \"@NODE@\" --expose-internals \"@JS@\" \"$@\"\n";

    private static final String JS_CLI =
            "#!/usr/bin/env node\n"
            + "// saf: the six-method SAF contract, as a command, for dsh (Node).\n"
            + "const fs = require('fs');\n"
            + "const path = require('path');\n"
            + "const FILES = process.env.DSH_SAF_DIR || process.env.HOME;\n"
            + "if (!FILES) { console.error('saf: no DSH_SAF_DIR/HOME'); process.exit(2); }\n"
            + "const DIR = path.join(FILES, 'saf');\n"
            + "const REQ = path.join(DIR, 'req'), RES = path.join(DIR, 'res');\n"
            + "const MANIFEST = path.join(FILES, 'saf-manifest.txt');\n"
            + "const TARGET = path.join(FILES, 'saf-target-uri.txt');\n"
            + "const sleep = ms => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);\n"
            + "function readRes() { try { return fs.readFileSync(RES, 'utf8').split('\\n'); } catch { return null; } }\n"
            + "function enqueue(op, arg) {\n"
            + "  fs.mkdirSync(DIR, { recursive: true });\n"
            + "  const seq = Date.now();\n"
            + "  const tmp = path.join(DIR, 'req.tmp');\n"
            + "  fs.writeFileSync(tmp, `${seq}\\n${op}\\n${arg}\\n`);\n"
            + "  fs.renameSync(tmp, REQ);\n"
            + "  console.log(`saf: ${op} requested (seq=${seq})`);\n"
            + "  return seq;\n"
            + "}\n"
            + "function wait(seq, timeoutMs) {\n"
            + "  const t0 = Date.now();\n"
            + "  while (Date.now() - t0 < timeoutMs) {\n"
            + "    const r = readRes();\n"
            + "    if (r && String(r[0]).trim() === String(seq)) {\n"
            + "      const st = (r[1] || '').trim();\n"
            + "      if (st === 'done' || st === 'error' || st === 'cancelled') return { status: st, msg: (r[2] || '').trim() };\n"
            + "    }\n"
            + "    sleep(300);\n"
            + "  }\n"
            + "  return { status: 'timeout', msg: 'no answer from the app (is the dsh app in the foreground?)' };\n"
            + "}\n"
            + "const args = process.argv.slice(2);\n"
            + "const cmd = args[0] || 'state';\n"
            + "function opt(name, def) { const i = args.indexOf(name); return i >= 0 && args[i + 1] ? args[i + 1] : def; }\n"
            + "if (cmd === 'open') {\n"
            + "  const seq = enqueue('open', '');\n"
            + "  const r = wait(seq, 300000);\n"
            + "  if (r.status !== 'done') { console.error(`saf: open ${r.status}: ${r.msg}`); process.exit(1); }\n"
            + "  let paths = fs.readFileSync(MANIFEST, 'utf8').split('\\n').filter(Boolean);\n"
            + "  const into = opt('--into', '');\n"
            + "  if (into) {\n"
            + "    fs.mkdirSync(into, { recursive: true });\n"
            + "    paths = paths.map(p => { const d = path.join(into, path.basename(p)); fs.copyFileSync(p, d); return d; });\n"
            + "  }\n"
            + "  for (const p of paths) console.log(p);\n"
            + "} else if (cmd === 'save') {\n"
            + "  const src = args[1];\n"
            + "  if (!src) { console.error('usage: saf save <file> [--name NAME]'); process.exit(2); }\n"
            + "  const name = opt('--name', path.basename(src));\n"
            + "  const seq = enqueue('save', `${name}\t${src}`);\n"
            + "  // the Java side shows CREATE_DOCUMENT, then publishes the scratch file\n"
            + "  const r = wait(seq, 300000);\n"
            + "  console.log(`saf: save ${r.status}: ${r.msg}`);\n"
            + "  if (r.status !== 'done') process.exit(1);\n"
            + "  console.log(fs.existsSync(TARGET) ? fs.readFileSync(TARGET, 'utf8').trim() : '');\n"
            + "} else if (cmd === 'state') {\n"
            + "  const r = readRes();\n"
            + "  console.log(JSON.stringify({ dir: DIR, manifest: fs.existsSync(MANIFEST), target: fs.existsSync(TARGET), res: r }));\n"
            + "} else { console.error('usage: saf open [--into DIR] | saf save <file> [--name NAME] | saf state'); process.exit(2); }\n";

    /** Materialise filesDir/bin/saf (+saf.js) with absolute paths baked in. */
    public static void installCli(Context c) {
        try {
            File bin = new File(c.getFilesDir(), "bin");
            //noinspection ResultOfMethodCallIgnored
            bin.mkdirs();
            File js = new File(bin, "saf.js");
            File sh = new File(bin, "saf");
            File node = new File(c.getApplicationInfo().nativeLibraryDir, "libnode.so");
            try (FileOutputStream fos = new FileOutputStream(js)) {
                fos.write(JS_CLI.getBytes(StandardCharsets.UTF_8));
            }
            String shell = SHELL_CLI
                    .replace("@NODE@", node.getAbsolutePath())
                    .replace("@JS@", js.getAbsolutePath())
                    .replace("@FILES@", c.getFilesDir().getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(sh)) {
                fos.write(shell.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            sh.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            js.setReadable(true, false);
            LogFile.log(TAG, "installed SAF CLI " + sh + " and " + js);
        } catch (Throwable t) {
            LogFile.log(TAG, "installCli failed: " + t);
        }
    }
}
