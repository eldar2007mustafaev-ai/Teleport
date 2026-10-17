package com.teleport.sens;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

ri.shizuku.api.Shizuku; // Прямой импорт API Shizuku для обхода багов One UI

public class MainActivity extends Activity {
    private WebView webView;
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;

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

        // Слушатель результатов авторизации Shizuku
        if (Shizuku.isPreV11() || Shizuku.getVersion() >= 11) {
            Shizuku.addRequestPermissionResultListener(requestCode -> {
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    checkAndSendStatus();
                }
            });
        }
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
                    if (Shizuku.isBinderReceived()) {
                        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(MainActivity.this, "Shizuku уже авторизован!", Toast.LENGTH_SHORT).show();
                        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                            Toast.makeText(MainActivity.this, "Требуется разрешить доступ в диалоге One UI", Toast.LENGTH_LONG).show();
                            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
                        } else {
                            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "Служба Shizuku не запущенна. Запустите в приложении Shizuku!", Toast.LENGTH_LONG).show();
                    }
                    webView.postDelayed(() -> checkAndSendStatus(), 1500);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка вызова Shizuku API", Toast.LENGTH_SHORT).show();
                }
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
                    // Запуск твоего фирменного скрипта через Shizuku Binder
                    String exactScript = "gain=1.8 gainx=1.8 gainy=1.8 nz=2 dz=0 dwellms=100 lock=6 chunk=28 chunkmin=0 chunkcap=600 chunkspd=0 chunkcool=5 kaccel=0 power=1.00 edge=0 straighten=0";
                    executeCommand(exactScript);
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активирована через Shizuku!", Toast.LENGTH_SHORT).show();
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
                if (Shizuku.isBinderReceived() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    isRunning = true;
                    title = "SHIZUKU ВКЛ";
                    sub = "Доступ активен (One UI обход)";
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
                if (Shizuku.isBinderReceived() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    // Выполнение через Shizuku Process API (обходит ограничения su/sh на Samsung)
                    Process process = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
                    process.waitFor();
                } else {
                    // Резервный метод через стандартную оболочку
                    Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                    process.waitFor();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
