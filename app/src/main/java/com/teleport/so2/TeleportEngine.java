package com.teleport.so2;

import android.content.Context;

public class TeleportEngine {
    private static boolean active = false;
    private static float snapPowerMultiplier = 5.0f;
    private static String currentPreset = "teleport";
    private static float screenCalibFactor = 1.35f;

    // Параметры "СВОИ ЧИСЛА" (по умолчанию сразу заряжены на жёсткий телепорт кусками)
    private static float sharpness = 2.50f;
    private static float sensX = 2.50f;
    private static float sensY = 2.20f;
    private static int noiseCutoff = 0;
    private static int deadZone = 0;
    private static int prediction = 0;
    private static int axisStick = 3;
    private static int stickDelay = 15;
    private static int stickStrength = 1;
    private static int chunks = 22; // Главный параметр телепорта ("Куски")
    private static int bypassChunks = 0;
    private static int chunkCap = 0;
    private static int chunksFaster = 0;
    private static int shotPause = 0;
    private static float accel = 0.00f;
    private static float accelCurve = 0.30f;
    private static int edgeRange = 0;
    private static float straightening = 0.85f;
    private static int axisGrip = 4;
    private static int gripCap = 4;

    public static void initOverlayPulse(Context context) {
        // Оверлей не используется, чтобы не вызывать задержку клика и залипание жестов
    }

    public static void setCalibrationFactor(float factor) {
        screenCalibFactor = Math.max(1.0f, Math.min(3.5f, factor));
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static void setStepQuantization(boolean enabled, float multiplier) {
        snapPowerMultiplier = Math.max(1.5f, multiplier);
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

    /**
     * Формула агрессивного квантования ("Кусков"):
     * превращает любой микро-сдвиг пальца в мгновенный дискретный скачок
     */
    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (deadZone > 0 && abs < (deadZone * 0.0008f)) return 0f;
        if (noiseCutoff > 0 && abs < (noiseCutoff * 0.0010f)) return 0f;

        float axisMult = (rawDelta >= 0 ? sensX : sensY) * sharpness * screenCalibFactor;
        float chunkStep = Math.max(1, chunks) * 9.5f * snapPowerMultiplier * axisMult;
        float rawBoost = abs * (22.0f + prediction * 2.5f) * snapPowerMultiplier * axisMult;

        float finalStep = Math.max(rawBoost, chunkStep);
        if (chunkCap > 0) {
            finalStep = Math.min(finalStep, chunkCap * 25.0f);
        }
        return Math.copySign(finalStep, rawDelta);
    }

    private static String buildQuantizedShizukuCommand() {
        // Экстремальное квантование пакетов сенсора ("Куски"):
        // Чем выше Куски (chunks), тем реже, но крупнее система выстреливает дельту координат в игру.
        // При chunks = 20..25 частота пакетов сжимается до 6..10 Гц с огромным шагом —
        // в Standoff 2 камера буквально телепортируется рывками от малейшего движения пальца.
        int effectiveChunks = Math.max(1, Math.round((chunks + snapPowerMultiplier * 1.8f) * (screenCalibFactor * 0.85f)));

        int quantizedEventsPerSec;
        int minPointerDurMs;
        int vsyncPhaseOffset;

        if ("smooth".equals(currentPreset)) {
            quantizedEventsPerSec = 180;
            minPointerDurMs = 2;
            vsyncPhaseOffset = 0;
        } else {
            quantizedEventsPerSec = Math.max(6, Math.min(45, 62 - (effectiveChunks * 2)));
            minPointerDurMs = Math.max(18, Math.min(95, effectiveChunks * 3));
            vsyncPhaseOffset = 1000000;
        }

        return
            // 1. Защита от фантомных нажатий, залипания шторки/полоски и Google Assistant:
            "settings put secure long_press_timeout 500; " +
            "settings put secure multi_press_timeout 300; " +
            "settings delete secure tap_duration_threshold; " +
            "settings delete secure touch_blocking_period; " +
            "settings put system pointer_location 0; " +
            "settings put system show_touches 0; " +
            // 2. Максимальный множитель скорости вектора и отключение сглаживания:
            "settings put system pointer_speed 7; " +
            "setprop debug.input.resampling 0; " +
            "setprop persist.sys.input.resampling 0; " +
            "setprop debug.sf.early_phase_offset_ns " + vsyncPhaseOffset + "; " +
            "setprop debug.sf.early_gl_phase_offset_ns " + vsyncPhaseOffset + "; " +
            // 3. Квантование кусков (дискретные скачки прицела при микро-фликах):
            "setprop windowsmgr.max_events_per_sec " + quantizedEventsPerSec + "; " +
            "setprop ro.min_pointer_dur " + minPointerDurMs + "; " +
            "setprop view.scroll_friction 0.0; " +
            "setprop view.minimum_fling_velocity 1; " +
            "setprop view.maximum_fling_velocity 32000; " +
            // 4. Отключение буферизации кадров SurfaceFlinger (мгновенный кадр после рывка):
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
        snapPowerMultiplier = Math.max(2.5f, snapPercent / 40.0f);

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
