package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
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
import java.lang.reflect.Method;

public class MainActivity extends Activity {
    private WebView webView;
    private static final String TAG = "Teleport";
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
        
        checkShizuku();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkShizuku();
        webView.postDelayed(() -> {
            boolean hasPermission = Settings.canDrawOverlays(MainActivity.this);
            webView.evaluateJavascript(
                "if(typeof AndroidBridge !== 'undefined') { " +
                "var hasPerm = AndroidBridge.hasOverlayPermission(); " +
                "var modal = document.getElementById('permModal'); " +
                "if(modal) { modal.style.display = hasPerm ? 'none' : 'flex'; } " +
                "}", null);
        }, 500);
    }

    // === ПРОВЕРКА SHIZUKU ЧЕРЕЗ РЕФЛЕКСИЮ ===
    private void checkShizuku() {
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Method pingBinder = shizukuClass.getMethod("pingBinder");
            boolean running = (Boolean) pingBinder.invoke(null);
            
            if (running) {
                Method checkPerm = shizukuClass.getMethod("checkSelfPermission");
                int perm = (Integer) checkPerm.invoke(null);
                shizukuAvailable = (perm == PackageManager.PERMISSION_GRANTED);
            } else {
                shizukuAvailable = false;
            }
        } catch (Exception e) {
            shizukuAvailable = false;
            Log.w(TAG, "Shizuku не найден: " + e.getMessage());
        }
        
        webView.postDelayed(() -> {
            String status = shizukuAvailable ? "✅ Shizuku активен" : "Shizuku не активен";
            webView.evaluateJavascript(
                "if(typeof updateShizukuStatus !== 'undefined') updateShizukuStatus('" + status + "', " + shizukuAvailable + ");",
                null
            );
        }, 500);
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
            return shizukuAvailable ? "✅ Shizuku активен" : "Shizuku не активен";
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
            try {
                Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
                Method pingBinder = shizukuClass.getMethod("pingBinder");
                if ((Boolean) pingBinder.invoke(null)) {
                    Method requestPerm = shizukuClass.getMethod("requestPermission", int.class);
                    requestPerm.invoke(null, 1001);
                    Toast.makeText(MainActivity.this, "Запрос разрешения...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Shizuku не запущен. Запусти его сначала.", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Shizuku не найден", Toast.LENGTH_SHORT).show();
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
        public boolean isFixSensServiceRunning() { return false; }

        @JavascriptInterface
        public boolean isFixSensActive() { return false; }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "Найди Teleport и включи", Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void toggleFixSens(boolean enable) {
            if (!shizukuAvailable) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "⚠️ Сначала запусти Shizuku и дай разрешение!", Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "FixSens: " + (enable ? "ВКЛ ✅" : "ВЫКЛ"), Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void setFixSensParams(float sharpness, float noise, float straighten) {
            if (!shizukuAvailable) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "⚠️ Shizuku не активен!", Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                String.format(" Параметры: %.2f · %.1f · %.2f", sharpness, noise, straighten),
                Toast.LENGTH_SHORT).show());
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
