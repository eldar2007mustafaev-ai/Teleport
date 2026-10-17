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

public class MainActivity extends Activity implements Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Регистрируем слушатели Shizuku для точного отслеживания статуса
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
                updateShizukuStatusUI();
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    @Override
    public void onBinderReceived() {
        updateShizukuStatusUI();
    }

    @Override
    public void onBinderDead() {
        updateShizukuStatusUI();
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            updateShizukuStatusUI();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                    Toast.makeText(MainActivity.this, "Старая версия Shizuku", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "Права уже есть :)", Toast.LENGTH_SHORT).show();
                    updateShizukuStatusUI();
                } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                    Toast.makeText(MainActivity.this, "Дайте разрешение в приложении Shizuku", Toast.LENGTH_LONG).show();
                } else {
                    try {
                        Shizuku.requestPermission(0);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Ошибка запроса прав", Toast.LENGTH_SHORT).show();
                    }
                }
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
                    executeShizukuCommand(exactScript);
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активна!", Toast.LENGTH_SHORT).show();
                } else {
                    executeShizukuCommand("settings put system pointer_speed 5");
                    Toast.makeText(MainActivity.this, "Сенса сброшена", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void updateShizukuStatusUI() {
        boolean isRunning = false;
        try {
            isRunning = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            isRunning = false;
        }

        final boolean active = isRunning;
        final String title = active ? "SHIZUKU ВКЛ" : "SHIZUKU — НАЖМИ";
        final String sub = active ? "Доступ активен :)" : "Требуется авторизация";

        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript("updateUIStatus(" + active + ", '" + title + "', '" + sub + "');", null));
        }
    }

    private void executeShizukuCommand(String command) {
        new Thread(() -> {
            try {
                // Выполнение через официальный NewProcess Shizuku (обход песочницы One UI)
                String[] cmd = {"sh", "-c", command};
                rikka.shizuku.ShizukuProcess process = new rikka.shizuku.ShizukuProcess(cmd);
                process.waitFor();
            } catch (Exception e) {
                // Запасной вариант через стандартный exec, если процессор Shizuku занят
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                    p.waitFor();
                } catch (Exception ignored) {}
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
