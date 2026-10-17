package com.dsh.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * dsh, as a native Android APK.
 *
 * Everything dsh needs to run is inside this APK: the Node runtime (in
 * jniLibs, extracted to nativeLibraryDir so execve is permitted), the shared
 * libraries it links, and the whole dsh dependency tree (assets/dsh-bundle.zip).
 * Termux is contacted ONLY for bash execution, via {@link TermuxDispatch}.
 */
public class MainActivity extends Activity {
    private static final String TAG = "DshAndroid";
    private TextView status;
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Process dshProcess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogFile.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        status.setPadding(20, 20, 20, 20);
        sv.addView(status);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        new Thread(this::boot, "dsh-boot").start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void say(String s) {
        LogFile.log(TAG, s);
        ui.post(() -> status.append(s + "\n"));
    }

    private void boot() {
        File files = getFilesDir();
        File nld = new File(getApplicationInfo().nativeLibraryDir);
        try {
            say("=== dsh native APK boot ===");
            say("targetSdk=" + getApplicationInfo().targetSdkVersion
                    + " nativeLibraryDir=" + nld);

            // ---- 1. runtime shared libraries from assets -> filesDir/rt ----
            File rt = DshRuntime.rtDir(this);
            if (!rt.isDirectory()) {
                int n = LogFile.extractAssetDir(this, "rt", rt);
                say("extracted runtime assets: " + n);
            }
            chmodTree(rt);

            // ---- 2. prove we can exec the bundled Node runtime ----
            File node = new File(nld, "libnode.so");
            say("node=" + node + " exists=" + node.exists() + " canExec=" + node.canExecute());
            Exec.Result a = Exec.run(
                    Exec.cmd(node.getAbsolutePath(), "-e",
                            "console.log('NODE_OK ' + process.version + ' ' + process.platform + '/' + process.arch)"),
                    files,
                    env -> DshRuntime.applyEnv(env, this), 30000);
            say("node exec: " + a.toString().trim());

            // ---- 3. unpack the dsh bundle ----
            File dsh = DshRuntime.dshDir(this);
            File marker = new File(dsh, ".extracted");
            if (!marker.isFile()) {
                say("extracting dsh bundle (this takes a moment)...");
                long t0 = System.currentTimeMillis();
                int n = ZipExtractor.extract(this, "dsh-bundle.zip", dsh);
                say("extracted " + n + " files in " + (System.currentTimeMillis() - t0) + " ms");
                //noinspection ResultOfMethodCallIgnored
                marker.createNewFile();
            } else {
                say("dsh bundle already extracted");
            }

            // ---- 3b. executables must live in nativeLibraryDir, not here ----
            DshRuntime.linkExecutables(this, nld);

            // ---- 4. Termux is the bash backend, nothing more ----
            say("Termux dispatch: " + TermuxDispatch.dispatchEcho(this).trim());

            // ---- 5. start the dsh host ----
            say("starting dsh web on port " + DshRuntime.PORT + " ...");
            dshProcess = DshRuntime.startWeb(this, nld, new DshRuntime.Listener() {
                @Override public void onUrl(String url) {
                    say("DSH_WEB_URL " + url);
                    ui.post(() -> web.loadUrl(url));
                }
                @Override public void onExit(int rc) {
                    say("dsh web process exited rc=" + rc);
                }
            });
            say("dsh web launched, pid=" + (dshProcess != null ? "ok" : "?"));
            say("=== boot done ===");
        } catch (Throwable t) {
            say("BOOT_EXCEPTION " + t);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dshProcess != null) dshProcess.destroy();
    }
}
