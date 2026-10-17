package com.teleport.sens;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View floatingButtonView;
    private boolean isStretched = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getExtras() != null) {
            String action = intent.getStringExtra("action");
            if ("START_BUTTON".equals(action)) {
                showFloatingButton();
            } else if ("STOP_BUTTON".equals(action)) {
                removeFloatingButton();
            }
        }
        return START_NOT_STICKY;
    }

    private void showFloatingButton() {
        if (floatingButtonView != null) return;

        Button button = new Button(this);
        button.setText("16:9");
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.parseColor("#990059FF")); // Неоново-синий стиль

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                140, 140,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        // Логика тапа: цикл сжатие/растяжение
        button.setOnClickListener(v -> {
            try {
                if (!isStretched) {
                    // Сжатие экрана для рендера
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size 1080x2400"});
                    isStretched = true;
                    button.setText("ON");
                } else {
                    // Растяжение на весь экран
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size reset"});
                    isStretched = false;
                    button.setText("16:9");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Плавное перетаскивание кнопки по экрану
        button.setOnTouchListener(new View.OnTouchListener() {
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
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingButtonView, params);
                        return true;
                }
                return false;
            }
        });

        floatingButtonView = button;
        windowManager.addView(floatingButtonView, params);
    }

    private void removeFloatingButton() {
        if (floatingButtonView != null) {
            windowManager.removeView(floatingButtonView);
            floatingButtonView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeFloatingButton();
    }
}
