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

    private static WindowManager windowManager;
    private static View quantumPulseView;
    private static WindowManager.LayoutParams pulseParams;
    private static final Handler pulseHandler = new Handler(Looper.getMainLooper());
    private static boolean pulseToggle = false;

    // Квантовый импульс: заставляет InputDispatcher отдавать движение пальца
    // резкими дискретными скачками (эффект телепорта прицела), не прерывая зажатие стрельбы
    private static final Runnable quantumRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || quantumPulseView == null || windowManager == null) return;
            try {
                pulseToggle = !pulseToggle;
                pulseParams.x = pulseToggle ? 1 : 0;
                windowManager.updateViewLayout(quantumPulseView, pulseParams);
            } catch (Exception ignored) {}

            // Чем выше мощность УЛЬТРА, тем резче шаг квантования (35-55 мс)
            long intervalMs = Math.max(32L, Math.min(60L, (long) (180f / Math.max(1f, snapPowerMultiplier))));
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
                    1,
                    1,
                    overlayType,
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

    public static void setStepQuantization(boolean enabled, float multiplier) {
        stepQuantizationEnabled = enabled;
        snapPowerMultiplier = Math.max(1.0f, multiplier);
    }

    public static boolean isActive() {
        return active;
    }

    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (abs < 0.001f) return 0f;
        float instantSnapStep = 50.0f * snapPowerMultiplier;
        float finalStep = Math.max(abs * 18.0f * snapPowerMultiplier, instantSnapStep);
        return Math.copySign(finalStep, rawDelta);
    }

    public static void startTeleport(int snapPercent, String presetId) {
        startTeleport(null, snapPercent, presetId);
    }

    public static void startTeleport(Context context, int snapPercent, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "teleport";
        snapPowerMultiplier = Math.max(2.5f, snapPercent / 55.0f);

        if ("teleport".equals(currentPreset) || "ultra".equals(currentPreset)) {
            startQuantumPulse(context);
        } else {
            stopQuantumPulse();
        }

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            // Для режима УЛЬТРА ставим квантование пакетов (14-18 Гц без интерполяции),
            // чтобы прицел телепортировался рывками, а не плыл плавно
            int maxEvents = 14;
            int minDur = 55;

            if ("smooth".equals(currentPreset)) {
                maxEvents = 180;
                minDur = 5;
            } else if ("sensa".equals(currentPreset)) {
                maxEvents = 45;
                minDur = 25;
            } else if ("sharp".equals(currentPreset)) {
                maxEvents = 24;
                minDur = 40;
            } else {
                // Режим УЛЬТРА / МАКСИМУМ — жёсткий телепорт без сглаживания
                maxEvents = Math.max(10, 26 - (snapPercent / 25));
                minDur = Math.min(75, 25 + (snapPercent / 10));
            }

            String cmd =
                // 1. Восстанавливаем нормальные системные таймауты, чтобы НЕ залипала нижняя полоска и НЕ вызывался Google:
                "settings put secure long_press_timeout 500; " +
                "settings put secure multi_press_timeout 300; " +
                "settings delete secure tap_duration_threshold; " +
                "settings delete secure touch_blocking_period; " +
                // 2. Максимальная скорость указателя и отключение даунскейла (чтобы сенса была быстрой):
                "settings put system pointer_speed 7; " +
                "cmd game mode performance com.axlebolt.standoff2; " +
                "cmd game downscale disable com.axlebolt.standoff2; " +
                // 3. Отключаем плавное сглаживание (resampling) и включаем квантовый рывок (телепорт):
                "setprop ro.input.resampling 0; " +
                "setprop debug.input.resampling 0; " +
                "setprop windowsmgr.max_events_per_sec " + maxEvents + "; " +
                "setprop ro.min_pointer_dur " + minDur + "; " +
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
