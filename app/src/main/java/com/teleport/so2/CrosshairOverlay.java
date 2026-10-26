package com.teleport.so2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public class CrosshairOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private CrosshairView crosshairView;
    private WindowManager.LayoutParams params;

    private String shape = "off";
    private int scalePercent = 50;
    private int opacityPercent = 100;
    private int colorValue = Color.parseColor("#38BDF8");
    private boolean stealthMode = false;
    private boolean isShowing = false;

    public CrosshairOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setShape(String newShape) {
        this.shape = newShape != null ? newShape : "off";
        if ("off".equalsIgnoreCase(this.shape)) {
            hide();
        } else {
            show();
            refresh();
        }
    }

    public void setScale(int scale) {
        this.scalePercent = Math.max(10, Math.min(100, scale));
        refresh();
    }

    public void setOpacity(int opacity) {
        this.opacityPercent = Math.max(20, Math.min(100, opacity));
        refresh();
    }

    public void setColorHex(String hex) {
        try {
            this.colorValue = Color.parseColor(hex);
            refresh();
        } catch (Exception ignored) {}
    }

    public void setStealthMode(boolean stealth) {
        this.stealthMode = stealth;
        if (isShowing && params != null && crosshairView != null) {
            try {
                if (stealth) {
                    params.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                } else {
                    params.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                }
                windowManager.updateViewLayout(crosshairView, params);
            } catch (Exception ignored) {}
        }
    }

    public void show() {
        if (!Settings.canDrawOverlays(context)) return;
        if (isShowing) {
            refresh();
            return;
        }
        try {
            if (crosshairView == null) {
                crosshairView = new CrosshairView(context);
            }
            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

            if (stealthMode) {
                flags |= WindowManager.LayoutParams.FLAG_SECURE;
            }

            int sizePx = (int) (120 * context.getResources().getDisplayMetrics().density);
            params = new WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    overlayType,
                    flags,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.CENTER;

            windowManager.addView(crosshairView, params);
            isShowing = true;
            crosshairView.invalidate();
        } catch (Exception ignored) {}
    }

    public void hide() {
        if (!isShowing || crosshairView == null) return;
        try {
            windowManager.removeView(crosshairView);
        } catch (Exception ignored) {}
        isShowing = false;
    }

    public void refresh() {
        if (crosshairView != null && isShowing) {
            crosshairView.invalidate();
        }
    }

    private class CrosshairView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeOutline = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CrosshairView(Context ctx) {
            super(ctx);
            strokeOutline.setColor(Color.BLACK);
            strokeOutline.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if ("off".equalsIgnoreCase(shape)) return;

            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float len = (5f + (scalePercent / 100f) * 16f) * density;
            float gap = (2f + (scalePercent / 100f) * 6f) * density;
            float thickness = 2.2f * density;

            int alpha = Math.round((opacityPercent / 100f) * 255f);
            paint.setColor(colorValue);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(thickness);

            strokeOutline.setAlpha(Math.min(200, alpha));
            strokeOutline.setStrokeWidth(thickness + 1.5f * density);

            if ("dot".equalsIgnoreCase(shape)) {
                paint.setStyle(Paint.Style.FILL);
                float r = Math.max(2.5f * density, len * 0.25f);
                canvas.drawCircle(cx, cy, r + 1f, strokeOutline);
                canvas.drawCircle(cx, cy, r, paint);
            } else if ("tshape".equalsIgnoreCase(shape)) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - len, cy, cx - gap, cy, paint);
                canvas.drawLine(cx + gap, cy, cx + len, cy, paint);
                canvas.drawLine(cx, cy + gap, cx, cy + len, paint);
            } else if ("box".equalsIgnoreCase(shape)) {
                paint.setStyle(Paint.Style.STROKE);
                float half = len * 0.7f;
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
            } else {
                // Крест (cross)
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - len, cy, cx - gap, cy, paint);
                canvas.drawLine(cx + gap, cy, cx + len, cy, paint);
                canvas.drawLine(cx, cy - len, cx, cy - gap, paint);
                canvas.drawLine(cx, cy + gap, cx, cy + len, paint);
            }
        }
    }
}
