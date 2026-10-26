package com.teleport.so2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    public static final String APP_VERSION = "1.0.0";
    public static final String TELEGRAM_BOT_URL = "https://t.me/so2_teleport_bot";
    private static final String PREF_NAME = "so2_teleport_prefs";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;

    private WebView webView;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("window.syncUiState && window.syncUiState();", null);
                        }
                    });
                }
            };

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Запрос прав Shizuku при старте
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
                }
            }
        } catch (Throwable ignored) {}

        // 2. Тёмная тема окна
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#060913")));
            window.setStatusBarColor(Color.parseColor("#060913"));
            window.setNavigationBarColor(Color.parseColor("#060913"));
        }

        // 3. Инициализация WebView
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#060913"));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // 4. Подключаем AndroidBridge (тот самый отдельный файл!)
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        // 5. Восстанавливаем плавающую кнопку, если была включена
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean("float_btn_enabled", false)) {
            if (Settings.canDrawOverlays(this)) {
                startService(new Intent(this, FloatingButtonService.class));
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {}
        ShizukuManager.stopTeleport();
        if (webView != null) {
            webView.destroy();
        }
    }

    // =========================================================================
    // МЕТОДЫ, КОТОРЫЕ ВЫЗЫВАЕТ AndroidBridge
    // =========================================================================

    public void updateCrosshair(String type) {
        if ("off".equalsIgnoreCase(type)) {
            runOnUiThread(() -> CrosshairOverlay.hide(this));
        } else {
            runOnUiThread(() -> CrosshairOverlay.show(this, type));
        }
    }

    public void toggleFloatingButton(boolean enabled) {
        if (enabled) {
            if (Settings.canDrawOverlays(this)) {
                startService(new Intent(this, FloatingButtonService.class));
            }
        } else {
            stopService(new Intent(this, FloatingButtonService.class));
        }
    }

    public void applyResolutionRatio(String ratio) {
        ShizukuManager.setStretchConfig(ratio, 1080, 2400, 440);
    }

    public void resetResolution() {
        ShizukuManager.resetStretch();
    }

    public void updateOverlayStealthFlag(boolean stealth) {
        runOnUiThread(() -> {
            if (stealth) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        });
    }
}
