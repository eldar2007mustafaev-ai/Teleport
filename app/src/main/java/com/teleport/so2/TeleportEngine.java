package com.teleport.so2;

import android.content.Context;

public class TeleportEngine {
    private static boolean active = false;
    private static float snapPowerMultiplier = 5.0f;
    private static String currentPreset = "teleport";
    private static float screenCalibFactor = 1.45f;

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
        screenCalibFactor = Math.max(1.1f, Math.min(3.5f, factor));
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static void setStepQuantization(boolean enabled, float multiplier) {
        snapPowerMultiplier = Math.max(2.0f, multiplier);
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
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
        if (deadZone > 0 && abs < (deadZone * 0.0005f)) return 0f;
        if (noiseCutoff > 0 && abs < (noiseCutoff * 0.0007f)) return 0f;

        float axisMult = (rawDelta >= 0 ? sensX : sensY) * sharpness * screenCalibFactor;
        float chunkStep = Math.max(1, chunks) * 14.0f * snapPowerMultiplier * axisMult;
        float rawBoost = abs * (28.0f + prediction * 3.0f) * snapPowerMultiplier * axisMult;

        float finalStep = (chunksFaster == 0) ? Math.max(rawBoost, chunkStep) : rawBoost;
        if (chunkCap > 0) {
            finalStep = Math.min(finalStep, chunkCap * 30.0f);
        }
        return Math.copySign(finalStep, rawDelta);
    }

    private static String buildQuantizedShizukuCommand() {
        boolean isSmoothMode = "smooth".equals(currentPreset) || chunks <= 1;

        int quantizedEventsPerSec;
        int minPointerDurMs;
        int pointerSpeedVal;
        int touchSlopPx;

        if (isSmoothMode) {
            quantizedEventsPerSec = 240;
            minPointerDurMs = 1;
            pointerSpeedVal = 2;
            touchSlopPx = 8;
        } else if ("awp".equals(currentPreset)) {
            quantizedEventsPerSec = 10;
            minPointerDurMs = Math.max(35, shotPause * 5 + 30);
            pointerSpeedVal = 7;
            touchSlopPx = 1;
        } else {
            int effectiveChunks = Math.max(10, Math.round(chunks * (sharpness / 2.2f)));
            quantizedEventsPerSec = Math.max(5, Math.min(18, 36 - effectiveChunks));
            minPointerDurMs = Math.max(45, Math.min(115, effectiveChunks * 3));
            pointerSpeedVal = 7;
            touchSlopPx = 1;
        }

        return
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
            "setprop debug.sf.early_phase_offset_ns 1500000; " +
            "setprop debug.sf.early_gl_phase_offset_ns 1500000; " +
            "setprop view.scroll_friction 0.0; " +
            "setprop view.minimum_fling_velocity 1; " +
            "setprop view.maximum_fling_velocity 40000; " +
            "setprop debug.sf.latch_unsignaled 1; " +
            "setprop debug.sf.disable_backpressure 1; " +
            "setprop debug.hwui.use_buffer_age false; " +
            "cmd game mode performance com.axlebolt.standoff2; " +
            "cmd game downscale disable com.axlebolt.standoff2";
    }

    private static void applyLiveQuantization() {
        new Thread(() -> {
            if (!ShizukuManager.isPermissionGranted()) return;
            ShizukuManager.execCommand(buildQuantizedShizukuCommand());
        }).start();
    }

    public static void startTeleport(int snapPercent, String presetId) {
        startTeleport(null, snapPercent, presetId);
    }

    public static void startTeleport(Context context, int snapPercent, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "teleport";
        snapPowerMultiplier = Math.max(3.0f, snapPercent / 35.0f);

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
                "setprop view.minimum_fling_velocity 50; " +
                "setprop view.maximum_fling_velocity 8000; " +
                "setprop debug.sf.latch_unsignaled 0; " +
                "setprop debug.sf.disable_backpressure 0";
            ShizukuManager.execCommand(cmd);
        }).start();
    }
}
