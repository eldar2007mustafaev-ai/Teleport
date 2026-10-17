package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 1234;

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

        // Проверяем разрешение на показ плавающей кнопки поверх окон
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
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
                String cmd = "";
                if (ratio.equals("4:3")) {
                    cmd = "wm size 1080x1440 && wm density 360";
                } else if (ratio.equals("16:10")) {
                    cmd = "wm size 1080x1728 && wm density 400";
                } else {
                    cmd = "wm size reset && wm density reset";
                }
                executeCommand(cmd);
                Toast.makeText(MainActivity.this, "Плавающая кнопка активирована для режима " + ratio, Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void applySensBoost(boolean enable) {
            runOnUiThread(() -> {
                if (enable) {
                    String sensCmd = "settings put system pointer_speed 11 && " +
                                     "settings put global pointer_speed 11 && " +
                                     "settings put system touch_exploration_enabled 0";
                    executeCommand(sensCmd);
                    Toast.makeText(MainActivity.this, "Тепающаяся сенса активирована!", Toast.LENGTH_SHORT).show();
                } else {
                    executeCommand("settings put system pointer_speed 5 && settings put global pointer_speed 5");
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
