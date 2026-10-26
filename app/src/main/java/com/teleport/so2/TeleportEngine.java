package com.teleport.so2;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public class TeleportEngine {
    private static boolean active = false;
    private static boolean stepQuantizationEnabled = true;
    private static float snapPowerMultiplier = 6.0f;
    private static String currentPreset = "teleport";

    // Параметры продвинутой сенсы (из меню настройки)
    private static float sensX = 1.0f;
    private static float sensY = 1.0f;
    private static int noiseCutoff = 3;
    private static int deadZone = 1;
    private static int stickiness = 4;
    private static float smoothing = 0.80f;
    private static float acceleration = 0.0f;

    private static WindowManager windowManager;
    private static View quantumPulseView;
    private static WindowManager.LayoutParams pulseParams;
    private static final Handler pulseHandler = new Handler(Looper.getMainLooper());
    private static boolean pulseToggle = false;

    private static final Runnable quantumRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || quantumPulseView == null || windowManager == null) return;
            try {
                pulseToggle = !pulseToggle;
                pulseParams.x = pulseToggle ? 1 : 0;
                windowManager.updateViewLayout(quantumPulseView, pulseParams);
            } catch (Exception ignored) {}

            long intervalMs = Math.max(28L, Math.min(55L, (long) (160f / Math.max(1f, snapPowerMultiplier))));
            pulseHandler.postDelayed(this, intervalMs);
        }
    };

    public static void initOverlayPulse(Context context) {
        if (windowManager != null) return;
        try {
            Context appCtx = context.getApplicationContext();
            windowManager = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            quantumPulseView = new View(appCtx);

            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            pulseParams = new WindowManager.LayoutParams(
                    1, 1, overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            pulseParams.gravity = Gravity.TOP | Gravity.START;
            pulseParams.x = 0;
            pulseParams.y = 0;
        } catch (Exception ignored) {}
    }

    private static void startQuantumPulse(Context context) {
        if (context != null) initOverlayPulse(context);
        pulseHandler.post(() -> {
            try {
                if (context != null && Settings.canDrawOverlays(context) && quantumPulseView != null && windowManager != null) {
                    if (quantumPulseView.getParent() == null) {
                        windowManager.addView(quantumPulseView, pulseParams);
                    }
                    pulseHandler.removeCallbacks(quantumRunnable);
                    pulseHandler.post(quantumRunnable);
                }
            } catch (Exception ignored) {}
        });
    }

    private static void stopQuantumPulse() {
        pulseHandler.post(() -> {
            pulseHandler.removeCallbacks(quantumRunnable);
            try {
                if (quantumPulseView != null && quantumPulseView.getParent() != null && windowManager != null) {
                    windowManager.removeView(quantumPulseView);
                }
            } catch (Exception ignored) {}
        });
    }

    public static void setAdvancedSensParams(float sx, float sy, int noise, int dz, int stick, float smooth, float accel) {
        sensX = sx;
        sensY = sy;
        noiseCutoff = noise;
        deadZone = dz;
        stickiness = stick;
        smoothing = smooth;
        acceleration = accel;
    }

    public static void setStepQuantization(boolean enabled, float multiplier) {
        stepQuantizationEnabled = enabled;
        snapPowerMultiplier = Math.max(1.0f, multiplier);
    }

    public static boolean isActive() {
        return active;
    }

    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (abs < (deadZone * 0.001f + 0.001f)) return 0f;
        if (abs < (noiseCutoff * 0.002f)) return 0f;

        float effectiveMultiplier = snapPowerMultiplier * ((rawDelta > 0 ? sensX : sensY));
        float instantSnapStep = 45.0f * effectiveMultiplier;
        float finalStep = Math.max(abs * (12.0f + acceleration * 5f) * effectiveMultiplier, instantSnapStep);
        
        // Применение выпрямления и сглаживания
        finalStep = finalStep * (1.0f - (smoothing * 0.15f));
        return Math.copySign(finalStep, rawDelta);
    }

    public static void startTeleport(int snapPercent, String presetId) {
        startTeleport(null, snapPercent, presetId);
    }

    public static void startTeleport(Context context, int snapPercent, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "teleport";
        snapPowerMultiplier = Math.max(3.0f, snapPercent / 45.0f);

        startQuantumPulse(context);

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                // Безопасные тайминги (гарантированно убирают залипание нижней полоски и Google Assistant)
                "settings put secure long_press_timeout 500; " +
                "settings put secure multi_press_timeout 300; " +
                "settings delete secure tap_duration_threshold; " +
                "settings delete secure touch_blocking_period; " +
                "settings put system pointer_speed 7; " +
                "cmd game mode performance com.axlebolt.standoff2; " +
                "cmd game downscale disable com.axlebolt.standoff2; " +
                "setprop ro.input.resampling 0; " +
                "setprop debug.input.resampling 0; " +
                "setprop windowsmgr.max_events_per_sec 12; " +
                "setprop ro.min_pointer_dur 60; " +
                "setprop touch.pressure.scale 0.0001; " +
                "setprop view.scroll_friction 0.0; " +
                "setprop debug.sf.latch_unsignaled 1; " +
                "setprop debug.sf.disable_backpressure 1; " +
                "setprop debug.hwui.use_buffer_age false";

            ShizukuManager.execCommand(cmd);
        }).start();
    }

    public static void stopTeleport() {
        active = false;
        stopQuantumPulse();
        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                "settings put secure long_press_timeout 500; " +
                "settings put secure multi_press_timeout 300; " +
                "settings delete secure tap_duration_threshold; " +
                "settings delete secure touch_blocking_period; " +
                "settings put system pointer_speed 0; " +
                "setprop ro.input.resampling 1; " +
                "setprop debug.input.resampling 1; " +
                "setprop windowsmgr.max_events_per_sec 240; " +
                "setprop ro.min_pointer_dur 1; " +
                "setprop debug.sf.latch_unsignaled 0; " +
                "setprop debug.sf.disable_backpressure 0";
            ShizukuManager.execCommand(cmd);
        }).start();
    }
}
