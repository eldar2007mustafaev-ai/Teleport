package com.teleport.so2;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingButtonService extends Service {
    private WindowManager windowManager;
    private TextView floatingBtn;
    private TextView trashView;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams trashParams;
    private boolean isStretchedNow = false;
    private boolean isTrashShown = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (floatingBtn == null) {
            createFloatingButton();
            createTrashView();
        }
        updateStealthFlag();
        return START_STICKY;
    }

    private void createFloatingButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (48 * density);

        floatingBtn = new TextView(this);
        floatingBtn.setText("📐");
        floatingBtn.setTextSize(18);
        floatingBtn.setGravity(Gravity.CENTER);
        floatingBtn.setTextColor(Color.WHITE);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E60B192C"));
        bg.setStroke((int) (2 * density), Color.parseColor("#38BDF8"));
        floatingBtn.setBackground(bg);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                size,
                size,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 260;

        floatingBtn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        showTrash(true);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int newX = initialX + (int) (event.getRawX() - initialTouchX);
                        int newY = initialY + (int) (event.getRawY() - initialTouchY);
                        params.x = newX;
                        params.y = newY;
                        try {
                            windowManager.updateViewLayout(floatingBtn, params);
                        } catch (Exception ignored) {}

                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        if (newY > dm.heightPixels - 300) {
                            if (trashView != null) trashView.setBackgroundColor(Color.parseColor("#88FF3366"));
                        } else {
                            if (trashView != null) trashView.setBackground(getTrashDrawable());
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        showTrash(false);
                        DisplayMetrics dmUp = getResources().getDisplayMetrics();
                        if (params.y > dmUp.heightPixels - 300) {
                            getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("float_btn_enabled", false).apply();
                            stopSelf();
                            return true;
                        }

                        long dx = (long) Math.abs(event.getRawX() - initialTouchX);
                        long dy = (long) Math.abs(event.getRawY() - initialTouchY);
                        if (dx < 12 && dy < 12) {
                            toggleStretchOnTap();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(floatingBtn, params);
        } catch (Exception ignored) {}
    }

    private void createTrashView() {
        float density = getResources().getDisplayMetrics().density;
        trashView = new TextView(this);
        trashView.setText("🗑️ Убрать");
        trashView.setTextSize(14);
        trashView.setGravity(Gravity.CENTER);
        trashView.setTextColor(Color.WHITE);
        trashView.setBackground(getTrashDrawable());

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        trashParams = new WindowManager.LayoutParams(
                (int) (140 * density),
                (int) (48 * density),
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trashParams.y = 80;
    }

    private GradientDrawable getTrashDrawable() {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(24 * density);
        bg.setColor(Color.parseColor("#CC040914"));
        bg.setStroke((int) (1.5 * density), Color.parseColor("#FB7185"));
        return bg;
    }

    private void showTrash(boolean show) {
        if (trashView == null || windowManager == null) return;
        try {
            if (show && !isSharedPreferencesStealth()) {
                if (!isTrashShown) {
                    windowManager.addView(trashView, trashParams);
                    isTrashShown = true;
                }
            } else {
                if (isTrashShown) {
                    windowManager.removeView(trashView);
                    isTrashShown = false;
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isSharedPreferencesStealth() {
        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        return prefs.getBoolean("stealth_overlays", false);
    }

    private void updateStealthFlag() {
        if (floatingBtn == null || params == null) return;
        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        boolean stealth = prefs.getBoolean("stealth_overlays", false);
        try {
            if (stealth) {
                params.flags |= WindowManager.LayoutParams.FLAG_SECURE;
            } else {
                params.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            }
            windowManager.updateViewLayout(floatingBtn, params);
        } catch (Exception ignored) {}
    }

    private void toggleStretchOnTap() {
        if (!ShizukuManager.isPermissionGranted()) {
            Toast.makeText(this, "Включите Shizuku для растяга!", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        String ratio = prefs.getString("stretch_ratio", "4:3");

        isStretchedNow = !isStretchedNow;
        if (isStretchedNow) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int highShortSide = Math.max(Math.min(dm.widthPixels, dm.heightPixels), 1920);
            int targetLongSide = Math.round(highShortSide * 4f / 3f);
            if ("3:2".equals(ratio)) targetLongSide = Math.round(highShortSide * 3f / 2f);
            else if ("16:10".equals(ratio)) targetLongSide = Math.round(highShortSide * 16f / 10f);
            else if ("16:9".equals(ratio)) targetLongSide = Math.round(highShortSide * 16f / 9f);

            final String cmd = "wm size " + highShortSide + "x" + targetLongSide;
            new Thread(() -> ShizukuManager.execCommand(cmd)).start();
            floatingBtn.setText("⚡");
            Toast.makeText(this, "Растяг " + ratio + " + Ультра-сенса", Toast.LENGTH_SHORT).show();
        } else {
            new Thread(() -> ShizukuManager.execCommand("wm size reset")).start();
            floatingBtn.setText("📐");
            Toast.makeText(this, "Обычный экран", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        showTrash(false);
        if (floatingBtn != null && windowManager != null) {
            try {
                windowManager.removeView(floatingBtn);
            } catch (Exception ignored) {}
            floatingBtn = null;
        }
    }
}
