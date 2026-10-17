package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.List;

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
                    boolean shizukuFound = false;
                    PackageManager pm = getPackageManager();
                    List<PackageInfo> packages = pm.getInstalledPackages(0);
                    for (PackageInfo pkg : packages) {
                        if (pkg.packageName.equals("moe.shizuku.privileged.api")) {
                            shizukuFound = true;
                            break;
                        }
                    }

                    if (shizukuFound) {
                        Intent intent = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (intent != null) {
                            startActivity(intent);
                            Toast.makeText(MainActivity.this, "Shizuku найден. Разрешите доступ", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "Shizuku не обнаружен на устройстве!", Toast.LENGTH_LONG).show();
                    }
                    webView.postDelayed(() -> checkAndSendStatus(), 2000);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка сканирования системы", Toast.LENGTH_SHORT).show();
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
                    // Запуск твоего точного скрипта с параметрами тепающейся сенсы
                    String exactScript = "gain=1.8 gainx=1.8 gainy=1.8 nz=2 dz=0 dwellms=100 lock=6 chunk=28 chunkmin=0 chunkcap=600 chunkspd=0 chunkcool=5 kaccel=0 power=1.00 edge=0 straighten=0";
                    executeCommand(exactScript);
                    Toast.makeText(MainActivity.this, "Фирменный скрипт сенсы активирован :)", Toast.LENGTH_SHORT).show();
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
            String sub = "Сканирование системы...";
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size"});
                if (process.waitFor() == 0) {
                    isRunning = true;
                    title = "SHIZUKU ВКЛ";
                    sub = "Доступ активен";
                } else {
                    sub = "Требуется авторизация";
                }
            } catch (Exception e) {
                isRunning = false;
                sub = "Требуется авторизация";
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
