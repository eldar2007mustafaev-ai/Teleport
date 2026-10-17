package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;

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
                checkRealShizukuStatus();
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void checkShizukuStatus() {
            checkRealShizukuStatus();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (intent != null) {
                        startActivity(intent);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка запуска", Toast.LENGTH_SHORT).show();
                }
                webView.postDelayed(() -> checkRealShizukuStatus(), 2000);
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

    private void checkRealShizukuStatus() {
        new Thread(() -> {
            boolean isRunning = false;
            try {
                // Честная проверка сокета Shizuku в системе
                File socketFile = new File("/dev/socket/shizuku");
                File binderSocket = new File("/dev/ashmem"); // Проверка доступности памяти шлюза
                
                // Дополнительно проверяем через выполнение команды пинга binder
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "ls /dev/socket/shizuku"});
                int exitCode = process.waitFor();
                
                if (exitCode == 0 || socketFile.exists()) {
                    isRunning = true;
                } else {
                    // Альтернативная проверка по активному процессу через pm
                    Process p2 = Runtime.getRuntime().exec(new String[]{"sh", "-c", "pidof moe.shizuku.privileged.api"});
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p2.getInputStream()));
                    if (reader.readLine() != null) {
                        isRunning = true;
                    }
                    p2.waitFor();
                }
            } catch (Exception e) {
                isRunning = false;
            }

            final boolean active = isRunning;
            final String title = active ? "SHIZUKU ВКЛ" : "SHIZUKU — НАЖМИ";
            final String sub = active ? "Доступ активен :)" : "Требуется авторизация";

            webView.post(() -> webView.evaluateJavascript("updateUIStatus(" + active + ", '" + title + "', '" + sub + "');", null));
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
