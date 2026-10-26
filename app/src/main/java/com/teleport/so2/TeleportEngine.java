package com.teleport.so2;

import android.content.Context;

public class TeleportEngine {
    private static boolean active = false;
    private static float snapPowerMultiplier = 4.0f;
    private static String currentPreset = "teleport";

    // СВОИ ЧИСЛА (все параметры с 1-го видео)
    private static float sharpness = 1.00f;
    private static float sensX = 1.00f;
    private static float sensY = 1.00f;
    private static int noiseCutoff = 3;
    private static int deadZone = 1;
    private static int prediction = 0;
    private static int axisStick = 4;
    private static int stickDelay = 29;
    private static int stickStrength = 1;
    private static int chunks = 10; // Главный параметр телепорта ("Куски")
    private static int bypassChunks = 0;
    private static int chunkCap = 0;
    private static int chunksFaster = 0;
    private static int shotPause = 0;
    private static float accel = 0.00f;
    private static float accelCurve = 0.30f;
    private static int edgeRange = 0;
    private static float straightening = 0.80f;
    private static int axisGrip = 4;
    private static int gripCap = 4;

    public static void initOverlayPulse(Context context) {
        // Оверлей-пульсатор отключен навсегда: он вызывал лаг кликов и залипание иконок
    }

    public static void setStepQuantization(boolean enabled, float multiplier) {
        snapPowerMultiplier = Math.max(1.0f, multiplier);
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
     * Расчет дискретного прыжка ("Кусков" телепорта) без сглаживания
     */
    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (abs < (deadZone * 0.0012f)) return 0f;
        if (abs < (noiseCutoff * 0.0015f)) return 0f;

        float axisMult = (rawDelta >= 0 ? sensX : sensY) * sharpness;
        float chunkStep = Math.max(1, chunks) * 4.8f * snapPowerMultiplier * axisMult;
        float accelBoost = 1.0f + (float) Math.pow(abs, Math.max(0.1f, accelCurve)) * accel * 3.5f;
        float predicted = (abs * (14.0f + prediction * 1.8f) * snapPowerMultiplier * axisMult) * accelBoost;

        float finalStep = Math.max(predicted, chunkStep);
        if (chunkCap > 0) {
            finalStep = Math.min(finalStep, chunkCap * 18.0f);
        }
        return Math.copySign(finalStep, rawDelta);
    }

    private static void applyLiveQuantization() {
        new Thread(() -> {
            // Чем больше "Куски" (chunks) и мощность УЛЬТРА, тем жестче квантуются пакеты тача:
            // вместо плавной воды камера прыгает мгновенными рывками (как на 2-м видео)
            int calcEvents;
            int calcMinDur;

            if ("smooth".equals(currentPreset)) {
                calcEvents = 180;
                calcMinDur = 2;
            } else {
                int effectiveChunks = Math.max(1, chunks + (int)(snapPowerMultiplier * 1.5f));
                calcEvents = Math.max(12, Math.min(90, 115 - (effectiveChunks * 5)));
                calcMinDur = Math.max(8, Math.min(65, effectiveChunks * 3));
            }

            String cmd =
                "setprop ro.input.resampling 0; " +
                "setprop debug.input.resampling 0; " +
                "setprop windowsmgr.max_events_per_sec " + calcEvents + "; " +
                "setprop ro.min_pointer_dur " + calcMinDur + "; " +
                "setprop view.scroll_friction 0.0";

            ShizukuManager.execCommand(cmd);
        }).start();
    }

    public static void startTeleport(int snapPercent, String presetId) {
        startTeleport(null, snapPercent, presetId);
    }

    public static void startTeleport(Context context, int snapPercent, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "teleport";
        snapPowerMultiplier = Math.max(2.0f, snapPercent / 50.0f);

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            int effectiveChunks = Math.max(1, chunks + (int)(snapPowerMultiplier * 1.5f));
            int calcEvents = "smooth".equals(currentPreset) ? 180 : Math.max(12, Math.min(85, 110 - (effectiveChunks * 5)));
            int calcMinDur = "smooth".equals(currentPreset) ? 2 : Math.max(10, Math.min(65, effectiveChunks * 3));

            String cmd =
                // 1. Снимаем все баги с долгим нажатием, фантомными тапами и Google Assistant:
                "settings put secure long_press_timeout 500; " +
                "settings put secure multi_press_timeout 300; " +
                "settings delete secure tap_duration_threshold; " +
                "settings delete secure touch_blocking_period; " +
                "settings put system pointer_location 0; " +
                "settings put system show_touches 0; " +
                // 2. Максимальная резкость курсора и отключение системного сглаживания (resampling 0):
                "settings put system pointer_speed 7; " +
                "setprop ro.input.resampling 0; " +
                "setprop debug.input.resampling 0; " +
                // 3. Дискретные рывки (эффект мгновенного телепорта прицела на фликах):
                "setprop windowsmgr.max_events_per_sec " + calcEvents + "; " +
                "setprop ro.min_pointer_dur " + calcMinDur + "; " +
                "setprop view.scroll_friction 0.0; " +
                "setprop debug.sf.latch_unsignaled 1; " +
                "setprop debug.sf.disable_backpressure 1; " +
                "setprop debug.hwui.use_buffer_age false; " +
                "cmd game mode performance com.axlebolt.standoff2; " +
                "cmd game downscale disable com.axlebolt.standoff2";

            ShizukuManager.execCommand(cmd);
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
