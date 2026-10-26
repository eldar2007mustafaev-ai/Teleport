package com.teleport.so2;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private CrosshairOverlay crosshairOverlay;
    private int nativeShortSide = 1080;
    private int nativeLongSide = 2340;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        nativeShortSide = Math.min(dm.widthPixels, dm.heightPixels);
        nativeLongSide = Math.max(dm.widthPixels, dm.heightPixels);

        TeleportEngine.initOverlayPulse(this);
        crosshairOverlay = new CrosshairOverlay(this);

        // Автоматически лечим залипание нижней полоски и вызов Google при запуске
        healBrokenTouchTimeouts();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    public void healBrokenTouchTimeouts() {
        if (!ShizukuManager.isPermissionGranted()) return;
        new Thread(() -> {
            ShizukuManager.execCommand(
                "settings put secure long_press_timeout 500; " +
                "settings put secure multi_press_timeout 300; " +
                "settings delete secure tap_duration_threshold; " +
                "settings delete secure touch_blocking_period"
            );
        }).start();
    }

    public boolean checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Выдайте разрешение «Поверх других окон»", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
            return false;
        }
        return true;
    }

    public void updateCrosshair(String type) {
        runOnUiThread(() -> {
            if (!"off".equalsIgnoreCase(type) && !checkAndRequestOverlayPermission()) {
                return;
            }
            if (crosshairOverlay != null) {
                crosshairOverlay.setShape(type);
            }
        });
    }

    public void updateCrosshairStyle(int scale, int opacity, String colorHex) {
        runOnUiThread(() -> {
            if (crosshairOverlay != null) {
                crosshairOverlay.setScale(scale);
                crosshairOverlay.setOpacity(opacity);
                crosshairOverlay.setColorHex(colorHex);
            }
        });
    }

    public void toggleFloatingButton(boolean enabled) {
        runOnUiThread(() -> {
            if (enabled && !checkAndRequestOverlayPermission()) {
                return;
            }
            Intent serviceIntent = new Intent(this, FloatingButtonService.class);
            if (enabled) {
                startService(serviceIntent);
            } else {
                stopService(serviceIntent);
            }
        });
    }

    public void applyResolutionRatio(String ratio) {
        if (!ShizukuManager.isPermissionGranted()) {
            runOnUiThread(() -> Toast.makeText(this, "Сначала активируйте Shizuku!", Toast.LENGTH_SHORT).show());
            return;
        }

        // Используем повышенную пиксельную базу (1920 по короткой стороне),
        // чтобы в Standoff 2 было в 2 раза больше пикселей на миллиметр свайпа = взрывная сенса!
        int highShortSide = Math.max(nativeShortSide, 1920);
        int targetLongSide;

        if ("4:3".equals(ratio)) {
            targetLongSide = Math.round(highShortSide * 4f / 3f); // 1920x2560
        } else if ("3:2".equals(ratio)) {
            targetLongSide = Math.round(highShortSide * 3f / 2f); // 1920x2880
        } else if ("16:10".equals(ratio)) {
            targetLongSide = Math.round(highShortSide * 16f / 10f); // 1920x3072
        } else {
            targetLongSide = Math.round(highShortSide * 16f / 9f); // 1920x3412
        }

        final String cmd = "wm size " + highShortSide + "x" + targetLongSide;
        new Thread(() -> ShizukuManager.execCommand(cmd)).start();
    }

    public void resetResolution() {
        if (!ShizukuManager.isPermissionGranted()) return;
        new Thread(() -> ShizukuManager.execCommand("wm size reset")).start();
    }

    public void updateOverlayStealthFlag(boolean stealth) {
        runOnUiThread(() -> {
            try {
                if (stealth) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
                if (crosshairOverlay != null) {
                    crosshairOverlay.setStealthMode(stealth);
                }
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}
