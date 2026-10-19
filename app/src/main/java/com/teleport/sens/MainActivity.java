package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements Shizuku.OnRequestPermissionResultListener {
    private WebView webView;
    private static final String TAG = "Teleport";
    private static final int SHIZUKU_REQUEST_CODE = 1001;
    private boolean shizukuAvailable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LOW_PROFILE
        );
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.addJavascriptInterface(new AppController(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
        
        Shizuku.addRequestPermissionResultListener(this);
        checkShizuku();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkShizuku();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(this);
    }

    private void checkShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                int perm = Shizuku.checkSelfPermission();
                shizukuAvailable = (perm == PackageManager.PERMISSION_GRANTED);
                Log.i(TAG, "Shizuku: " + (shizukuAvailable ? "✅ активен" : "️ нет разрешения"));
            } else {
                shizukuAvailable = false;
                Log.w(TAG, "Shizuku не запущен");
            }
        } catch (Exception e) {
            shizukuAvailable = false;
            Log.e(TAG, "Shizuku ошибка: " + e.getMessage());
        }
        
        webView.postDelayed(() -> {
            webView.evaluateJavascript(
                "if(typeof updateShizukuStatus !== 'undefined') updateShizukuStatus(" + shizukuAvailable + ");",
                null
            );
        }, 300);
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            shizukuAvailable = (grantResult == PackageManager.PERMISSION_GRANTED);
            Toast.makeText(this, 
                shizukuAvailable ? "✅ Shizuku разрешение получено!" : "⚠️ Разрешение отклонено", 
                Toast.LENGTH_SHORT).show();
            checkShizuku();
        }
    }

    public class AppController {
        @JavascriptInterface
        public void executeCommand(String command) { runCommand(command); }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openPermissionSettings() {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }

        @JavascriptInterface
        public void toggleFloatingButton(boolean show) {
            Intent serviceIntent = new Intent(MainActivity.this, OverlayService.class);
            serviceIntent.putExtra("action", show ? "show" : "hide");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        @JavascriptInterface
        public boolean hasOverlayPermission() {
            return Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public String getShizukuStatus() {
            return shizukuAvailable ? "✅ Shizuku активен" : "⚠️ Shizuku не активен";
        }

        @JavascriptInterface
        public boolean isShizukuAvailable() {
            return shizukuAvailable;
        }

        @JavascriptInterface
        public void openShizukuApp() {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (intent != null) startActivity(intent);
                else Toast.makeText(MainActivity.this, "Shizuku не установлен", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                    Toast.makeText(MainActivity.this, "Запрос разрешения Shizuku...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "✅ Разрешение уже есть!", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(MainActivity.this, "⚠️ Shizuku не запущен", Toast.LENGTH_LONG).show();
            }
        }

        @JavascriptInterface
        public String getAdbCommand() {
            return "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh";
        }

        @JavascriptInterface
        public void copyAdbCommand() {
            String cmd = getAdbCommand();
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("ADB", cmd);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(MainActivity.this, "Команда скопирована", Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public boolean isFixSensServiceRunning() {
            try {
                FixSensService s = FixSensService.getInstance();
                return s != null && s.isServiceRunning();
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean isFixSensActive() {
            try {
                FixSensService s = FixSensService.getInstance();
                return s != null && s.isActive();
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "Найди Teleport и включи", Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void toggleFixSens(boolean enable) {
            try {
                FixSensService s = FixSensService.getInstance();
                if (s != null) {
                    s.setActive(enable);
                    String mode = s.isUsingShizuku() ? " (Shizuku INJECT ✅)" : " (fallback ⚠️)";
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "FixSens: " + (enable ? "ВКЛ" : "ВЫКЛ") + mode, Toast.LENGTH_LONG).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Сервис не запущен. Включи в Настройках → Специальные возможности", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void setFixSensParams(float sharpness, float noise, float straighten) {
            try {
                FixSensService s = FixSensService.getInstance();
                if (s != null) {
                    s.setProcessorParams(sharpness, noise, straighten);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        String.format("⚡ Параметры: %.2f · %.1f · %.2f", sharpness, noise, straighten),
                        Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void applyTeleportMode() {
            String[] commands = {
                "settings put global window_animation_scale 0",
                "settings put global transition_animation_scale 0",
                "settings put global animator_duration_scale 0",
                "settings put system pointer_speed 7",
                "settings put system pointer_acceleration 0"
            };
            for (String cmd : commands) { runCommand(cmd); }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "⚡ TELEPORT MODE активирован", Toast.LENGTH_SHORT).show());
        }
    }

    public static void runCommand(String cmd) {
        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) { output.append(line); }
                process.waitFor();
                Log.d(TAG, "Команда: " + cmd);
            } catch (Exception e) {
                Log.e(TAG, "Ошибка: " + e.getMessage());
            }
        }).start();
    }
}
