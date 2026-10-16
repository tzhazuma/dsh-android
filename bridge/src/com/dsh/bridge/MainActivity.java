package com.dsh.bridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The dsh launcher UI.
 *
 * Opening this app gives you a real DeepSeek Harness interface:
 *   1. it asks Termux (via the documented RUN_COMMAND channel) to start dsh web;
 *   2. Termux also runs a tiny redirector that captures dsh's per-run ?token=;
 *   3. this WebView points at the redirector, which 302s to the authenticated UI.
 *
 * The token indirection exists because `dsh web` mints a fresh random token every
 * start, offers no flag to fix it, and an APK cannot read Termux's private files.
 */
public class MainActivity extends Activity {
    private static final String TAG = "DshBridge";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String LAUNCH_SCRIPT = HOME + "/dshx/launcher_start.sh";
    private static final int REDIR_PORT = 8898;
    private static final String URL = "http://127.0.0.1:" + REDIR_PORT + "/";

    private WebView web;
    private TextView status;
    private boolean dispatched = false;
    private boolean polling = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.parseColor("#0f1115"));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setText("正在启动 dsh…");
        status.setTextColor(Color.parseColor("#e6e6e6"));
        status.setTextSize(16f);
        status.setPadding(56, 160, 56, 56);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        Log.i(TAG, "MainActivity onCreate");
    }

    @Override protected void onResume() {
        super.onResume();
        if (!dispatched) {
            dispatched = true;
            // Dispatch only once the app is genuinely foreground: a service start
            // issued too early races the TOP-procstate promotion and is refused
            // (AOSP) or silently dropped (vivo).
            getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { dispatch(); }
                    }, 1200);
                }
            }, 300);
        }
        if (!polling) { polling = true; startPolling(); }
    }

    private void dispatch() {
        Intent i = new Intent();
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.setAction("com.termux.RUN_COMMAND");
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        // A single argv element = a script path. Never inline a shell string here:
        // it avoids every layer of quoting/re-parsing.
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{LAUNCH_SCRIPT});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "dsh-web");
        try {
            // startForegroundService must be the primary path: vivo silently drops
            // a plain startService (no exception, no log, nothing happens).
            startForegroundService(i);
            Log.i(TAG, "DISPATCH_OK startForegroundService");
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService rejected: " + t);
            try {
                startService(i);
                Log.i(TAG, "DISPATCH_OK startService");
            } catch (Throwable t2) {
                Log.e(TAG, "DISPATCH_FAIL " + t2, t2);
                status.setText("无法启动 Termux 命令服务：\n" + t2);
            }
        }
    }

    private void startPolling() {
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 90; i++) {
                    try (Socket sock = new Socket()) {
                        sock.connect(new InetSocketAddress("127.0.0.1", REDIR_PORT), 700);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                status.setVisibility(View.GONE);
                                web.loadUrl(URL);
                                Log.i(TAG, "WEBVIEW_LOAD " + URL);
                            }
                        });
                        return;
                    } catch (Exception ignored) {
                        // redirector not up yet
                    }
                    final int n = i + 1;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { status.setText("正在启动 dsh… (" + n + "s)"); }
                    });
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        status.setText("启动超时（90 秒）。\n\n请确认：\n"
                                + "1) Termux 已安装\n"
                                + "2) dsh 已装在 ~/dsh-test\n"
                                + "3) Termux 的 allow-external-apps = true 且已重启过 Termux\n"
                                + "4) 已执行 adb shell pm grant com.dsh.bridge com.termux.permission.RUN_COMMAND");
                    }
                });
            }
        }).start();
    }
}
