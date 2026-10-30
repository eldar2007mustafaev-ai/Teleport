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
import android.graphics.RectF;
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
import android.view.Surface;
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
    private CyberTrashView cyberTrashIconView;
    private WindowManager.LayoutParams floatBtnParams;
    private WindowManager.LayoutParams trashParams;

    // Квантовый стробоскоп склейки чанков (работает только в фоне поверх игры!)
    private View quantumPulseView;
    private WindowManager.LayoutParams quantumPulseParams;
    private boolean strobeWanted = false;
    private boolean isAppInForeground = true;
    private boolean pulsePhase = false;

    private boolean isStretchedActive = false;
    private boolean stealthModeEnabled = false;
    private OnBackInvokedCallback backInvokedCallback;

    /**
     * КАСТОМНОЕ НЕОНОВОЕ ВЕДРО УДАЛЕНИЯ С АНИМАЦИЕЙ ОТКРЫТИЯ КРЫШКИ И ПОРТАЛОМ
     */
    private static class CyberTrashView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pillRect = new RectF();
        private final RectF binBody = new RectF();
        private boolean hovered = false;

        public CyberTrashView(Context context) {
            super(context);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(4.5f);

            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(4.2f);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(32f);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }

        public void setHovered(boolean isHover) {
            if (this.hovered != isHover) {
                this.hovered = isHover;
                animate().scaleX(isHover ? 1.12f : 1.0f)
                        .scaleY(isHover ? 1.12f : 1.0f)
                        .setDuration(140)
                        .start();
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float pad = 12f;
            pillRect.set(pad, pad, w - pad, h - pad);

            if (hovered) {
                bgPaint.setColor(Color.parseColor("#F24C0519"));
                borderPaint.setColor(Color.parseColor("#FF2E93"));
                iconPaint.setColor(Color.parseColor("#FF2E93"));
            } else {
                bgPaint.setColor(Color.parseColor("#EB140828"));
                borderPaint.setColor(Color.parseColor("#A855F7"));
                iconPaint.setColor(Color.parseColor("#D946EF"));
            }

            canvas.drawRoundRect(pillRect, 999f, 999f, bgPaint);
            canvas.drawRoundRect(pillRect, 999f, 999f, borderPaint);

            // Рисуем неоновое ведро слева внутри капсулы
            float cx = pad + 64f;
            float cy = h / 2f;

            // Корпус корзины
            binBody.set(cx - 18f, cy - 10f, cx + 18f, cy + 22f);
            canvas.drawRoundRect(binBody, 6f, 6f, iconPaint);

            // Внутренние неоновые рёбра ведра
            canvas.drawLine(cx - 7f, cy - 3f, cx - 7f, cy + 15f, iconPaint);
            canvas.drawLine(cx + 7f, cy - 3f, cx + 7f, cy + 15f, iconPaint);

            // Крышка ведра (приподнимается при наведении кнопки)
            float lidOffsetY = hovered ? -9f : -3f;
            canvas.drawLine(cx - 23f, cy - 12f + lidOffsetY, cx + 23f, cy - 12f + lidOffsetY, iconPaint);
            canvas.drawLine(cx - 8f, cy - 18f + lidOffsetY, cx + 8f, cy - 18f + lidOffsetY, iconPaint);

            // Текст подсказки
            String label = hovered ? "ОТПУСТИТЕ ДЛЯ УДАЛЕНИЯ" : "УБРАТЬ КНОПКУ «T»";
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = cy - (fm.ascent + fm.descent) / 2f;
            textPaint.setColor(hovered ? Color.parseColor("#FFE4E6") : Color.parseColor("#F3E8FF"));
            canvas.drawText(label, cx + 42f, textY, textPaint);
        }
    }

    /**
     * КВАНТОВЫЙ СТРОБОСКОП ПАКЕТОВ ВВОДА (MULTI-PULSE INPUT BATCHING):
     * Во время фазы удержания делает серию быстрых транзакций окна, заставляя SurfaceFlinger
     * и InputDispatcher склеить путь пальца в один резкий дискретный прыжок камеры.
     */
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
                        int burst = TeleportEngine.getQuantumBurstCount();
                        for (int i = 0; i < burst; i++) {
                            quantumPulseParams.width = 4 + (i % 3);
                            quantumPulseParams.height = 4 + (i % 3);
                            quantumPulseParams.x = (i % 2 == 0) ? 2 : 0;
                            windowManager.updateViewLayout(quantumPulseView, quantumPulseParams);
                        }
                        mainHandler.postDelayed(this, TeleportEngine.getBatchHoldMs());
                        return;
                    } else {
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

        // Жёсткая вертикальная фиксация самого приложения SO2Teleport
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = this::handleSwipeBackNavigation;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backInvokedCallback
            );
        }
    }

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
                        Toast.makeText(this, "Разрешите показ поверх других окон для SO2Teleport!", Toast.LENGTH_LONG).show();
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
        if (cyberTrashIconView != null && trashDropView != null && trashDropView.getVisibility() == View.VISIBLE) {
            int[] boxLoc = new int[2];
            cyberTrashIconView.getLocationOnScreen(boxLoc);
            int boxLeft = boxLoc[0] - 80;
            int boxTop = boxLoc[1] - 90;
            int boxRight = boxLoc[0] + cyberTrashIconView.getWidth() + 80;
            int boxBottom = boxLoc[1] + cyberTrashIconView.getHeight() + 120;
            if (rawTouchX >= boxLeft && rawTouchX <= boxRight && rawTouchY >= boxTop && rawTouchY <= boxBottom) {
                return true;
            }
        }
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        return rawTouchY >= (dm.heightPixels - 200);
    }

    /**
     * ПЛАВАЮЩАЯ КНОПКА «T» С НЕОНОВЫМ ВЕДРОМ УДАЛЕНИЯ (ВКЛЮЧАЕТ РАСТЯГ В ИГРЕ)
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
            cyberTrashIconView = new CyberTrashView(this);

            ((FrameLayout) trashDropView).addView(cyberTrashIconView, new FrameLayout.LayoutParams(
                    580,
                    126,
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
            trashParams.y = 20;

            FrameLayout bubbleContainer = new FrameLayout(this);
            floatingTxtView = new TextView(this);
            updateFloatingBubbleAppearance();

            bubbleContainer.addView(floatingTxtView, new FrameLayout.LayoutParams(124, 124, Gravity.CENTER));
            floatingBtnView = bubbleContainer;

            floatBtnParams = new WindowManager.LayoutParams(
                    138, 138,
                    overlayType,
                    getStealthOverlayFlags(false),
                    PixelFormat.TRANSLUCENT
            );
            floatBtnParams.gravity = Gravity.BOTTOM | Gravity.END;
            floatBtnParams.x = 28;
            floatBtnParams.y = 150;

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
                                    trashDropView.setAlpha(0f);
                                    trashDropView.setVisibility(View.VISIBLE);
                                    trashDropView.animate().alpha(1f).setDuration(150).start();
                                }
                            }
                            floatBtnParams.x = initX + dx;
                            floatBtnParams.y = initY + dy;
                            try {
                                windowManager.updateViewLayout(floatingBtnView, floatBtnParams);
                            } catch (Exception ignored) {}

                            if (cyberTrashIconView != null) {
                                cyberTrashIconView.setHovered(isOverTrashBox(event.getRawX(), event.getRawY()));
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            boolean dropInTrash = moved && isOverTrashBox(event.getRawX(), event.getRawY());
                            if (cyberTrashIconView != null) {
                                cyberTrashIconView.setHovered(false);
                            }
                            if (trashDropView != null) {
                                trashDropView.setVisibility(View.GONE);
                            }
                            if (dropInTrash) {
                                getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                                        .edit().putBoolean("float_btn_enabled", false).apply();
                                if (isStretchedActive) {
                                    resetResolution();
                                }
                                removeFloatingButton();
                                Toast.makeText(MainActivity.this, "Кнопка «T» удалена", Toast.LENGTH_SHORT).show();
                                return true;
                            }
                            if (!moved) {
                                SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
                                String ratio = prefs.getString("stretch_ratio", "4:3");
                                if (!isStretchedActive) {
                                    applyResolutionRatio(ratio);
                                    Toast.makeText(MainActivity.this, "Растяг " + ratio + " ВКЛ", Toast.LENGTH_SHORT).show();
                                } else {
                                    resetResolution();
                                    Toast.makeText(MainActivity.this, "Стандартный экран", Toast.LENGTH_SHORT).show();
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
     * РАБОЧИЙ РАСТЯГ В ИГРЕ С ФИКСАЦИЕЙ ОРИЕНТАЦИИ (БЕЗ ПЕРЕВОРОТА STANDOFF 2 И ПРИЛОЖЕНИЯ):
     * set-ignore-orientation-request выставлен в false, чтобы Android уважал альбомный режим Standoff 2
     * и портретный режим SO2Teleport!
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

        if (savedBaseShort <= 0 || savedBaseLong <= 0) {
            savedBaseShort = Math.min(dm.widthPixels, dm.heightPixels);
            savedBaseLong = Math.max(dm.widthPixels, dm.heightPixels);
            prefs.edit()
                    .putInt("native_short_px", savedBaseShort)
                    .putInt("native_long_px", savedBaseLong)
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

        // Определяем текущий альбомный поворот (1 = 90°, 3 = 270°), чтобы зафиксировать игру без переворота
        int currentRot = windowManager.getDefaultDisplay().getRotation();
        final int lockUserRot = (currentRot == Surface.ROTATION_270) ? 3 : 1;

        isStretchedActive = true;
        runOnUiThread(this::updateFloatingBubbleAppearance);

        new Thread(() -> {
            String cmd =
                "cmd window set-ignore-orientation-request false; " +
                "cmd window set-fix-to-user-rotation disabled; " +
                "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation " + lockUserRot + "; " +
                "settings put global window_animation_scale 0.0; " +
                "settings put global transition_animation_scale 0.0; " +
                "wm size " + baseShort + "x" + finalLong;
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
                "cmd window set-ignore-orientation-request false; " +
                "cmd window set-fix-to-user-rotation default; " +
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
