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
    private WindowManager.LayoutParams params;
    private boolean isStretchedNow = false;

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
        }
        updateStealthFlag();
        return START_STICKY;
    }

    private void createFloatingButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (50 * density);

        floatingBtn = new TextView(this);
        floatingBtn.setText("📐");
        floatingBtn.setTextSize(19);
        floatingBtn.setGravity(Gravity.CENTER);
        floatingBtn.setTextColor(Color.WHITE);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC0B192C"));
        bg.setStroke((int) (2 * density), Color.parseColor("#38BDF8"));
        floatingBtn.setBackground(bg);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                size,
                size,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 60;
        params.y = 240;

        floatingBtn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            windowManager.updateViewLayout(floatingBtn, params);
                        } catch (Exception ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                        long dx = (long) Math.abs(event.getRawX() - initialTouchX);
                        long dy = (long) Math.abs(event.getRawY() - initialTouchY);
                        if (dx < 15 && dy < 15 && (System.currentTimeMillis() - touchStartTime) < 300) {
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
            Toast.makeText(this, "Сначала включите Shizuku!", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        String ratio = prefs.getString("stretch_ratio", "4:3");

        isStretchedNow = !isStretchedNow;
        if (isStretchedNow) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
            int targetLongSide = Math.round(shortSide * 4f / 3f);
            if ("3:2".equals(ratio)) targetLongSide = Math.round(shortSide * 3f / 2f);
            else if ("16:10".equals(ratio)) targetLongSide = Math.round(shortSide * 16f / 10f);
            else if ("16:9".equals(ratio)) targetLongSide = Math.round(shortSide * 16f / 9f);

            final String cmd = "wm size " + shortSide + "x" + targetLongSide;
            new Thread(() -> ShizukuManager.execCommand(cmd)).start();
            floatingBtn.setText("⚡");
            Toast.makeText(this, "Растяг " + ratio + " включен", Toast.LENGTH_SHORT).show();
        } else {
            new Thread(() -> ShizukuManager.execCommand("wm size reset")).start();
            floatingBtn.setText("📐");
            Toast.makeText(this, "Обычный экран возвращен", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingBtn != null && windowManager != null) {
            try {
                windowManager.removeView(floatingBtn);
            } catch (Exception ignored) {}
            floatingBtn = null;
        }
    }
}
