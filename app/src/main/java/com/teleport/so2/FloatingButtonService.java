package com.teleport.so2;

import android.app.AlertDialog;
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
    private static final long LONG_PRESS_TIMEOUT_MS = 500L;

    private WindowManager windowManager;
    private TextView floatingButton;
    private WindowManager.LayoutParams layoutParams;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isLongPressTriggered = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createFloatingButton();
    }

    private void createFloatingButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        int sizePx = dpToPx(50);

        floatingButton = new TextView(this);
        floatingButton.setText("📐");
        floatingButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        floatingButton.setGravity(Gravity.CENTER);
        floatingButton.setTextColor(Color.WHITE);
        updateButtonAppearance(ShizukuManager.isStretchActive());

        int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int savedX = prefs.getInt(KEY_FLOAT_X, 60);
        int savedY = prefs.getInt(KEY_FLOAT_Y, 300);

        layoutParams = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = savedX;
        layoutParams.y = savedY;

        setupTouchListener();

        try {
            windowManager.addView(floatingButton, layoutParams);
            prefs.edit().putBoolean(KEY_FLOAT_ENABLED, true).apply();
        } catch (Throwable ignored) {}
    }

    private void updateButtonAppearance(boolean active) {
        if (floatingButton == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (active) {
            // Активный растяг: полупрозрачный тёмно-зелёный неон
            bg.setColor(Color.parseColor("#BF063624"));
            bg.setStroke(dpToPx(2), Color.parseColor("#00FF88"));
        } else {
            // Обычное состояние: полупрозрачный тёмно-синий неон
            bg.setColor(Color.parseColor("#BF0E152B"));
            bg.setStroke(dpToPx(2), Color.parseColor("#00D4FF"));
        }
        floatingButton.setBackground(bg);
    }

    private void setupTouchListener() {
        final Runnable longPressRunnable = () -> {
            isLongPressTriggered = true;
            showHideConfirmDialog();
        };

        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        isLongPressTriggered = false;
                        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.hypot(dx, dy) > dpToPx(8)) {
                            moved = true;
                            mainHandler.removeCallbacks(longPressRunnable);
                            layoutParams.x = initialX + dx;
                            layoutParams.y = initialY + dy;
                            if (windowManager != null && floatingButton != null) {
                                try {
                                    windowManager.updateViewLayout(floatingButton, layoutParams);
                                } catch (Throwable ignored) {}
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(longPressRunnable);
                        if (moved) {
                            saveButtonPosition(layoutParams.x, layoutParams.y);
                        } else if (!isLongPressTriggered && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            handleFloatingButtonClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * Обработка нажатия на плавающую кнопку:
     * Срабатывает ТОЛЬКО когда активно приложение com.axlebolt.standoff2.
     */
    private void handleFloatingButtonClick() {
        new Thread(() -> {
            if (!ShizukuManager.isStandoffForeground()) {
                // В других приложениях кнопка видна, но не применяет растяг
                return;
            }
            final String msg;
            final boolean nowActive;
            if (!ShizukuManager.isStretchActive()) {
                msg = ShizukuManager.applyStretch();
                nowActive = true;
            } else {
                msg = ShizukuManager.resetStretch();
                nowActive = false;
            }
            mainHandler.post(() -> {
                updateButtonAppearance(nowActive);
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /**
     * Меню долгого нажатия (500 мс): "Скрыть плавающую кнопку?" (Да / Нет).
     */
    private void showHideConfirmDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(
                    this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert
            );
            builder.setTitle("Плавающая кнопка");
            builder.setMessage("Скрыть плавающую кнопку?");
            builder.setPositiveButton("Да", (dialog, which) -> {
                SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_FLOAT_ENABLED, false).apply();
                stopSelf();
            });
            builder.setNegativeButton("Нет", (dialog, which) -> dialog.dismiss());

            AlertDialog dialog = builder.create();
            if (dialog.getWindow() != null) {
                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                dialog.getWindow().setType(overlayType);
            }
            dialog.show();
        } catch (Throwable ignored) {}
    }

    private void saveButtonPosition(int x, int y) {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_FLOAT_X, x)
                .putInt(KEY_FLOAT_Y, y)
                .apply();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (windowManager != null && floatingButton != null) {
            try {
                windowManager.removeView(floatingButton);
            } catch (Throwable ignored) {}
            floatingButton = null;
        }
    }
}
