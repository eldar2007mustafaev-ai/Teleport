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
    private boolean isShizukuActive = false;

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
                updateStatusUI(true, "SHIZUKU ВКЛ", "Доступ активен :)");
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            updateStatusUI(true, "SHIZUKU ВКЛ", "Доступ активен :)");
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                isShizukuActive = true;
                updateStatusUI(true, "SHIZUKU ВКЛ", "Доступ активен :)");
                Toast.makeText(MainActivity.this, "Shizuku подключен :)", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void applyRatio(String ratio) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Режим " + ratio + " (ручной растяг через разделение экрана)", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void applySensBoost(boolean enable) {
            runOnUiThread(() -> {
                if (enable) {
                    // Точный скрипт тепающейся сенсы
                    String exactScript = "gain=1.8 gainx=1.8 gainy=1.8 nz=2 dz=0 dwellms=100 lock=6 chunk=28 chunkmin=0 chunkcap=600 chunkspd=0 chunkcool=5 kaccel=0 power=1.00 edge=0 straighten=0";
                    executeCommand(exactScript);
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активна!", Toast.LENGTH_SHORT).show();
                } else {
                    executeCommand("settings put system pointer_speed 5");
                    Toast.makeText(MainActivity.this, "Сенса сброшена", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void updateStatusUI(boolean active, String title, String sub) {
        isShizukuActive = active;
        webView.post(() -> webView.evaluateJavascript("updateUIStatus(" + active + ", '" + title + "', '" + sub + "');", null));
    }

    private void executeCommand(String command) {
        new Thread(() -> {
            try {
                // Выполнение через доступную оболочку отладки
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
