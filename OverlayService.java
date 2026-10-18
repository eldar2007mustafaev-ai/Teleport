package com.teleport.sens;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class OverlayService extends Service {

    public static final String EXTRA_ACTION = "action";
    public static final String ACTION_SHOW  = "show";
    public static final String ACTION_HIDE  = "hide";

    private static final String CHANNEL_ID = "overlay_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int LONG_PRESS_MS = 650;

    private WindowManager windowManager;
    private View floatingButtonView;
    private WindowManager.LayoutParams params;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;

    private int screenState = 0;
    private String physicalSize = null;

    private float downX, downY, startX, startY;
    private boolean moved = false;
    private int touchSlop;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        detectPhysicalSize();
        startForegroundWithNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getStringExtra(EXTRA_ACTION);
            if (ACTION_HIDE.equals(action)) {
                removeFloatingButton();
            } else {
                showFloatingButton();
            }
        } else {
            showFloatingButton();
        }
        return START_STICKY;
    }

    private void showFloatingButton() {
        if (floatingButtonView != null) return;

        int size = dp(56);
        ImageView btn = new ImageView(this);
        btn.setImageDrawable(new BitmapDrawable(getResources(), createIconBitmap(size)));
        btn.setAlpha(0.92f);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                size, size,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(24);
        params.y = dp(220);

        btn.setOnTouchListener((v, event) -> onTouch(event));

        floatingButtonView = btn;
        windowManager.addView(floatingButtonView, params);
        toast("Кнопка активна");
    }

    private void removeFloatingButton() {
        resetScreen();
        if (floatingButtonView != null) {
            try { windowManager.removeView(floatingButtonView); } catch (Exception ignored) {}
            floatingButtonView = null;
        }
        stopSelf();
    }

    private boolean onTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                moved = false;
                longPressRunnable = this::onLongPress;
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    moved = true;
                    if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                    // ИСПРАВЛЕНО: явное приведение к int
                    params.x = (int)(startX + Math.round(dx));
                    params.y = (int)(startY + Math.round(dy));
                    windowManager.updateViewLayout(floatingButtonView, params);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                if (!moved) onTap();
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                return true;
        }
        return false;
    }

    private void onTap() {
        if (physicalSize == null) detectPhysicalSize();

        if (screenState == 0) {
            try {
                String[] wh = physicalSize.split("x");
                int w = Integer.parseInt(wh[0].trim());
                int h = Math.round(w * 16f / 9f);
                runCommand("wm size " + w + "x" + h);
                screenState = 1;
                toast("16:9 — полосы включены");
            } catch (Exception e) {
                toast("Не удалось определить разрешение");
            }
        } else if (screenState == 1) {
            runCommand("wm size reset");
            screenState = 2;
            toast("Растяжение активно");
        } else {
            try {
                String[] wh = physicalSize.split("x");
                int w = Integer.parseInt(wh[0].trim());
                int h = Math.round(w * 16f / 9f);
                runCommand("wm size " + w + "x" + h);
                screenState = 1;
                toast("16:9 — полосы включены");
            } catch (Exception e) {
                toast("Не удалось определить разрешение");
            }
        }
    }

    private void onLongPress() {
        removeFloatingButton();
        toast("Кнопка удалена");
    }

    private void resetScreen() {
        if (screenState != 0) {
            runCommand("wm size reset");
            screenState = 0;
        }
    }

    private void runCommand(final String cmd) {
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", cmd)
                        .redirectErrorStream(true)
                        .start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
                int exitCode = p.waitFor();
                
                if (exitCode != 0) {
                    p = new ProcessBuilder("sh", "-c", cmd)
                            .redirectErrorStream(true)
                            .start();
                    p.waitFor();
                }
            } catch (Exception e) {
                handler.post(() -> toast("Ошибка: " + cmd));
                e.printStackTrace();
            }
        }).start();
    }

    private void detectPhysicalSize() {
        try {
            Process p = new ProcessBuilder("su", "-c", "wm size").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("Physical size:");
                if (i >= 0) {
                    physicalSize = line.substring(i + "Physical size:".length()).trim();
                    break;
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}

        if (physicalSize == null) {
            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            physicalSize = dm.widthPixels + "x" + dm.heightPixels;
        }
    }

    private Bitmap createIconBitmap(int size) {
        float d = getResources().getDisplayMetrics().density;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, size, size,
                Color.parseColor("#12304a"), Color.parseColor("#070913"),
                Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), p);

        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setColor(Color.parseColor("#38bdf8"));
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#7dd3fc"));
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        p.setTextSize(13f * d);
        float y = size / 2f - (p.descent() + p.ascent()) / 2f;
        c.drawText("16:9", size / 2f, y, p);

        return bmp;
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("Teleport")
         .setContentText("Плавающая кнопка активна")
         .setSmallIcon(android.R.drawable.ic_menu_crop)
         .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startForeground(NOTIFICATION_ID, b.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, b.build());
        }
    }

    private void toast(final String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (floatingButtonView != null) {
            try { windowManager.removeView(floatingButtonView); } catch (Exception ignored) {}
            floatingButtonView = null;
        }
        super.onDestroy();
    }
}
