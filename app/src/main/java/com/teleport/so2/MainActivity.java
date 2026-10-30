package com.teleport.so2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

public class MainActivity extends Activity {

    private WebView webView;
    private WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View crosshairView;
    private WindowManager.LayoutParams crosshairParams;
    private String currentCrosshairType = "off";
    private int crosshairScale = 50;
    private int crosshairOpacity = 100;
    private int crosshairColor = Color.parseColor("#D946EF");

    private View floatingBtnView;
    private TextView floatingTxtView;
    private View trashDropView;
    private TextView trashBoxView;
    private WindowManager.LayoutParams floatBtnParams;
    private WindowManager.LayoutParams trashParams;

    // Квантовый стробоскоп склейки чанков (работает автономно от кнопки растяга!)
    private View quantumPulseView;
    private WindowManager.LayoutParams quantumPulseParams;
    private boolean strobeWanted = false;
    private boolean isAppInForeground = true;
    private boolean pulsePhase = false;

    private boolean isStretchedActive = false;
    private boolean stealthModeEnabled = false;
    private OnBackInvokedCallback backInvokedCallback;

    private final Runnable batchStrobeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!strobeWanted || !TeleportEngine.isActive() || isAppInForeground) {
                return;
            }
            if (quantumPulseView != null && quantumPulseParams != null) {
                try {
                    pulsePhase = !pulsePhase;
                    if (pulsePhase) {
                        // Фаза удержания чанка: склеивает плавный путь пальца в один дискретный вектор
                        quantumPulseParams.width = 6;
                        quantumPulseParams.height = 6;
                        quantumPulseParams.x = 3;
                        windowManager.updateViewLayout(quantumPulseView, quantumPulseParams);
                        mainHandler.postDelayed(this, TeleportEngine.getBatchHoldMs());
                        return;
                    } else {
                        // Фаза выброса чанка в кадр игры
                        quantumPulseParams.width = 1;
                        quantumPulseParams.height = 1;
                        quantumPulseParams.x = 0;
                        windowManager.updateViewLayout(quantumPulseView, quantumPulseParams);
                    }
                } catch (Exception ignored) {}
            }
            mainHandler.postDelayed(this, TeleportEngine.getBatchReleaseIntervalMs());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        healBrokenTouchTimeouts();

        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        stealthModeEnabled = prefs.getBoolean("stealth_overlays", false);

        webView = new WebView(this);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(Color.parseColor("#070312"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        setContentView(webView);

        // Регистрация свайпа «Назад» для Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = this::handleSwipeBackNavigation;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backInvokedCallback
            );
        }
    }

    /**
     * Если открыта внутренняя карточка — возвращает на главный экран приложения.
     * Если пользователь уже на главном экране — сворачивает приложение.
     */
    private void handleSwipeBackNavigation() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){ return (window.handleSystemBack && window.handleSystemBack()) ? 'HANDLED' : 'ROOT'; })()",
                    result -> {
                        if (result == null || !result.contains("HANDLED")) {
                            moveTaskToBack(true);
                        }
                    }
            );
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleSwipeBackNavigation();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            handleSwipeBackNavigation();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAppInForeground = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        mainHandler.removeCallbacks(batchStrobeRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAppInForeground = false;
        if (strobeWanted && TeleportEngine.isActive()) {
            ensureQuantumOverlayCreated();
            mainHandler.removeCallbacks(batchStrobeRunnable);
            mainHandler.postDelayed(batchStrobeRunnable, 80);
        }
    }

    private void ensureQuantumOverlayCreated() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }
        if (quantumPulseView == null) {
            quantumPulseView = new View(this);
            quantumPulseView.setBackgroundColor(Color.TRANSPARENT);
            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            quantumPulseParams = new WindowManager.LayoutParams(
                    1, 1,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
            );
            quantumPulseParams.gravity = Gravity.TOP | Gravity.START;
            quantumPulseParams.x = 0;
            quantumPulseParams.y = 0;
            try {
                windowManager.addView(quantumPulseView, quantumPulseParams);
            } catch (Exception ignored) {}
        }
    }

    public void setQuantumOverlayStrobe(boolean enable) {
        runOnUiThread(() -> {
            strobeWanted = enable;
            mainHandler.removeCallbacks(batchStrobeRunnable);
            if (!enable) {
                if (quantumPulseView != null) {
                    try { windowManager.removeView(quantumPulseView); } catch (Exception ignored) {}
                    quantumPulseView = null;
                }
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                ensureQuantumOverlayCreated();
                if (!isAppInForeground) {
                    mainHandler.post(batchStrobeRunnable);
                }
            }
        });
    }

    public void healBrokenTouchTimeouts() {
        if (!ShizukuManager.isPermissionGranted()) return;
        new Thread(() -> {
            ShizukuManager.execCommand(
                "settings put secure long_press_timeout 400; " +
                "settings put secure multi_press_timeout 250; " +
                "settings delete secure tap_duration_threshold; " +
                "settings delete secure touch_blocking_period; " +
                "settings put system pointer_location 0; " +
                "settings put system show_touches 0"
            );
        }).start();
    }

    public boolean checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                runOnUiThread(() -> {
                    try {
                        Toast.makeText(this, "Разрешите показ поверх других окон!", Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception ignored) {}
                });
                return false;
            }
        }
        return true;
    }

    private int getStealthOverlayFlags(boolean nonTouchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (nonTouchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (stealthModeEnabled) {
            flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }
        return flags;
    }

    public void updateOverlayStealthFlag(boolean enabled) {
        this.stealthModeEnabled = enabled;
        runOnUiThread(() -> {
            try {
                if (crosshairView != null && crosshairParams != null) {
                    crosshairParams.flags = getStealthOverlayFlags(true);
                    windowManager.updateViewLayout(crosshairView, crosshairParams);
                }
                if (floatingBtnView != null && floatBtnParams != null) {
                    floatBtnParams.flags = getStealthOverlayFlags(false);
                    windowManager.updateViewLayout(floatingBtnView, floatBtnParams);
                }
            } catch (Exception ignored) {}
        });
    }

    public void updateCrosshairStyle(int scale, int opacity, String colorHex) {
        this.crosshairScale = Math.max(10, Math.min(100, scale));
        this.crosshairOpacity = Math.max(15, Math.min(100, opacity));
        try {
            this.crosshairColor = Color.parseColor(colorHex);
        } catch (Exception e) {
            this.crosshairColor = Color.parseColor("#D946EF");
        }
        runOnUiThread(() -> {
            if (crosshairView != null) crosshairView.invalidate();
        });
    }

    public void updateCrosshair(String type) {
        this.currentCrosshairType = type == null ? "off" : type;
        runOnUiThread(() -> {
            if ("off".equalsIgnoreCase(currentCrosshairType)) {
                removeCrosshairOverlay();
                return;
            }
            if (!checkAndRequestOverlayPermission()) return;

            if (crosshairView == null) {
                crosshairView = new View(this) {
                    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                    @Override
                    protected void onDraw(Canvas canvas) {
                        super.onDraw(canvas);
                        int cx = getWidth() / 2;
                        int cy = getHeight() / 2;

                        float size = 6f + (crosshairScale / 100f) * 26f;
                        float gap = 3f + (crosshairScale / 100f) * 9f;

                        paint.setColor(crosshairColor);
                        paint.setAlpha((int) (255 * (crosshairOpacity / 100f)));
                        paint.setStrokeWidth(3.0f);

                        if ("dot".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.FILL);
                            canvas.drawCircle(cx, cy, Math.max(3.5f, size * 0.28f), paint);
                        } else if ("tshape".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.STROKE);
                            canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                            canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                            canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
                        } else if ("circle".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.STROKE);
                            canvas.drawCircle(cx, cy, size * 0.65f, paint);
                            paint.setStyle(Paint.Style.FILL);
                            canvas.drawCircle(cx, cy, 2.5f, paint);
                        } else {
                            paint.setStyle(Paint.Style.STROKE);
                            canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                            canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                            canvas.drawLine(cx, cy - size, cx, cy - gap, paint);
                            canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
                        }
                    }
                };

                int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                crosshairParams = new WindowManager.LayoutParams(
                        180, 180,
                        overlayType,
                        getStealthOverlayFlags(true),
                        PixelFormat.TRANSLUCENT
                );
                crosshairParams.gravity = Gravity.CENTER;
                try {
                    windowManager.addView(crosshairView, crosshairParams);
                } catch (Exception ignored) {}
            } else {
                crosshairView.invalidate();
            }
        });
    }

    private void removeCrosshairOverlay() {
        if (crosshairView != null) {
            try { windowManager.removeView(crosshairView); } catch (Exception ignored) {}
            crosshairView = null;
        }
    }

    private boolean isOverTrashBox(float rawTouchX, float rawTouchY) {
        if (trashBoxView != null && trashDropView != null && trashDropView.getVisibility() == View.VISIBLE) {
            int[] boxLoc = new int[2];
            trashBoxView.getLocationOnScreen(boxLoc);
            int boxLeft = boxLoc[0] - 90;
            int boxTop = boxLoc[1] - 110;
            int boxRight = boxLoc[0] + trashBoxView.getWidth() + 90;
            int boxBottom = boxLoc[1] + trashBoxView.getHeight() + 140;
            if (rawTouchX >= boxLeft && rawTouchX <= boxRight && rawTouchY >= boxTop && rawTouchY <= boxBottom) {
                return true;
            }
        }
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        return rawTouchY >= (dm.heightPixels - 210);
    }

    /**
     * ПЛАВАЮЩАЯ КНОПКА УПРАВЛЯЕТ СТРОГО РАСТЯГОМ ЭКРАНА (БЕЗ ВМЕШАТЕЛЬСТВА В ТЕЛЕПОРТ)
     */
    public void toggleFloatingButton(boolean enable) {
        runOnUiThread(() -> {
            if (!enable) {
                removeFloatingButton();
                return;
            }
            if (!checkAndRequestOverlayPermission()) return;
            if (floatingBtnView != null) return;

            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            trashDropView = new FrameLayout(this);
            trashBoxView = new TextView(this);
            trashBoxView.setText("Перетащите сюда для удаления");
            trashBoxView.setTextColor(Color.WHITE);
            trashBoxView.setTextSize(13f);
            trashBoxView.setGravity(Gravity.CENTER);
            trashBoxView.setPadding(46, 22, 46, 22);

            GradientDrawable trashBg = new GradientDrawable();
            trashBg.setColor(Color.parseColor("#E6190B33"));
            trashBg.setCornerRadius(999f);
            trashBg.setStroke(4, Color.parseColor("#F43F5E"));
            trashBoxView.setBackground(trashBg);

            ((FrameLayout) trashDropView).addView(trashBoxView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            ));
            trashDropView.setVisibility(View.GONE);

            trashParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    210,
                    overlayType,
                    getStealthOverlayFlags(true),
                    PixelFormat.TRANSLUCENT
            );
            trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            trashParams.y = 16;

            FrameLayout bubbleContainer = new FrameLayout(this);
            floatingTxtView = new TextView(this);
            updateFloatingBubbleAppearance();

            bubbleContainer.addView(floatingTxtView, new FrameLayout.LayoutParams(124, 124, Gravity.CENTER));
            floatingBtnView = bubbleContainer;

            floatBtnParams = new WindowManager.LayoutParams(
                    136, 136,
                    overlayType,
                    getStealthOverlayFlags(false),
                    PixelFormat.TRANSLUCENT
            );
            floatBtnParams.gravity = Gravity.BOTTOM | Gravity.END;
            floatBtnParams.x = 26;
            floatBtnParams.y = 140;

            floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
                private int initX, initY;
                private float touchX, touchY;
                private boolean moved = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            initX = floatBtnParams.x;
                            initY = floatBtnParams.y;
                            touchX = event.getRawX();
                            touchY = event.getRawY();
                            moved = false;
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) (touchX - event.getRawX());
                            int dy = (int) (touchY - event.getRawY());
                            if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                                moved = true;
                                if (trashDropView != null && trashDropView.getVisibility() != View.VISIBLE) {
                                    trashDropView.setVisibility(View.VISIBLE);
                                }
                            }
                            floatBtnParams.x = initX + dx;
                            floatBtnParams.y = initY + dy;
                            try {
                                windowManager.updateViewLayout(floatingBtnView, floatBtnParams);
                            } catch (Exception ignored) {}
                            return true;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            boolean dropInTrash = moved && isOverTrashBox(event.getRawX(), event.getRawY());
                            if (trashDropView != null) {
                                trashDropView.setVisibility(View.GONE);
                            }
                            if (dropInTrash) {
                                getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                                        .edit().putBoolean("float_btn_enabled", false).apply();
                                removeFloatingButton();
                                Toast.makeText(MainActivity.this, "Кнопка растяга убрана", Toast.LENGTH_SHORT).show();
                                return true;
                            }
                            if (!moved) {
                                // СТРОГО ТОЛЬКО РАСТЯГ ЭКРАНА!
                                SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
                                String ratio = prefs.getString("stretch_ratio", "4:3");
                                if (!isStretchedActive) {
                                    applyResolutionRatio(ratio);
                                    Toast.makeText(MainActivity.this, "Растяг " + ratio + " включён", Toast.LENGTH_SHORT).show();
                                } else {
                                    resetResolution();
                                    Toast.makeText(MainActivity.this, "Растяг сброшен (Стандартный экран)", Toast.LENGTH_SHORT).show();
                                }
                            }
                            return true;
                    }
                    return false;
                }
            });

            try {
                windowManager.addView(trashDropView, trashParams);
                windowManager.addView(floatingBtnView, floatBtnParams);
            } catch (Exception ignored) {}
        });
    }

    private void updateFloatingBubbleAppearance() {
        if (floatingTxtView == null) return;
        floatingTxtView.setText("T");
        floatingTxtView.setTextColor(Color.WHITE);
        floatingTxtView.setTextSize(19f);
        floatingTxtView.setGravity(Gravity.CENTER);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.RECTANGLE);
        circle.setCornerRadius(36f);
        if (isStretchedActive) {
            circle.setColor(Color.parseColor("#E67C3AED"));
            circle.setStroke(4, Color.parseColor("#00F0FF"));
        } else {
            circle.setColor(Color.parseColor("#D90B0719"));
            circle.setStroke(3, Color.parseColor("#A855F7"));
        }
        floatingTxtView.setBackground(circle);
    }

    private void removeFloatingButton() {
        if (floatingBtnView != null) {
            try { windowManager.removeView(floatingBtnView); } catch (Exception ignored) {}
            floatingBtnView = null;
        }
        if (trashDropView != null) {
            try { windowManager.removeView(trashDropView); } catch (Exception ignored) {}
            trashDropView = null;
        }
    }

    /**
     * ЧИСТЫЙ РАСТЯГ ЭКРАНА + БЛОКИРОВКА ПЕРЕВОРОТА ИГРЫ ДАЖЕ ПРИ ВКЛЮЧЕННОМ АВТОПОВОРОТЕ:
     * Отключает датчик автоповорота (`accelerometer_rotation 0`) и фиксирует ориентацию окна,
     * чтобы Standoff 2 никогда не переворачивался при растяге.
     */
    public void applyResolutionRatio(String ratio) {
        if (!ShizukuManager.isPermissionGranted()) return;

        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        String cleanRatio = (ratio == null || ratio.trim().isEmpty()) ? "4:3" : ratio.trim();
        prefs.edit().putString("stretch_ratio", cleanRatio).apply();

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);

        int savedBaseShort = prefs.getInt("native_short_px", 0);
        int savedBaseLong = prefs.getInt("native_long_px", 0);
        int savedBaseDpi = prefs.getInt("native_dpi", 0);

        if (savedBaseShort <= 0 || savedBaseLong <= 0 || savedBaseDpi <= 0) {
            savedBaseShort = Math.min(dm.widthPixels, dm.heightPixels);
            savedBaseLong = Math.max(dm.widthPixels, dm.heightPixels);
            savedBaseDpi = dm.densityDpi;
            prefs.edit()
                    .putInt("native_short_px", savedBaseShort)
                    .putInt("native_long_px", savedBaseLong)
                    .putInt("native_dpi", savedBaseDpi)
                    .apply();
        }

        final int baseShort = savedBaseShort;
        int targetLong;
        if ("5:4".equals(cleanRatio)) {
            targetLong = Math.round(baseShort * 5f / 4f);
        } else if ("4:3".equals(cleanRatio)) {
            targetLong = Math.round(baseShort * 4f / 3f);
        } else if ("3:2".equals(cleanRatio)) {
            targetLong = Math.round(baseShort * 3f / 2f);
        } else if ("16:10".equals(cleanRatio)) {
            targetLong = Math.round(baseShort * 16f / 10f);
        } else {
            targetLong = Math.round(baseShort * 16f / 9f);
        }

        if ((targetLong & 1) == 1) targetLong++;
        final int finalLong = targetLong;
        final int targetDpi = Math.max(220, Math.round(savedBaseDpi * 0.88f));

        isStretchedActive = true;
        runOnUiThread(this::updateFloatingBubbleAppearance);

        new Thread(() -> {
            String cmd =
                "settings put system accelerometer_rotation 0; " +
                "cmd window set-ignore-orientation-request true; " +
                "cmd window set-fix-to-user-rotation enabled; " +
                "settings put global window_animation_scale 0.0; " +
                "settings put global transition_animation_scale 0.0; " +
                "wm size " + baseShort + "x" + finalLong + "; " +
                "wm density " + targetDpi + "; " +
                "cmd game downscale disable com.axlebolt.standoff2";
            ShizukuManager.execCommand(cmd);
        }).start();
    }

    public void resetResolution() {
        if (!ShizukuManager.isPermissionGranted()) return;
        isStretchedActive = false;
        runOnUiThread(this::updateFloatingBubbleAppearance);

        new Thread(() -> {
            ShizukuManager.execCommand(
                "wm size reset; " +
                "wm density reset; " +
                "cmd window set-fix-to-user-rotation default; " +
                "cmd window set-ignore-orientation-request false; " +
                "settings put system accelerometer_rotation 0"
            );
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
        setQuantumOverlayStrobe(false);
        removeCrosshairOverlay();
        removeFloatingButton();
    }
}
