package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
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
                checkAndSendStatus();
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            checkAndSendStatus();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                try {
                    // Пытаемся запустить само приложение Shizuku для выдачи прав
                    Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (intent != null) {
                        startActivity(intent);
                        Toast.makeText(MainActivity.this, "Откройте Shizuku и разрешите доступ", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Shizuku не установлено!", Toast.LENGTH_SHORT).show();
                    }
                    webView.postDelayed(() -> checkAndSendStatus(), 2000);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка открытия Shizuku", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void applyRatio(String ratio) {
            runOnUiThread(() -> {
                String cmd = ratio.equals("4:3") ? "wm size 1080x1440 && wm density 360" :
                             ratio.equals("16:10") ? "wm size 1080x1728 && wm density 400" :
                             "wm size reset && wm density reset";
                executeCommand(cmd);
                Toast.makeText(MainActivity.this, "Режим " + ratio + " применен", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void applySensBoost(boolean enable) {
            runOnUiThread(() -> {
                if (enable) {
                    executeCommand("settings put system pointer_speed 7 && settings put system touch_exploration_enabled 0");
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активирована!", Toast.LENGTH_SHORT).show();
                } else {
                    executeCommand("settings put system pointer_speed 5");
                    Toast.makeText(MainActivity.this, "Сенса сброшена", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void checkAndSendStatus() {
        new Thread(() -> {
            boolean isRunning = false;
            String title = "SHIZUKU — НАЖМИ";
            String sub = "Требуется авторизация";
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size"});
                if (process.waitFor() == 0) {
                    isRunning = true;
                    title = "SHIZUKU ВКЛ";
                    sub = "Доступ активен";
                }
            } catch (Exception e) {
                isRunning = false;
            }

            final boolean active = isRunning;
            final String t = title;
            final String s = sub;
            webView.post(() -> webView.evaluateJavascript("updateUIStatus(" + active + ", '" + t + "', '" + s + "');", null));
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
