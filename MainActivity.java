package com.teleport.sens;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.lang.reflect.Method;

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
                    Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
                    Method requestPermission = shizukuClass.getMethod("requestPermission", int.class);
                    requestPermission.invoke(null, 0);
                    Toast.makeText(MainActivity.this, "Запрос прав отправлен в Shizuku!", Toast.LENGTH_SHORT).show();
                    webView.postDelayed(() -> checkAndSendShizukuStatus(), 1500);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Открой Shizuku вручную", Toast.LENGTH_LONG).show();
                }
            });
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
                Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
                Method pingBinder = shizukuClass.getMethod("pingBinder");
                boolean active = (boolean) pingBinder.invoke(null);

                if (active) {
                    Method checkSelfPermission = shizukuClass.getMethod("checkSelfPermission");
                    int perm = (int) checkSelfPermission.invoke(null);
                    if (perm == 0) {
                        isRunning = true;
                        statusMsg = "Shizuku активен и готов!";
                    } else {
                        statusMsg = "Нет прав! Нажми для запроса";
                        Method requestPermission = shizukuClass.getMethod("requestPermission", int.class);
                        requestPermission.invoke(null, 0);
                    }
                }
            } catch (Exception e) {
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size"});
                    if (process.waitFor() == 0) {
                        isRunning = true;
                        statusMsg = "Shizuku/Root активен!";
                    } else {
                        statusMsg = "Shizuku запущен, но нет прав!";
                    }
                } catch (Exception ex) {
                    isRunning = false;
                    statusMsg = "Shizuku не запущен!";
                }
            }

            final boolean finalRunning = isRunning;
            final String finalMsg = statusMsg;
            webView.post(() -> webView.evaluateJavascript("setShizukuStatus(" + finalRunning + ", '" + finalMsg + "');", null));
        }).start();
    }

    private void executeCommand(String command) {
        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
