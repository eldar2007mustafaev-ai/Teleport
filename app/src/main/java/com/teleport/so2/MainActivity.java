package com.teleport.so2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_RESULT_CODE = 3005;
    private ValueCallback<Uri[]> filePathCallback;

    private WebView webView;
    private WindowManager windowManager;

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

    private boolean isStretchedActive = false;
    private boolean stealthModeEnabled = false;
    private int cachedScreenHeight = 2400;
    private OnBackInvokedCallback backInvokedCallback;

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
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(4.5f);

            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(4.2f);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(30f);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }

        public void setHovered(boolean isHover) {
            if (this.hovered != isHover) {
                this.hovered = isHover;
                animate().scaleX(isHover ? 1.10f : 1.0f)
                        .scaleY(isHover ? 1.10f : 1.0f)
                        .setDuration(120)
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
                iconPaint.setColor(Color.parseColor("#00E5FF"));
            }

            canvas.drawRoundRect(pillRect, 999f, 999f, bgPaint);
            canvas.drawRoundRect(pillRect, 999f, 999f, borderPaint);

            float cx = pad + 62f;
            float cy = h / 2f;

            binBody.set(cx - 17f, cy - 9f, cx + 17f, cy + 21f);
            canvas.drawRoundRect(binBody, 6f, 6f, iconPaint);

            canvas.drawLine(cx - 6f, cy - 2f, cx - 6f, cy + 14f, iconPaint);
            canvas.drawLine(cx + 6f, cy - 2f, cx + 6f, cy + 14f, iconPaint);

            float lidOffsetY = hovered ? -8f : -3f;
            canvas.drawLine(cx - 22f, cy - 11f + lidOffsetY, cx + 22f, cy - 11f + lidOffsetY, iconPaint);
            canvas.drawLine(cx - 8f, cy - 17f + lidOffsetY, cx + 8f, cy - 17f + lidOffsetY, iconPaint);

            String label = hovered ? "ОТПУСТИТЕ ДЛЯ УДАЛЕНИЯ" : "УДАЛИТЬ КНОПКУ «TP»";
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = cy - (fm.ascent + fm.descent) / 2f;
            textPaint.setColor(hovered ? Color.parseColor("#FFE4E6") : Color.parseColor("#F3E8FF"));
            canvas.drawText(label, cx + 40f, textY, textPaint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredRefreshRate = 120.0f;
            getWindow().setAttributes(lp);
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        cachedScreenHeight = Math.max(dm.widthPixels, dm.heightPixels);

        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        int nativeShort = Math.min(dm.widthPixels, dm.heightPixels);
        int nativeLong = Math.max(dm.widthPixels, dm.heightPixels);
        if ((float) nativeLong / Math.max(1, nativeShort) > 1.85f || !prefs.contains("native_short_px")) {
            prefs.edit()
                    .putInt("native_short_px", nativeShort)
                    .putInt("native_long_px", nativeLong)
                    .apply();
        }

        TeleportEngine.init(this);
        TeleportEngine.requestShizukuPermissionIfNeeded();
        healBrokenTouchTimeouts();

        stealthModeEnabled = prefs.getBoolean("stealth_overlays", false);

        webView = new WebView(this);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);

        // Полная блокировка системного окна «Выбор с помощью ИИ» при долгом нажатии
        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setOnLongClickListener(v -> true);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setBackgroundColor(Color.parseColor("#060709"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.RECORD_AUDIO);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.POST_NOTIFICATIONS");
                }
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), 2002);
            }
        }

        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    startActivityForResult(Intent.createChooser(intent, "Выберите аватарку"), FILE_CHOOSER_RESULT_CODE);
                    return true;
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
            }
        });

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                    results = new Uri[]{data.getClipData().getItemAt(0).getUri()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    public void evalJsFromNotification(final String jsCode) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(jsCode, null);
            }
        });
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
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    public void healBrokenTouchTimeouts() {
        if (!TeleportEngine.isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        String pkg = getPackageName();
        TeleportEngine.execShizukuCommand(
                "cmd appops set " + pkg + " POST_NOTIFICATION allow; " +
                "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS; " +
                "pm grant " + pkg + " android.permission.RECORD_AUDIO; " +
                "settings put secure long_press_timeout 240; " +
                "settings put secure multi_press_timeout 240; " +
                "settings put secure tap_duration_threshold 0.0; " +
                "settings put secure touch_blocking_period 0.0; " +
                "settings put system pointer_speed 7; " +
                "settings put system pointer_location 0; " +
                "settings put system show_touches 0"
        );
    }

    public boolean checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                runOnUiThread(() -> {
                    try {
                        Toast.makeText(this, "Разрешите показ поверх других окон для кнопки «TP»!", Toast.LENGTH_LONG).show();
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
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (nonTouchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (stealthModeEnabled) {
            flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }
        return flags;
    }

    public void updateOverlayStealthFlag(boolean enabled) {
        if (this.stealthModeEnabled == enabled) return;
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
            this.crosshairColor = Color.parseColor("#00E5FF");
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

                        float size = 5f + (crosshairScale / 100f) * 22f;
                        float gap = 2.5f + (crosshairScale / 100f) * 7f;

                        paint.setColor(crosshairColor);
                        paint.setAlpha((int) (255 * (crosshairOpacity / 100f)));
                        paint.setStrokeWidth(2.8f);

                        if ("dot".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.FILL);
                            canvas.drawCircle(cx, cy, Math.max(3.2f, size * 0.28f), paint);
                        } else if ("tshape".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.STROKE);
                            canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                            canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                            canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
                        } else if ("circle".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.STROKE);
                            canvas.drawCircle(cx, cy, size * 0.65f, paint);
                            paint.setStyle(Paint.Style.FILL);
                            canvas.drawCircle(cx, cy, 2.2f, paint);
                        } else {
                            paint.setStyle(Paint.Style.STROKE);
                            canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                            canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                            canvas.drawLine(cx, cy - size, cx, cy - gap, paint);
                            canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
                        }
                    }
                };
                crosshairView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

                int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                crosshairParams = new WindowManager.LayoutParams(
                        96, 96,
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
        DisplayMetrics m = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(m);
        int curScreenH = m.heightPixels;

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
        return rawTouchY >= (curScreenH - 220);
    }

    public void toggleFloatingButton(boolean enable) {
        runOnUiThread(() -> {
            if (!enable) {
                removeFloatingButton();
                return;
            }
            if (!checkAndRequestOverlayPermission()) return;

            if (floatingBtnView != null) {
                updateFloatingBubbleAppearance();
                return;
            }

            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            cachedScreenHeight = dm.heightPixels;

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
            trashParams.y = 24;

            FrameLayout bubbleContainer = new FrameLayout(this);
            bubbleContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            floatingTxtView = new TextView(this);
            updateFloatingBubbleAppearance();

            bubbleContainer.addView(floatingTxtView, new FrameLayout.LayoutParams(128, 128, Gravity.CENTER));
            floatingBtnView = bubbleContainer;

            floatBtnParams = new WindowManager.LayoutParams(
                    142, 142,
                    overlayType,
                    getStealthOverlayFlags(false),
                    PixelFormat.TRANSLUCENT
            );
            floatBtnParams.gravity = Gravity.TOP | Gravity.START;
            floatBtnParams.x = 40;
            floatBtnParams.y = 260;

            final int touchSlopPx = Math.round(12 * getResources().getDisplayMetrics().density);

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
                            v.setScaleX(0.92f);
                            v.setScaleY(0.92f);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) (event.getRawX() - touchX);
                            int dy = (int) (event.getRawY() - touchY);
                            if (Math.abs(dx) > touchSlopPx || Math.abs(dy) > touchSlopPx) {
                                moved = true;
                                if (trashDropView != null && trashDropView.getVisibility() != View.VISIBLE) {
                                    trashDropView.setAlpha(0f);
                                    trashDropView.setVisibility(View.VISIBLE);
                                    trashDropView.animate().alpha(1f).setDuration(140).start();
                                }
                            }
                            if (moved) {
                                floatBtnParams.x = initX + dx;
                                floatBtnParams.y = initY + dy;
                                try {
                                    windowManager.updateViewLayout(floatingBtnView, floatBtnParams);
                                } catch (Exception ignored) {}

                                if (cyberTrashIconView != null) {
                                    cyberTrashIconView.setHovered(isOverTrashBox(event.getRawX(), event.getRawY()));
                                }
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.setScaleX(1.0f);
                            v.setScaleY(1.0f);
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
                                Toast.makeText(MainActivity.this, "🗑️ Кнопка «TP» удалена", Toast.LENGTH_SHORT).show();
                                return true;
                            }
                            if (!moved) {
                                SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
                                String ratio = prefs.getString("stretch_ratio", "4:3");
                                if (!isStretchedActive) {
                                    applyResolutionRatio(ratio);
                                } else {
                                    resetResolution();
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

    public void updateFloatingBubbleAppearance() {
        if (floatingTxtView == null) return;
        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        String ratio = prefs.getString("stretch_ratio", "4:3");

        floatingTxtView.setTextColor(Color.WHITE);
        floatingTxtView.setTextSize(13f);
        floatingTxtView.setGravity(Gravity.CENTER);
        floatingTxtView.setIncludeFontPadding(false);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.RECTANGLE);
        circle.setCornerRadius(38f);
        if (isStretchedActive) {
            floatingTxtView.setText("ON\n" + ratio);
            circle.setColor(Color.parseColor("#E610B981"));
            circle.setStroke(4, Color.parseColor("#FFFFFF"));
        } else {
            floatingTxtView.setText("TP\n" + ratio);
            circle.setColor(Color.parseColor("#D90B0719"));
            circle.setStroke(3, Color.parseColor("#00E5FF"));
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

    public void applyResolutionRatio(String ratio) {
        if (!TeleportEngine.isShizukuReady() && !ShizukuManager.isPermissionGranted()) {
            TeleportEngine.requestShizukuPermissionIfNeeded();
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "⚠️ Разрешите доступ в приложении Shizuku для работы растяга!", Toast.LENGTH_LONG).show()
            );
            return;
        }

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

        int currentRot = windowManager.getDefaultDisplay().getRotation();
        final int lockUserRot = (currentRot == Surface.ROTATION_270) ? 3 : 1;

        isStretchedActive = true;
        runOnUiThread(() -> {
            updateFloatingBubbleAppearance();
            Toast.makeText(MainActivity.this, "🔥 Растяг " + cleanRatio + " ВКЛЮЧЕН!", Toast.LENGTH_SHORT).show();
        });

        String cmd =
                "cmd window set-ignore-orientation-request false; " +
                "cmd window set-fix-to-user-rotation disabled; " +
                "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation " + lockUserRot + "; " +
                "settings put system peak_refresh_rate 120.0; " +
                "settings put system min_refresh_rate 120.0; " +
                "settings put secure refresh_rate_mode 2; " +
                "wm size " + baseShort + "x" + finalLong;
        TeleportEngine.execShizukuCommand(cmd);
    }

    public void resetResolution() {
        isStretchedActive = false;
        runOnUiThread(() -> {
            updateFloatingBubbleAppearance();
            Toast.makeText(MainActivity.this, "Стандартный экран восстановлен", Toast.LENGTH_SHORT).show();
        });

        if (!TeleportEngine.isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        TeleportEngine.execShizukuCommand(
                "wm size reset; " +
                "cmd window set-ignore-orientation-request false; " +
                "cmd window set-fix-to-user-rotation default; " +
                "settings put system accelerometer_rotation 0"
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
        removeCrosshairOverlay();
        removeFloatingButton();
    }
}
