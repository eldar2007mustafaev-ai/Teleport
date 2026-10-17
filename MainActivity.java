package com.teleport.sens;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

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
        public void applyRatio(String ratio) {
            runOnUiThread(() -> {
                try {
                    String cmd = "";
                    if (ratio.equals("4:3")) {
                        cmd = "wm size 1080x1440 && wm density 360";
                    } else if (ratio.equals("16:10")) {
                        cmd = "wm size 1080x1728 && wm density 400";
                    } else {
                        cmd = "wm size reset && wm density reset";
                    }
                    executeCommand(cmd);
                    Toast.makeText(MainActivity.this, "Режим " + ratio + " применен!", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка выполнения", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void applySensBoost() {
            runOnUiThread(() -> {
                try {
                    executeCommand("settings put system pointer_speed 7 && settings put system touch_exploration_enabled 0");
                    Toast.makeText(MainActivity.this, "Телепорт сенсы активирован!", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка активации", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void checkAndSendShizukuStatus() {
        new Thread(() -> {
            boolean isRunning = false;
            String statusMsg = "Shizuku не запущен!";
            try {
                // Проверяем доступность через sh / su (Shizuku пробрасывает права в селлы)
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "echo 'test'"});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                process.waitFor();

                if (line != null && line.contains("test")) {
                    // Проверяем, есть ли права администратора/adb через shizuku
                    Process p2 = Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size"});
                    if (p2.waitFor() == 0) {
                        isRunning = true;
                        statusMsg = "Shizuku активен и готов!";
                    } else {
                        statusMsg = "Shizuku запущен, но нет прав!";
                    }
                }
            } catch (Exception e) {
                isRunning = false;
                statusMsg = "Shizuku не запущен!";
            }

            final boolean finalRunning = isRunning;
            final String finalMsg = statusMsg;
            webView.post(() -> webView.evaluateJavascript("setShizukuStatus(" + finalRunning + ", '" + finalMsg + "');", null));
        }).start();
    }

    private void executeCommand(String command) {
        new Thread(() -> {
            try {
                // Выполнение команды через Shizuku / ADB селл
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
