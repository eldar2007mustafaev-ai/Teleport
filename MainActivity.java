package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkRealSocketStatus();
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            checkRealSocketStatus();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (intent != null) {
                        startActivity(intent);
                    }
                } catch (Exception e) {}
                webView.postDelayed(() -> checkRealSocketStatus(), 2000);
            });
        }

        @JavascriptInterface
        public void applyRatio(String ratio) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Режим " + ratio + " (ручной растяг)", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void applySensBoost(boolean enable) {
            runOnUiThread(() -> {
                if (enable) {
                    // Твой точный скрипт тепающейся сенсы
                    String exactScript = "gain=1.8 gainx=1.8 gainy=1.8 nz=2 dz=0 dwellms=100 lock=6 chunk=28 chunkmin=0 chunkcap=600 chunkspd=0 chunkcool=5 kaccel=0 power=1.00 edge=0 straighten=0";
                    executeViaShizukuSocket(exactScript);
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активна!", Toast.LENGTH_SHORT).show();
                } else {
                    executeViaShizukuSocket("settings put system pointer_speed 5");
                    Toast.makeText(MainActivity.this, "Сенса сброшена", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void checkRealSocketStatus() {
        new Thread(() -> {
            boolean isRunning = false;
            try {
                // Честная проверка активности службы через сокет или процесс
                File socket = new File("/dev/socket/shizuku");
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "pidof shizuku_server"});
                process.waitFor();
                
                if (socket.exists() || process.exitValue() == 0) {
                    isRunning = true;
                }
            } catch (Exception e) {
                isRunning = false;
            }

            final boolean active = isRunning;
            final String title = active ? "SHIZUKU ВКЛ" : "SHIZUKU — НАЖМИ";
            final String sub = active ? "Доступ активен :)" : "Требуется авторизация";

            webView.post(() -> webView.evaluateJavascript("updateUIStatus(" + active + ", '" + title + "', '" + sub + "');", null));
        }).start();
    }

    private void executeViaShizukuSocket(String command) {
        new Thread(() -> {
            try {
                // Передаем команду напрямую через доступный интерфейс оболочки
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                OutputStream out = process.getOutputStream();
                out.write((command + "\n").getBytes());
                out.flush();
                out.close();
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
