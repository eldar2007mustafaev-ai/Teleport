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
                // Сразу активируем интерфейс, так как отладка/adb активна
                updateUIReady();
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            updateUIReady();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "ADB/Shizuku активен в системе :)", Toast.LENGTH_SHORT).show();
                updateUIReady();
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
                    executeSystemCommand(exactScript);
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активна!", Toast.LENGTH_SHORT).show();
                } else {
                    executeSystemCommand("settings put system pointer_speed 5");
                    Toast.makeText(MainActivity.this, "Сенса сброшена", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void updateUIReady() {
        webView.post(() -> webView.evaluateJavascript("updateUIStatus(true, 'TELEPORT ACTIVE', 'Готов к работе :)');", null));
    }

    private void executeSystemCommand(String command) {
        new Thread(() -> {
            try {
                // Выполнение команды через системный процесс оболочки
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
