package com.dsh.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * dsh, as a native Android APK: a WebView over the local dsh web host.
 *
 * The host itself lives in {@link DshService} (a foreground service) so it
 * survives task switches. This activity only renders it and hosts the UI
 * surfaces that must run in an Activity: file pickers.
 *
 * The launcher activity is MainActivity; the manifest points here.
 */
public class MainActivity extends Activity {
    private static final String TAG = "DshAndroid";
    private TextView status;
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int shownGeneration = -1;
    private String loadedUrl;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogFile.init(this);
        BootLog.init(this);
        SafBridge.attach(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("SAF Open", v -> SafBridge.safPickOpen()));
        bar.addView(button("SAF Save", v -> {
            // scratch file under <filesDir>/export/, as the shared contract requires
            try {
                java.io.File ex = new java.io.File(getFilesDir(), "export");
                //noinspection ResultOfMethodCallIgnored
                ex.mkdirs();
                java.io.File f = new java.io.File(ex, "dsh-saf-save.txt");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                String body = "dsh SAF save " + new java.util.Date() + "\n"
                        + "written by com.dsh.android\n";
                fos.write(body.getBytes("UTF-8"));
                fos.close();
                SafBridge.safPickSave("dsh-saf-save.txt\t" + f.getAbsolutePath());
            } catch (Exception e) {
                BootLog.say("SAF save scratch failed: " + e);
            }
        }));
        bar.addView(button("Show log", v -> {
            shownGeneration = -1; // force a redraw of the whole buffer
            render();
        }));
        hsv.addView(bar);
        root.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        status.setPadding(20, 10, 20, 10);
        sv.addView(status);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                // An <input type=file> inside dsh comes straight to the vivo picker.
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, SafBridge.REQ_WEB);
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // The host is a foreground service: start it once, never stop it from here.
        Intent svc = new Intent(this, DshService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        ui.postDelayed(this::poll, 300);
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setOnClickListener(l);
        return b;
    }

    private void render() {
        List<String> lines = BootLog.lines();
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        status.setText(sb.toString());
    }

    private void poll() {
        int g = BootLog.generation();
        if (g != shownGeneration) {
            shownGeneration = g;
            render();
            if (loadedUrl == null) {
                String u = BootLog.url();
                if (u != null) {
                    loadedUrl = u;
                    web.loadUrl(u);
                }
            }
        }
        ui.postDelayed(this::poll, 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SafBridge.REQ_OPEN || requestCode == SafBridge.REQ_SAVE) {
            SafBridge.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == SafBridge.REQ_WEB) {
            if (fileCallback != null) {
                Uri[] uris = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        List<Uri> list = new ArrayList<>();
                        for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                            list.add(data.getClipData().getItemAt(i).getUri());
                        }
                        uris = list.toArray(new Uri[0]);
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        // Deliberately do NOT stop DshService: the host must outlive the UI.
        super.onDestroy();
        if (web != null) web.destroy();
    }
}
