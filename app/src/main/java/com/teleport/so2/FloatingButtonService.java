package com.teleport.so2;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingButtonService extends Service {

    private static final String PREF_NAME = "so2_teleport_profile_prefs";
    public static final String KEY_FLOAT_ENABLED = "floating_stretch_enabled";
    private static final String KEY_FLOAT_X = "floating_stretch_x";
    private static final String KEY_FLOAT_Y = "floating_stretch_y";

    private WindowManager windowManager;
    private TextView floatingButton;
    private TextView trashBinView;
    private WindowManager.LayoutParams floatingParams;
    private WindowManager.LayoutParams trashParams;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createTrashBin();
        createFloatingButton();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int getOverlayWindowType() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createTrashBin() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        int size = dpToPx(64);
        trashBinView = new TextView(this);
        trashBinView.setText("🗑️");
        trashBinView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        trashBinView.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC1F0812"));
        bg.setStroke(dpToPx(2), Color.parseColor("#FF3366"));
        trashBinView.setBackground(bg);
        trashBinView.setVisibility(View.GONE);

        trashParams = new WindowManager.LayoutParams(
                size, size,
                getOverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trashParams.y = dpToPx(42);

        try {
            windowManager.addView(trashBinView, trashParams);
        } catch (Throwable ignored) {}
    }

    private void createFloatingButton() {
        if (windowManager == null) return;
        int sizePx = dpToPx(52);

        floatingButton = new TextView(this);
        floatingButton.setText("📐");
        floatingButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        floatingButton.setGravity(Gravity.CENTER);
        floatingButton.setTextColor(Color.WHITE);
        updateButtonStyle(ShizukuManager.isStretchActive());

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int savedX = prefs.getInt(KEY_FLOAT_X, 60);
        int savedY = prefs.getInt(KEY_FLOAT_Y, 260);

        floatingParams = new WindowManager.LayoutParams(
                sizePx, sizePx,
                getOverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = savedX;
        floatingParams.y = savedY;

        setupTouchHandling();

        try {
            windowManager.addView(floatingButton, floatingParams);
            prefs.edit().putBoolean(KEY_FLOAT_ENABLED, true).apply();
        } catch (Throwable ignored) {}
    }

    private void updateButtonStyle(boolean active) {
        if (floatingButton == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (active) {
            bg.setColor(Color.parseColor("#CC0A2E26"));
            bg.setStroke(dpToPx(2), Color.parseColor("#38F9D7"));
        } else {
            bg.setColor(Color.parseColor("#CC12172A"));
            bg.setStroke(dpToPx(2), Color.parseColor("#4FACFE"));
        }
        floatingButton.setBackground(bg);
    }

    private void setupTouchHandling() {
        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = floatingParams.x;
                        startY = floatingParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        dragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        if (Math.hypot(dx, dy) > dpToPx(8)) {
                            if (!dragging && trashBinView != null) {
                                trashBinView.setVisibility(View.VISIBLE);
                            }
                            dragging = true;
                            floatingParams.x = startX + dx;
                            floatingParams.y = startY + dy;
                            try {
                                windowManager.updateViewLayout(floatingButton, floatingParams);
                            } catch (Throwable ignored) {}

                            boolean insideTrash = isInsideBottomTrashZone(event.getRawY());
                            if (trashBinView != null) {
                                trashBinView.setScaleX(insideTrash ? 1.25f : 1.0f);
                                trashBinView.setScaleY(insideTrash ? 1.25f : 1.0f);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (trashBinView != null) {
                            trashBinView.setVisibility(View.GONE);
                        }
                        if (dragging) {
                            if (isInsideBottomTrashZone(event.getRawY())) {
                                getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                        .edit().putBoolean(KEY_FLOAT_ENABLED, false).apply();
                                stopSelf();
                                Toast.makeText(getApplicationContext(), "Плавающая кнопка скрыта", Toast.LENGTH_SHORT).show();
                            } else {
                                getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                        .edit()
                                        .putInt(KEY_FLOAT_X, floatingParams.x)
                                        .putInt(KEY_FLOAT_Y, floatingParams.y)
                                        .apply();
                            }
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            onFloatingButtonTap();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private boolean isInsideBottomTrashZone(float rawY) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;
        return rawY >= (screenH - dpToPx(135));
    }

    private void onFloatingButtonTap() {
        new Thread(() -> {
            if (!ShizukuManager.isStandoffForeground()) return;
            final boolean nextState = !ShizukuManager.isStretchActive();
            final String msg = nextState ? ShizukuManager.applyStretch() : ShizukuManager.resetStretch();
            mainHandler.post(() -> {
                updateButtonStyle(nextState);
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (windowManager != null) {
            if (floatingButton != null) {
                try { windowManager.removeView(floatingButton); } catch (Throwable ignored) {}
                floatingButton = null;
            }
            if (trashBinView != null) {
                try { windowManager.removeView(trashBinView); } catch (Throwable ignored) {}
                trashBinView = null;
            }
        }
    }
}
