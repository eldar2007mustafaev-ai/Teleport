package com.teleport.sens;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProcess;

public class MainActivity extends Activity implements Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Подписываемся на системные события Binder от Shizuku
        Shizuku.addBinderReceivedListenerSticky(this);
        Shizuku.addBinderDeadListener(this);
        
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkShizukuBinder();
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    @Override
    public void onBinderReceived() {
        checkShizukuBinder();
    }

    @Override
    public void onBinderDead() {
        checkShizukuBinder();
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            checkShizukuBinder();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                try {
                    if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                        Toast.makeText(MainActivity.this, "Старая версия Shizuku", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(MainActivity.this, "Доступ уже есть :)", Toast.LENGTH_SHORT).show();
                        checkShizukuBinder();
                    } else {
                        // Запрос разрешения через системный диалог Binder
                        Shizuku.requestPermission(0);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка запроса прав", Toast.LENGTH_SHORT).show();
                }
                webView.postDelayed(() -> checkShizukuBinder(), 2000);
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
                    String exactScript = "gain=1.8 gainx=1.8 gainy=1.8 nz=2 dz=0 dwellms=100 lock=6 chunk=28 chunkmin=0 chunkcap=600 chunkspd=0 chunkcool=5 kaccel=0 power=1.00 edge=0 straighten=0";
                    executeViaShizuku(exactScript);
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активна!", Toast.LENGTH_SHORT).show();
                } else {
                    executeViaShizuku("settings put system pointer_speed 5");
                    Toast.makeText(MainActivity.this, "Сенса сброшена", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void checkShizukuBinder() {
        new Thread(() -> {
            boolean active = false;
            try {
                // Честная проверка: пингуем сервер Shizuku и смотрим наличие разрешений
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    active = true;
                }
            } catch (Exception e) {
                active = false;
            }

            final boolean isRunning = active;
            final String title = isRunning ? "SHIZUKU ВКЛ" : "SHIZUKU — НАЖМИ";
            final String sub = isRunning ? "Доступ активен :)" : "Требуется авторизация";

            webView.post(() -> webView.evaluateJavascript("updateUIStatus(" + isRunning + ", '" + title + "', '" + sub + "');", null));
        }).start();
    }

    private void executeViaShizuku(String command) {
        new Thread(() -> {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    // Выполнение напрямую через системный процесс Shizuku с привилегиями
                    String[] cmd = {"sh", "-c", command};
                    ShizukuProcess process = new ShizukuProcess(cmd);
                    process.waitFor();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(this);
        Shizuku.removeBinderDeadListener(this);
    }
}
