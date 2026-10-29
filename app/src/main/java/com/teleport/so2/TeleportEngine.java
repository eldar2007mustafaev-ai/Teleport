package com.teleport.so2;

import android.content.Context;

public class TeleportEngine {
    private static boolean active = false;
    private static float snapPowerMultiplier = 12.0f;
    private static String currentPreset = "teleport";
    private static float screenCalibFactor = 2.10f;

    // Модули Build 401+ (Overdrive Edition)
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = false;
    private static int antiRecoilStrength = 70;
    private static boolean hzThermalGuardEnabled = false;
    private static boolean overdriveModeEnabled = true;
    private static boolean vectorStraighteningEnabled = true;

    // Выкрученные по умолчанию параметры для жесткого телепорта кусками
    private static float sharpness = 5.00f;
    private static float sensX = 4.50f;
    private static float sensY = 4.20f;
    private static int noiseCutoff = 0;
    private static int deadZone = 0;
    private static int prediction = 10;
    private static int axisStick = 5;
    private static int stickDelay = 0;
    private static int stickStrength = 4;
    private static int chunks = 50;
    private static int bypassChunks = 0;
    private static int chunkCap = 0;
    private static int chunksFaster = 0;
    private static int shotPause = 0;
    private static float accel = 0.45f;
    private static float accelCurve = 0.01f;
    private static int edgeRange = 0;
    private static float straightening = 0.96f;
    private static int axisGrip = 6;
    private static int gripCap = 0;

    public static void initOverlayPulse(Context context) {
    }

    public static void setCalibrationFactor(float factor) {
        screenCalibFactor = Math.max(1.50f, Math.min(5.0f, factor));
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static void setStepQuantization(boolean enabled, float multiplier) {
        snapPowerMultiplier = Math.max(5.0f, multiplier);
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static void setOverdriveUnleashed(boolean enabled) {
        overdriveModeEnabled = enabled;
        if (enabled) {
            sharpness = 5.00f;
            sensX = 5.00f;
            sensY = 4.80f;
            chunks = 50;
            chunkCap = 0;
            snapPowerMultiplier = 22.0f;
        }
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static boolean isOverdriveEnabled() {
        return overdriveModeEnabled;
    }

    public static void setVectorStraightening(boolean enabled) {
        vectorStraighteningEnabled = enabled;
        straightening = enabled ? 0.98f : 0.50f;
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
        if (deadZone > 0 && abs < (deadZone * 0.0002f)) return 0f;
        if (noiseCutoff > 0 && abs < (noiseCutoff * 0.0003f)) return 0f;

        float overdriveBoost = overdriveModeEnabled ? 1.85f : 1.0f;
        float recoilComp = antiRecoilEnabled ? (1.0f + (antiRecoilStrength / 140.0f)) : 1.0f;
        float axisMult = (rawDelta >= 0 ? sensX : (sensY * recoilComp)) * sharpness * screenCalibFactor * overdriveBoost;

        // Экстремальная формула дискретного прыжка кусками
        float chunkStep = Math.max(10, chunks) * 42.0f * snapPowerMultiplier * axisMult;
        float rawBoost = abs * (75.0f + prediction * 8.0f) * snapPowerMultiplier * axisMult;

        float finalStep = Math.max(rawBoost, chunkStep);
        if (smartAwpLockEnabled && abs < 0.0025f) {
            return 0f;
        }
        if (chunkCap > 0 && !overdriveModeEnabled) {
            finalStep = Math.min(finalStep, chunkCap * 40.0f);
        }
        return Math.copySign(finalStep, rawDelta);
    }

    private static String buildQuantizedShizukuCommand() {
        boolean isSmoothMode = "smooth".equals(currentPreset) && !overdriveModeEnabled;

        int quantizedEventsPerSec;
        int minPointerDurMs;
        int pointerSpeedVal = 7;
        int touchSlopPx = 0;
        String scrollFrictionVal = "0.0";
        int minFlingVel = 1;
        int maxFlingVel = 96000;

        if (isSmoothMode) {
            quantizedEventsPerSec = 180;
            minPointerDurMs = 2;
            pointerSpeedVal = 3;
            touchSlopPx = 4;
            scrollFrictionVal = "0.008";
            minFlingVel = 40;
            maxFlingVel = 16000;
        } else if ("awp".equals(currentPreset) || smartAwpLockEnabled) {
            // Жесткий снап с мгновенным тормозом в конце флика
            quantizedEventsPerSec = 4;
            minPointerDurMs = Math.max(115, shotPause * 8 + 90);
            scrollFrictionVal = "999.0";
            minFlingVel = 48000;
            maxFlingVel = 96000;
        } else {
            // БЕЗЛИМИТНЫЙ РЕЖИМ ТЕЛЕПОРТА (HYPER-CHUNK 2..3 Hz):
            // Сжимает частоту кадров тача до предела, заставляя экран отдавать координаты огромными кусками
            int effectiveChunks = overdriveModeEnabled ? 50 : Math.max(25, Math.round(chunks * (sharpness / 1.3f)));
            quantizedEventsPerSec = overdriveModeEnabled ? 2 : Math.max(3, Math.min(6, 32 - effectiveChunks));
            minPointerDurMs = overdriveModeEnabled ? 210 : Math.max(135, Math.min(200, effectiveChunks * 5));
        }

        if (antiRecoilEnabled) {
            minPointerDurMs = Math.min(230, minPointerDurMs + (antiRecoilStrength / 4));
        }

        String straightProps = vectorStraighteningEnabled ? (
            "setprop touch.size.calibration geometric; " +
            "setprop touch.pressure.scale 0.0001; " +
            "setprop touch.orientation.calibration none; "
        ) : "";

        String hzGuardBlock = hzThermalGuardEnabled ? (
            "settings put system peak_refresh_rate 120.0; " +
            "settings put system min_refresh_rate 120.0; " +
            "settings put secure user_refresh_rate 120; " +
            "cmd power set-fixed-performance-mode-enabled true; " +
            "cmd thermalservice override-status 0; "
        ) : "";

        return
            hzGuardBlock +
            straightProps +
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
            "setprop ro.input.no_resample 1; " +
            "setprop debug.sf.vsp_touch_boost 1; " +
            "setprop view.touch_slop " + touchSlopPx + "; " +
            "setprop windowsmgr.max_events_per_sec " + quantizedEventsPerSec + "; " +
            "setprop ro.min_pointer_dur " + minPointerDurMs + "; " +
            "setprop debug.sf.early_phase_offset_ns 500000; " +
            "setprop debug.sf.early_gl_phase_offset_ns 500000; " +
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
        snapPowerMultiplier = Math.max(8.0f, snapPercent / 18.0f);

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
                "setprop ro.input.no_resample 0; " +
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
