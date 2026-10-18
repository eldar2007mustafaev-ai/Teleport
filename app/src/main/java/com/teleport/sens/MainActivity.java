package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
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

public class MainActivity extends Activity {
    private WebView webView;

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Проверяем разрешение при каждом возврате в приложение
        webView.postDelayed(() -> {
            boolean hasPermission = Settings.canDrawOverlays(this);
            webView.evaluateJavascript(
                "if(typeof AndroidBridge !== 'undefined') { " +
                "var hasPerm = AndroidBridge.hasOverlayPermission(); " +
                "var modal = document.getElementById('permModal'); " +
                "if(modal) { modal.style.display = hasPerm ? 'none' : 'flex'; } " +
                "}", 
                null
            );
        }, 500);
    }

    public class AppController {
        @JavascriptInterface
        public void executeCommand(String command) {
            runCommand(command);
        }

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
            return "Shizuku проверка...";
        }

        // Заглушки для FixSens
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
            runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                "FixSens: " + (enable ? "ВКЛ" : "ВЫКЛ"), Toast.LENGTH_SHORT).show());
        }
        
        @JavascriptInterface
        public void setFixSensParams(float sharpness, float noise, float straighten) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                String.format("%.2f · %.1f · %.2f", sharpness, noise, straighten), 
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
            for (String cmd : commands) {
                runCommand(cmd);
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                " TELEPORT MODE", Toast.LENGTH_SHORT).show());
        }
    }

    public static void runCommand(String cmd) {
        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
                process.waitFor();
                Log.d("Teleport", "Команда: " + cmd);
            } catch (Exception e) {
                Log.e("Teleport", "Ошибка: " + e.getMessage());
            }
        }).start();
    }
}
