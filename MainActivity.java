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
    private boolean hasShownPermissionRequest = false;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean canDrawOverlays = Settings.canDrawOverlays(this);
            if (!canDrawOverlays && !hasShownPermissionRequest) {
                hasShownPermissionRequest = true;
                webView.postDelayed(() -> {
                    webView.evaluateJavascript(
                        "document.getElementById('permModal').style.display = 'flex';", 
                        null
                    );
                }, 500);
            } else if (canDrawOverlays) {
                webView.postDelayed(() -> {
                    webView.evaluateJavascript("hideModal();", null);
                }, 300);
            }
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                hasShownPermissionRequest = true;
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(MainActivity.this);
            }
            return true;
        }

        // МАКСИМАЛЬНЫЙ TELEPORT MODE
        @JavascriptInterface
        public void applyTeleportMode() {
            String[] commands = {
                // Отключаем ВСЕ анимации системы
                "settings put global window_animation_scale 0",
                "settings put global transition_animation_scale 0",
                "settings put global animator_duration_scale 0",
                
                // Максимальная скорость указателя
                "settings put system pointer_speed 7",
                "settings put secure pointer_speed 7",
                
                // Отключаем акселерацию и сглаживание
                "settings put system pointer_acceleration 0",
                "settings put secure pointer_acceleration 0",
                
                // Отключаем touch batching (мгновенная отправка событий)
                "settings put system touch_exploration_enabled 0",
                "settings put global touch_pressure_scale 0",
                
                // Отключаем предсказание движения
                "settings put secure show_ime_with_hard_keyboard 0",
                "settings put global motion_event_injection_timeout 0",
                
                // Оптимизации для снижения input lag
                "settings put global lowpower_standby 0",
                "settings put global adaptive_sleep 0",
                
                // Максимальная частота опроса тачскрина
                "settings put system touch_polling_rate 1000"
            };
            
            for (String cmd : commands) {
                runCommand(cmd);
            }
            
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "⚡ TELEPORT MODE активирован", Toast.LENGTH_SHORT).show());
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
