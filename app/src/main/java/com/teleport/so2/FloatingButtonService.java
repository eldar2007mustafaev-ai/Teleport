package com.teleport.so2;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
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

    private int screenWidthPx = 1080;
    private int screenHeightPx = 2400;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        updateRealScreenMetrics();
        createTrashBinZone();
        createFloatingButton();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRealScreenMetrics();
        // Держим кнопку в пределах экрана при повороте в альбомный режим или растяге
        if (windowManager != null && floatingButton != null && floatingParams != null) {
            floatingParams.x = Math.max(0, Math.min(floatingParams.x, screenWidthPx - dpToPx(55)));
            floatingParams.y = Math.max(0, Math.min(floatingParams.y, screenHeightPx - dpToPx(55)));
            try {
                windowManager.updateViewLayout(floatingButton, floatingParams);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Динамически получает актуальные размеры экрана (работает в портретном, альбомном и растянутом режиме).
     */
    private void updateRealScreenMetrics() {
        try {
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                Display d = windowManager.getDefaultDisplay();
                DisplayMetrics dm = new DisplayMetrics();
                d.getRealMetrics(dm);
                screenWidthPx = dm.widthPixels;
                screenHeightPx = dm.heightPixels;
                return;
            }
        } catch (Throwable ignored) {}
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidthPx = dm.widthPixels;
        screenHeightPx = dm.heightPixels;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int getOverlayType() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * Создаёт широкую и удобную нижнюю зону-корзину, которая корректно ловит кнопку даже после растяга экрана.
     */
    private void createTrashBinZone() {
        if (windowManager == null) return;

        trashBinView = new TextView(this);
        trashBinView.setText("🗑️ Отпустите здесь, чтобы скрыть");
        trashBinView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        trashBinView.setTextColor(Color.WHITE);
        trashBinView.setGravity(Gravity.CENTER);
        trashBinView.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));

        styleTrashZone(false);
        trashBinView.setVisibility(View.GONE);

        trashParams = new WindowManager.LayoutParams(
                dpToPx(260),
                dpToPx(58),
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trashParams.y = dpToPx(28);

        try {
            windowManager.addView(trashBinView, trashParams);
        } catch (Throwable ignored) {}
    }

    private void styleTrashZone(boolean highlighted) {
        if (trashBinView == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(30));
        if (highlighted) {
            bg.setColor(Color.parseColor("#E6FF1E56"));
            bg.setStroke(dpToPx(2), Color.WHITE);
            trashBinView.setScaleX(1.08f);
            trashBinView.setScaleY(1.08f);
        } else {
            bg.setColor(Color.parseColor("#CC1A0812"));
            bg.setStroke(dpToPx(2), Color.parseColor("#FF2E93"));
            trashBinView.setScaleX(1.0f);
            trashBinView.setScaleY(1.0f);
        }
        trashBinView.setBackground(bg);
    }

    private void createFloatingButton() {
        if (windowManager == null) return;
        int sizePx = dpToPx(52);

        floatingButton = new TextView(this);
        floatingButton.setText("📐");
        floatingButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        floatingButton.setGravity(Gravity.CENTER);
        floatingButton.setTextColor(Color.WHITE);
        updateButtonAppearance(ShizukuManager.isStretchActive());

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int savedX = prefs.getInt(KEY_FLOAT_X, dpToPx(24));
        int savedY = prefs.getInt(KEY_FLOAT_Y, dpToPx(140));

        floatingParams = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = savedX;
        floatingParams.y = savedY;

        setupTouchListener();

        try {
            windowManager.addView(floatingButton, floatingParams);
            prefs.edit().putBoolean(KEY_FLOAT_ENABLED, true).apply();
        } catch (Throwable ignored) {}
    }

    private void updateButtonAppearance(boolean active) {
        if (floatingButton == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (active) {
            bg.setColor(Color.parseColor("#D9063624"));
            bg.setStroke(dpToPx(2), Color.parseColor("#00FF88"));
        } else {
            bg.setColor(Color.parseColor("#D90E152B"));
            bg.setStroke(dpToPx(2), Color.parseColor("#00D4FF"));
        }
        floatingButton.setBackground(bg);
    }

    private void setupTouchListener() {
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
                        updateRealScreenMetrics();
                        initialX = floatingParams.x;
                        initialY = floatingParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.hypot(dx, dy) > dpToPx(6)) {
                            if (!moved && trashBinView != null) {
                                trashBinView.setVisibility(View.VISIBLE);
                            }
                            moved = true;
                            floatingParams.x = Math.max(0, Math.min(initialX + dx, screenWidthPx - dpToPx(50)));
                            floatingParams.y = Math.max(0, Math.min(initialY + dy, screenHeightPx - dpToPx(50)));
                            if (windowManager != null && floatingButton != null) {
                                try {
                                    windowManager.updateViewLayout(floatingButton, floatingParams);
                                } catch (Throwable ignored) {}
                            }

                            boolean insideTrash = isOverTrashZone(event.getRawX(), event.getRawY());
                            styleTrashZone(insideTrash);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        boolean shouldDelete = moved && isOverTrashZone(event.getRawX(), event.getRawY());
                        if (trashBinView != null) {
                            trashBinView.setVisibility(View.GONE);
                        }
                        if (shouldDelete) {
                            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                            prefs.edit().putBoolean(KEY_FLOAT_ENABLED, false).apply();
                            Toast.makeText(getApplicationContext(), "Плавающая кнопка скрыта", Toast.LENGTH_SHORT).show();
                            stopSelf();
                        } else if (moved) {
                            saveButtonPosition(floatingParams.x, floatingParams.y);
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            handleFloatingButtonClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * Надёжная проверка попадания в корзину:
     * 1) Сравнивает реальные экранные координаты кнопки и корзины через getLocationOnScreen() (не сбивается от wm size).
     * 2) Дополнительно срабатывает, если кнопку опустили в нижние 20% экрана по центру.
     */
    private boolean isOverTrashZone(float rawX, float rawY) {
        try {
            if (floatingButton != null && trashBinView != null && trashBinView.getVisibility() == View.VISIBLE) {
                int[] btnLoc = new int[2];
                int[] trashLoc = new int[2];
                floatingButton.getLocationOnScreen(btnLoc);
                trashBinView.getLocationOnScreen(trashLoc);

                float btnCenterX = btnLoc[0] + (floatingButton.getWidth() / 2f);
                float btnCenterY = btnLoc[1] + (floatingButton.getHeight() / 2f);

                float trashLeft = trashLoc[0] - dpToPx(25);
                float trashRight = trashLoc[0] + trashBinView.getWidth() + dpToPx(25);
                float trashTop = trashLoc[1] - dpToPx(35);
                float trashBottom = trashLoc[1] + trashBinView.getHeight() + dpToPx(35);

                if (btnCenterX >= trashLeft && btnCenterX <= trashRight
                        && btnCenterY >= trashTop && btnCenterY <= trashBottom) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // Резервная проверка по нижней границе экрана (если изменилось виртуальное разрешение wm size)
        boolean nearBottom = (floatingParams.y >= screenHeightPx * 0.72f) || (rawY >= screenHeightPx * 0.78f);
        boolean nearCenterHoriz = Math.abs(floatingParams.x - (screenWidthPx / 2f)) < (screenWidthPx * 0.32f);
        return nearBottom && nearCenterHoriz;
    }

    private void handleFloatingButtonClick() {
        new Thread(() -> {
            if (!ShizukuManager.isStandoffForeground()) {
                mainHandler.post(() ->
                        Toast.makeText(getApplicationContext(), "Откройте Standoff 2 для применения растяга", Toast.LENGTH_SHORT).show()
                );
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

    private void saveButtonPosition(int x, int y) {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_FLOAT_X, x)
                .putInt(KEY_FLOAT_Y, y)
                .apply();
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
