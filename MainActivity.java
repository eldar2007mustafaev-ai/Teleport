package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

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
                checkAndSendShizukuStatus();
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            checkAndSendShizukuStatus();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                try {
                    if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(0);
                        } else {
                            Toast.makeText(MainActivity.this, "Права Shizuku уже выданы!", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "Запустите Shizuku в системе!", Toast.LENGTH_LONG).show();
                    }
                    checkAndSendShizukuStatus();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка запроса прав", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void applyRatio(String ratio) {
            runOnUiThread(() -> {
                String cmd = "";
                if (ratio.equals("4:3")) {
                    cmd = "wm size 1080x1440 && wm density 360";
                } else if (ratio.equals("16:10")) {
                    cmd = "wm size 1080x1728 && wm density 400";
                } else {
                    cmd = "wm size reset && wm density reset";
                }
                executeCommand(cmd);
                Toast.makeText(MainActivity.this, "Формат " + ratio + " применен", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void applySensBoost() {
            runOnUiThread(() -> {
                executeCommand("settings put system pointer_speed 7 && settings put system touch_exploration_enabled 0");
                Toast.makeText(MainActivity.this, "Отклик сенсора ускорен", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void checkAndSendShizukuStatus() {
        new Thread(() -> {
            boolean isRunning = false;
            String statusMsg = "Shizuku не запущен";
            try {
                if (Shizuku.pingBinder()) {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        isRunning = true;
                        statusMsg = "Shizuku подключен";
                    } else {
                        statusMsg = "Требуется авторизация";
                        // Автоматически вызываем системный запрос прав при старте, если запущен
                        Shizuku.requestPermission(0);
                    }
                }
            } catch (Exception e) {
                isRunning = false;
                statusMsg = "Shizuku не запущен";
            }

            final boolean finalRunning = isRunning;
            final String finalMsg = statusMsg;
            webView.post(() -> webView.evaluateJavascript("setShizukuStatus(" + finalRunning + ", '" + finalMsg + "');", null));
        }).start();
    }

    private void executeCommand(String command) {
        new Thread(() -> {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    Process process = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
                    process.waitFor();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
