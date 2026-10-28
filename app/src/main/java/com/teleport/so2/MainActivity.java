package com.teleport.so2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
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

public class MainActivity extends Activity {

    private WebView webView;
    private WindowManager windowManager;

    private View crosshairView;
    private WindowManager.LayoutParams crosshairParams;
    private String currentCrosshairType = "off";
    private int crosshairScale = 50;
    private int crosshairOpacity = 100;
    private int crosshairColor = Color.parseColor("#38BDF8");

    // Dynamic Dot Crosshair (Build 401)
    private boolean dynamicCrosshairEnabled = true;
    private float dynamicPulseRadius = 0f;

    private View floatingBtnView;
    private TextView floatingTxtView;
    private View trashDropView;
    private TextView trashBoxView;
    private WindowManager.LayoutParams floatBtnParams;
    private WindowManager.LayoutParams trashParams;

    private boolean isStretchedActive = false;
    private boolean stealthModeEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        healBrokenTouchTimeouts();

        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        stealthModeEnabled = prefs.getBoolean("stealth_overlays", false);
        dynamicCrosshairEnabled = prefs.getBoolean("dynamic_crosshair", true);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setBackgroundColor(Color.parseColor("#040914"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        setContentView(webView);
    }

    public void healBrokenTouchTimeouts() {
        if (!ShizukuManager.isPermissionGranted()) return;
        new Thread(() -> {
            ShizukuManager.execCommand(
                "settings put secure long_press_timeout 500; " +
                "settings put secure multi_press_timeout 300; " +
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

    public void setDynamicCrosshairMode(boolean enabled) {
        this.dynamicCrosshairEnabled = enabled;
        getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("dynamic_crosshair", enabled).apply();
        triggerCrosshairPulse();
    }

    public void triggerCrosshairPulse() {
        runOnUiThread(() -> {
            if (crosshairView != null) {
                dynamicPulseRadius = 12f;
                crosshairView.invalidate();
                crosshairView.postDelayed(() -> {
                    dynamicPulseRadius = 0f;
                    if (crosshairView != null) crosshairView.invalidate();
                }, 180);
            }
        });
    }

    public void updateCrosshairStyle(int scale, int opacity, String colorHex) {
        this.crosshairScale = Math.max(10, Math.min(100, scale));
        this.crosshairOpacity = Math.max(15, Math.min(100, opacity));
        try {
            this.crosshairColor = Color.parseColor(colorHex);
        } catch (Exception e) {
            this.crosshairColor = Color.parseColor("#38BDF8");
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
                    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

                    @Override
                    protected void onDraw(Canvas canvas) {
                        super.onDraw(canvas);
                        int cx = getWidth() / 2;
                        int cy = getHeight() / 2;

                        float size = 8f + (crosshairScale / 100f) * 28f;
                        float gap = 4f + (crosshairScale / 100f) * 10f;

                        // Dynamic Dot Crosshair: меняет оттенок при активном Квантовом Телепорте / AWP-Lock
                        int activeColor = crosshairColor;
                        if (dynamicCrosshairEnabled && TeleportEngine.isActive()) {
                            if (TeleportEngine.isSmartAwpLockEnabled()) {
                                activeColor = Color.parseColor("#F43F5E"); // Неоново-алый для AWP-Lock
                            } else if (TeleportEngine.isAntiRecoilEnabled()) {
                                activeColor = Color.parseColor("#10B981"); // Изумрудный при активном Anti-Recoil
                            } else {
                                activeColor = Color.parseColor("#38BDF8"); // Квантовый голубой импульс
                            }
                        }

                        paint.setColor(activeColor);
                        paint.setAlpha((int) (255 * (crosshairOpacity / 100f)));
                        paint.setStrokeWidth(3.2f);

                        // Отрисовка динамического импульсного кольца
                        if (dynamicCrosshairEnabled && (TeleportEngine.isActive() || dynamicPulseRadius > 0f)) {
                            ringPaint.setStyle(Paint.Style.STROKE);
                            ringPaint.setStrokeWidth(2.0f);
                            ringPaint.setColor(activeColor);
                            ringPaint.setAlpha(130);
                            float r = Math.max(5.5f, size * 0.42f) + dynamicPulseRadius;
                            canvas.drawCircle(cx, cy, r, ringPaint);
                        }

                        if ("dot".equalsIgnoreCase(currentCrosshairType) || "dynamic".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.FILL);
                            float dotRad = Math.max(3.8f, size * 0.28f);
                            canvas.drawCircle(cx, cy, dotRad, paint);
                        } else if ("tshape".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.STROKE);
                            canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                            canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                            canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
                        } else if ("box".equalsIgnoreCase(currentCrosshairType)) {
                            paint.setStyle(Paint.Style.STROKE);
                            float half = size * 0.72f;
                            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
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
            try {
                windowManager.removeView(crosshairView);
            } catch (Exception ignored) {}
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

            if (floatingBtnView != null) {
                int[] btnLoc = new int[2];
                floatingBtnView.getLocationOnScreen(btnLoc);
                float btnCenterX = btnLoc[0] + (floatingBtnView.getWidth() / 2f);
                float btnCenterY = btnLoc[1] + (floatingBtnView.getHeight() / 2f);
                if (btnCenterX >= boxLeft && btnCenterX <= boxRight && btnCenterY >= boxTop && btnCenterY <= boxBottom) {
                    return true;
                }
            }
        }
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        int actualCurrentHeight = dm.heightPixels;
        int minSide = Math.min(dm.widthPixels, dm.heightPixels);
        return rawTouchY >= (actualCurrentHeight - 220) || rawTouchY >= (minSide - 180);
    }

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
            trashBoxView.setText("🗑 Перетащите сюда, чтобы удалить");
            trashBoxView.setTextColor(Color.WHITE);
            trashBoxView.setTextSize(13.5f);
            trashBoxView.setGravity(Gravity.CENTER);
            trashBoxView.setPadding(50, 24, 50, 24);

            GradientDrawable trashBg = new GradientDrawable();
            trashBg.setColor(Color.parseColor("#E60F172A"));
            trashBg.setCornerRadius(999f);
            trashBg.setStroke(4, Color.parseColor("#FB7185"));
            trashBoxView.setBackground(trashBg);

            ((FrameLayout) trashDropView).addView(trashBoxView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            ));
            trashDropView.setVisibility(View.GONE);

            trashParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    220,
                    overlayType,
                    getStealthOverlayFlags(true),
                    PixelFormat.TRANSLUCENT
            );
            trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            trashParams.y = 16;

            FrameLayout bubbleContainer = new FrameLayout(this);
            floatingTxtView = new TextView(this);
            updateFloatingBubbleAppearance();

            bubbleContainer.addView(floatingTxtView, new FrameLayout.LayoutParams(134, 134, Gravity.CENTER));
            floatingBtnView = bubbleContainer;

            floatBtnParams = new WindowManager.LayoutParams(
                    146, 146,
                    overlayType,
                    getStealthOverlayFlags(false),
                    PixelFormat.TRANSLUCENT
            );
            floatBtnParams.gravity = Gravity.TOP | Gravity.START;
            floatBtnParams.x = 40;
            floatBtnParams.y = 260;

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
                            int dx = (int) (event.getRawX() - touchX);
                            int dy = (int) (event.getRawY() - touchY);
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

                            boolean inTrash = isOverTrashBox(event.getRawX(), event.getRawY());
                            if (trashBoxView != null) {
                                GradientDrawable bg = (GradientDrawable) trashBoxView.getBackground();
                                bg.setColor(Color.parseColor(inTrash ? "#FFBE123C" : "#E60F172A"));
                                trashBoxView.setText(inTrash ? "💥 ОТПУСТИТЕ ДЛЯ УДАЛЕНИЯ" : "🗑 Перетащите сюда, чтобы удалить");
                                trashBoxView.setScaleX(inTrash ? 1.10f : 1.0f);
                                trashBoxView.setScaleY(inTrash ? 1.10f : 1.0f);
                            }
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
                                Toast.makeText(MainActivity.this, "Кнопка 📐 удалена", Toast.LENGTH_SHORT).show();
                                return true;
                            }
                            if (!moved) {
                                SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
                                String ratio = prefs.getString("stretch_ratio", "16:9");
                                if (!isStretchedActive) {
                                    applyResolutionRatio(ratio);
                                    triggerCrosshairPulse();
                                    Toast.makeText(MainActivity.this, "Растяг " + ratio + " + Квантовый Телепорт ВКЛ!", Toast.LENGTH_SHORT).show();
                                } else {
                                    resetResolution();
                                    triggerCrosshairPulse();
                                    Toast.makeText(MainActivity.this, "Стандартный экран возвращён", Toast.LENGTH_SHORT).show();
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
        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        String savedRatio = prefs.getString("stretch_ratio", "16:9");

        floatingTxtView.setText(isStretchedActive ? savedRatio : "📐");
        floatingTxtView.setTextColor(Color.WHITE);
        floatingTxtView.setTextSize(isStretchedActive ? 14f : 20f);
        floatingTxtView.setGravity(Gravity.CENTER);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        if (isStretchedActive) {
            circle.setColor(Color.parseColor("#E60284C7"));
            circle.setStroke(4, Color.parseColor("#FFFFFF"));
        } else {
            circle.setColor(Color.parseColor("#CC0B192C"));
            circle.setStroke(3, Color.parseColor("#38BDF8"));
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
        if (!ShizukuManager.isPermissionGranted()) return;

        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        String cleanRatio = (ratio == null || ratio.trim().isEmpty()) ? "16:9" : ratio.trim();
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

        if ("4:3".equals(cleanRatio)) {
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

        isStretchedActive = true;
        runOnUiThread(this::updateFloatingBubbleAppearance);

        int snap = prefs.getInt("tp_snap_power", 500);
        String preset = prefs.getString("selected_preset", "teleport");
        TeleportEngine.setStepQuantization(true, snap / 25.0f);
        TeleportEngine.startTeleport(this, snap, preset);

        new Thread(() -> {
            String cmd =
                "cmd window set-ignore-orientation-request true; " +
                "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation 1; " +
                "settings put global window_animation_scale 0.0; " +
                "settings put global transition_animation_scale 0.0; " +
                "wm size " + baseShort + "x" + finalLong + "; " +
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
                "cmd window set-ignore-orientation-request false; " +
                "settings put system accelerometer_rotation 1"
            );
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeCrosshairOverlay();
        removeFloatingButton();
    }
}
