package com.teleport.so2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public class CrosshairOverlay {

    private static View overlayView = null;
    private static String currentShape = "cross";

    public static void show(Context context, String shape) {
        currentShape = shape != null ? shape : "cross";
        WindowManager wm = (WindowManager) context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        if (overlayView != null) {
            overlayView.invalidate();
            return;
        }

        overlayView = new View(context.getApplicationContext()) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;

                paint.setColor(Color.parseColor("#00FF88"));
                paint.setStrokeWidth(5f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setShadowLayer(10f, 0f, 0f, Color.parseColor("#00D4FF"));

                switch (currentShape) {
                    case "dot":
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy, 7f, paint);
                        break;
                    case "tshape":
                        canvas.drawLine(cx - 24f, cy, cx - 6f, cy, paint);
                        canvas.drawLine(cx + 6f, cy, cx + 24f, cy, paint);
                        canvas.drawLine(cx, cy + 6f, cx, cy + 24f, paint);
                        break;
                    case "corners":
                        float s = 20f;
                        float l = 9f;
                        canvas.drawLine(cx - s, cy - s, cx - s + l, cy - s, paint);
                        canvas.drawLine(cx - s, cy - s, cx - s, cy - s + l, paint);
                        canvas.drawLine(cx + s, cy - s, cx + s - l, cy - s, paint);
                        canvas.drawLine(cx + s, cy - s, cx + s, cy - s + l, paint);
                        canvas.drawLine(cx - s, cy + s, cx - s + l, cy + s, paint);
                        canvas.drawLine(cx - s, cy + s, cx - s, cy + s - l, paint);
                        canvas.drawLine(cx + s, cy + s, cx + s - l, cy + s, paint);
                        canvas.drawLine(cx + s, cy + s, cx + s, cy + s - l, paint);
                        break;
                    case "cross":
                    default:
                        canvas.drawLine(cx - 24f, cy, cx - 6f, cy, paint);
                        canvas.drawLine(cx + 6f, cy, cx + 24f, cy, paint);
                        canvas.drawLine(cx, cy - 24f, cx, cy - 6f, paint);
                        canvas.drawLine(cx, cy + 6f, cx, cy + 24f, paint);
                        break;
                }
            }
        };

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                140,
                140,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;

        try {
            wm.addView(overlayView, params);
        } catch (Throwable ignored) {
            overlayView = null;
        }
    }

    public static void hide(Context context) {
        if (overlayView == null) return;
        WindowManager wm = (WindowManager) context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            try {
                wm.removeView(overlayView);
            } catch (Throwable ignored) {}
        }
        overlayView = null;
    }
}
