package com.teleport.so2;

import android.content.Context;

public class TeleportEngine {
    private static boolean active = false;
    private static float snapPowerMultiplier = 7.0f;
    private static String currentPreset = "teleport";
    private static float screenCalibFactor = 1.65f;

    // Новые модули Build 401
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = false;
    private static int antiRecoilStrength = 65; // 1..100
    private static boolean hzThermalGuardEnabled = false;

    private static float sharpness = 3.00f;
    private static float sensX = 3.00f;
    private static float sensY = 3.00f;
    private static int noiseCutoff = 0;
    private static int deadZone = 0;
    private static int prediction = 0;
    private static int axisStick = 3;
    private static int stickDelay = 0;
    private static int stickStrength = 1;
    private static int chunks = 25;
    private static int bypassChunks = 0;
    private static int chunkCap = 0;
    private static int chunksFaster = 0;
    private static int shotPause = 0;
    private static float accel = 0.00f;
    private static float accelCurve = 0.05f;
    private static int edgeRange = 0;
    private static float straightening = 0.85f;
    private static int axisGrip = 4;
    private static int gripCap = 4;

    public static void initOverlayPulse(Context context) {
    }

    public static void setCalibrationFactor(float factor) {
        screenCalibFactor = Math.max(1.25f, Math.min(4.0f, factor));
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static void setStepQuantization(boolean enabled, float multiplier) {
        snapPowerMultiplier = Math.max(3.5f, multiplier);
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static void setSmartAwpLock(boolean enabled) {
        smartAwpLockEnabled = enabled;
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static boolean isSmartAwpLockEnabled() {
        return smartAwpLockEnabled;
    }

    public static void setAntiRecoilQuantization(boolean enabled, int strength) {
        antiRecoilEnabled = enabled;
        antiRecoilStrength = Math.max(10, Math.min(100, strength));
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static boolean isAntiRecoilEnabled() {
        return antiRecoilEnabled;
    }

    public static void setHzThermalGuard(boolean enabled) {
        hzThermalGuardEnabled = enabled;
        if (!ShizukuManager.isPermissionGranted()) return;
        new Thread(() -> {
            if (enabled) {
                ShizukuManager.execCommand(
                    "settings put system peak_refresh_rate 120.0; " +
                    "settings put system min_refresh_rate 120.0; " +
                    "settings put secure user_refresh_rate 120; " +
                    "cmd power set-fixed-performance-mode-enabled true; " +
                    "cmd thermalservice override-status 0; " +
                    "cmd game mode performance com.axlebolt.standoff2"
                );
            } else {
                ShizukuManager.execCommand(
                    "cmd power set-fixed-performance-mode-enabled false; " +
                    "cmd thermalservice reset"
                );
            }
        }).start();
    }

    public static void setCustomNumbers(
            float sharp, float sx, float sy,
            int noise, int dz, int pred,
            int aStick, int sDelay, int sStrength,
            int ch, int byCh, int chCap, int chFast, int sPause,
            float ac, float acCurve, int eRange, float straight, int aGrip, int gCap
    ) {
        sharpness = sharp;
        sensX = sx;
        sensY = sy;
        noiseCutoff = noise;
        deadZone = dz;
        prediction = pred;
        axisStick = aStick;
        stickDelay = sDelay;
        stickStrength = sStrength;
        chunks = ch;
        bypassChunks = byCh;
        chunkCap = chCap;
        chunksFaster = chFast;
        shotPause = sPause;
        accel = ac;
        accelCurve = acCurve;
        edgeRange = eRange;
        straightening = straight;
        axisGrip = aGrip;
        gripCap = gCap;

        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (deadZone > 0 && abs < (deadZone * 0.0004f)) return 0f;
        if (noiseCutoff > 0 && abs < (noiseCutoff * 0.0005f)) return 0f;

        float recoilComp = antiRecoilEnabled ? (1.0f + (antiRecoilStrength / 180.0f)) : 1.0f;
        float axisMult = (rawDelta >= 0 ? sensX : (sensY * recoilComp)) * sharpness * screenCalibFactor;
        float chunkStep = Math.max(1, chunks) * 26.0f * snapPowerMultiplier * axisMult;
        float rawBoost = abs * (48.0f + prediction * 4.5f) * snapPowerMultiplier * axisMult;

        float finalStep = (chunksFaster == 0) ? Math.max(rawBoost, chunkStep) : rawBoost;
        if (smartAwpLockEnabled && abs < 0.0022f) {
            return 0f; // Стоп-флик фиксация микро-дрожи после выстрела с AWM
        }
        if (chunkCap > 0) {
            finalStep = Math.min(finalStep, chunkCap * 35.0f);
        }
        return Math.copySign(finalStep, rawDelta);
    }

    private static String buildQuantizedShizukuCommand() {
        boolean isSmoothMode = "smooth".equals(currentPreset) || chunks <= 1;

        int quantizedEventsPerSec;
        int minPointerDurMs;
        int pointerSpeedVal;
        int touchSlopPx;
        String scrollFrictionVal;
        int minFlingVel;
        int maxFlingVel;

        if (isSmoothMode) {
            quantizedEventsPerSec = 240;
            minPointerDurMs = 1;
            pointerSpeedVal = 2;
            touchSlopPx = 8;
            scrollFrictionVal = "0.015";
            minFlingVel = 50;
            maxFlingVel = 12000;
        } else if ("awp".equals(currentPreset) || smartAwpLockEnabled) {
            // Smart AWP-Lock: жесткая отсечка инерции при отпускании пальца + точечный стоп-флик
            quantizedEventsPerSec = 6;
            minPointerDurMs = Math.max(70, shotPause * 6 + 60);
            pointerSpeedVal = 7;
            touchSlopPx = 0;
            scrollFrictionVal = "999.0";
            minFlingVel = 32000;
            maxFlingVel = 64000;
        } else {
            // ЭКСТРЕМАЛЬНЫЙ БАФФ ТЕЛЕПОРТА ("КВАНТОВЫЕ КУСКИ"):
            // Частота буфера сжимается до 4 Гц, а склейка импульса до 145 мс.
            int effectiveChunks = Math.max(18, Math.round(chunks * (sharpness / 1.6f)));
            quantizedEventsPerSec = Math.max(4, Math.min(8, 30 - effectiveChunks));
            minPointerDurMs = Math.max(95, Math.min(150, effectiveChunks * 4));
            pointerSpeedVal = 7;
            touchSlopPx = 0;
            scrollFrictionVal = "0.0";
            minFlingVel = 1;
            maxFlingVel = 64000;
        }

        if (antiRecoilEnabled) {
            // Квантовое гашение отдачи: уплотняем вертикальный шаг стабилизации
            minPointerDurMs = Math.min(160, minPointerDurMs + (antiRecoilStrength / 5));
        }

        String hzGuardBlock = hzThermalGuardEnabled ? (
            "settings put system peak_refresh_rate 120.0; " +
            "settings put system min_refresh_rate 120.0; " +
            "settings put secure user_refresh_rate 120; " +
            "cmd power set-fixed-performance-mode-enabled true; " +
            "cmd thermalservice override-status 0; "
        ) : "";

        return
            hzGuardBlock +
            "settings put secure long_press_timeout 500; " +
            "settings put secure multi_press_timeout 300; " +
            "settings delete secure tap_duration_threshold; " +
            "settings delete secure touch_blocking_period; " +
            "settings put system pointer_location 0; " +
            "settings put system show_touches 0; " +
            "settings put system pointer_speed " + pointerSpeedVal + "; " +
            "setprop debug.input.resampling 0; " +
            "setprop persist.sys.input.resampling 0; " +
            "setprop ro.input.resampling 0; " +
            "setprop view.touch_slop " + touchSlopPx + "; " +
            "setprop windowsmgr.max_events_per_sec " + quantizedEventsPerSec + "; " +
            "setprop ro.min_pointer_dur " + minPointerDurMs + "; " +
            "setprop debug.sf.early_phase_offset_ns 2000000; " +
            "setprop debug.sf.early_gl_phase_offset_ns 2000000; " +
            "setprop view.scroll_friction " + scrollFrictionVal + "; " +
            "setprop view.minimum_fling_velocity " + minFlingVel + "; " +
            "setprop view.maximum_fling_velocity " + maxFlingVel + "; " +
            "setprop debug.sf.latch_unsignaled 1; " +
            "setprop debug.sf.disable_backpressure 1; " +
            "setprop debug.hwui.use_buffer_age false; " +
            "cmd game mode performance com.axlebolt.standoff2; " +
            "cmd game downscale disable com.axlebolt.standoff2";
    }

    private static void applyLiveQuantization() {
        new Thread(() -> {
            if (!ApiManager.isSessionVerified()) return;
            if (!ShizukuManager.isPermissionGranted()) return;
            ShizukuManager.execCommand(buildQuantizedShizukuCommand());
        }).start();
    }

    public static void startTeleport(int snapPercent, String presetId) {
        startTeleport(null, snapPercent, presetId);
    }

    public static void startTeleport(Context context, int snapPercent, String presetId) {
        if (!ApiManager.isSessionVerified()) {
            active = false;
            return;
        }
        active = true;
        currentPreset = presetId != null ? presetId : "teleport";
        snapPowerMultiplier = Math.max(4.5f, snapPercent / 25.0f);

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            ShizukuManager.execCommand(buildQuantizedShizukuCommand());
        }).start();
    }

    public static void stopTeleport() {
        active = false;
        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                "settings put secure long_press_timeout 500; " +
                "settings put secure multi_press_timeout 300; " +
                "settings delete secure tap_duration_threshold; " +
                "settings delete secure touch_blocking_period; " +
                "settings put system pointer_speed 0; " +
                "setprop debug.input.resampling 1; " +
                "setprop windowsmgr.max_events_per_sec 240; " +
                "setprop view.scroll_friction 0.015; " +
                "setprop view.minimum_fling_velocity 50; " +
                "setprop view.maximum_fling_velocity 8000; " +
                "setprop debug.sf.latch_unsignaled 0; " +
                "setprop debug.sf.disable_backpressure 0";
            ShizukuManager.execCommand(cmd);
        }).start();
    }
}
