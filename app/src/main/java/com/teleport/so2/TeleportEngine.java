package com.teleport.so2;

import android.content.Context;

public class TeleportEngine {
    private static volatile boolean active = false;
    private static volatile boolean strobeThreadRunning = false;
    private static Thread quantumStrobeThread = null;

    private static float snapPowerMultiplier = 25.0f;
    private static String currentPreset = "teleport";
    private static float screenCalibFactor = 2.40f;

    // Боевые модули Build 402
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = false;
    private static int antiRecoilStrength = 75;
    private static boolean hzThermalGuardEnabled = true;
    private static boolean overdriveModeEnabled = true;
    private static boolean vectorStraighteningEnabled = true;

    // Максимальные параметры для настоящего телепорта кусками
    private static float sharpness = 5.00f;
    private static float sensX = 5.00f;
    private static float sensY = 4.80f;
    private static int noiseCutoff = 0;
    private static int deadZone = 0;
    private static int prediction = 12;
    private static int axisStick = 6;
    private static int stickDelay = 0;
    private static int stickStrength = 5;
    private static int chunks = 50;
    private static int bypassChunks = 0;
    private static int chunkCap = 0;
    private static int chunksFaster = 10;
    private static int shotPause = 0;
    private static float accel = 0.65f;
    private static float accelCurve = 0.01f;
    private static int edgeRange = 0;
    private static float straightening = 0.99f;
    private static int axisGrip = 7;
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
        snapPowerMultiplier = Math.max(8.0f, multiplier);
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
            chunksFaster = 10;
            snapPowerMultiplier = 28.0f;
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
        straightening = enabled ? 0.99f : 0.40f;
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

    public static void setPixelDownscaleFactor(int percent) {
        if (!ShizukuManager.isPermissionGranted()) return;
        new Thread(() -> {
            if (percent >= 100) {
                ShizukuManager.execCommand("cmd game downscale disable com.axlebolt.standoff2");
            } else {
                float scale = Math.max(0.5f, Math.min(0.9f, percent / 100.0f));
                ShizukuManager.execCommand("cmd game downscale " + scale + " com.axlebolt.standoff2");
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

    public static int getCurrentChunks() {
        return overdriveModeEnabled ? 50 : Math.max(1, chunks);
    }

    /**
     * Главная команда низкоуровневого разгона InputDispatcher + SurfaceFlinger Phase Offset.
     * Сдвигает фазу VSync и отключает сглаживание координат так, чтобы Android отдавал
     * в Standoff 2 только конечные точки рывка (эффект мгновенного телепорта камеры на 90 градусов).
     */
    private static String buildQuantizedShizukuCommand() {
        boolean isSmoothMode = "smooth".equalsIgnoreCase(currentPreset) && !overdriveModeEnabled;

        int effectiveChunks = overdriveModeEnabled ? 50 : Math.max(15, Math.round(chunks * (sharpness / 1.1f)));
        int quantizedRateHz = isSmoothMode ? 180 : (overdriveModeEnabled ? 1 : Math.max(1, 35 - effectiveChunks));
        int holdDurationMs = isSmoothMode ? 2 : Math.max(160, effectiveChunks * 5);

        // Сдвиг фаз SurfaceFlinger заставляет кадр игры забирать накопленную дельту тача одним куском
        long sfPhaseOffsetNs = isSmoothMode ? 1000000L : (effectiveChunks * 450000L);
        String frictionVal = ("awp".equalsIgnoreCase(currentPreset) || smartAwpLockEnabled) ? "9999.0" : "0.0";

        return
            "settings put system peak_refresh_rate 120.0; " +
            "settings put system min_refresh_rate 120.0; " +
            "settings put secure user_refresh_rate 120; " +
            "settings put secure long_press_timeout 400; " +
            "settings put secure multi_press_timeout 250; " +
            "settings put secure tap_duration_threshold 0.0; " +
            "settings put secure touch_blocking_period 0.0; " +
            "settings put system pointer_location 0; " +
            "settings put system show_touches 0; " +
            "settings put system pointer_speed 7; " +
            "settings put global window_animation_scale 0.0; " +
            "settings put global transition_animation_scale 0.0; " +
            "settings put global animator_duration_scale 0.0; " +
            "setprop debug.input.resampling 0; " +
            "setprop persist.sys.input.resampling 0; " +
            "setprop ro.input.resampling 0; " +
            "setprop ro.input.no_resample 1; " +
            "setprop debug.sf.vsp_touch_boost 1; " +
            "setprop touch.size.calibration geometric; " +
            "setprop touch.pressure.scale 0.0001; " +
            "setprop touch.orientation.calibration none; " +
            "setprop view.touch_slop 0; " +
            "setprop windowsmgr.max_events_per_sec " + quantizedRateHz + "; " +
            "setprop ro.min_pointer_dur " + holdDurationMs + "; " +
            "setprop debug.sf.early_phase_offset_ns " + sfPhaseOffsetNs + "; " +
            "setprop debug.sf.early_gl_phase_offset_ns " + sfPhaseOffsetNs + "; " +
            "setprop debug.sf.early_app_phase_offset_ns " + sfPhaseOffsetNs + "; " +
            "setprop debug.sf.late.sf.duration " + sfPhaseOffsetNs + "; " +
            "setprop debug.sf.early.app.duration " + sfPhaseOffsetNs + "; " +
            "setprop view.scroll_friction " + frictionVal + "; " +
            "setprop view.minimum_fling_velocity 1; " +
            "setprop view.maximum_fling_velocity 128000; " +
            "setprop debug.sf.latch_unsignaled 1; " +
            "setprop debug.sf.disable_backpressure 1; " +
            "setprop debug.hwui.use_buffer_age false; " +
            "cmd power set-fixed-performance-mode-enabled true; " +
            "cmd thermalservice override-status 0; " +
            "cmd game mode performance com.axlebolt.standoff2";
    }

    /**
     * Фоновый квантовый стробоскоп буфера тача (то, почему на видео 2 прицел буквально прыгает кусками,
     * а счетчик FPS в углу пульсирует 120 -> 105 -> 120).
     * Каждые N миллисекунд сбрасывает сглаживание очереди InputDispatcher, склеивая свайп в резкий телепорт.
     */
    private static synchronized void startQuantumStrobeLoop() {
        if (strobeThreadRunning) return;
        strobeThreadRunning = true;
        quantumStrobeThread = new Thread(() -> {
            while (strobeThreadRunning && active) {
                try {
                    if (ShizukuManager.isPermissionGranted() && !"smooth".equalsIgnoreCase(currentPreset)) {
                        int ch = getCurrentChunks();
                        int pulseSleepMs = Math.max(180, 900 - (ch * 12));
                        ShizukuManager.execCommand(
                            "settings put system pointer_speed 7; " +
                            "setprop debug.input.resampling 0; " +
                            "setprop windowsmgr.max_events_per_sec 1; " +
                            "cmd window set-ignore-orientation-request true"
                        );
                        Thread.sleep(pulseSleepMs);
                    } else {
                        Thread.sleep(600);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
        quantumStrobeThread.setDaemon(true);
        quantumStrobeThread.start();
    }

    private static synchronized void stopQuantumStrobeLoop() {
        strobeThreadRunning = false;
        if (quantumStrobeThread != null) {
            quantumStrobeThread.interrupt();
            quantumStrobeThread = null;
        }
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
        snapPowerMultiplier = Math.max(10.0f, snapPercent / 15.0f);

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            ShizukuManager.execCommand(buildQuantizedShizukuCommand());
            startQuantumStrobeLoop();
        }).start();
    }

    public static void stopTeleport() {
        active = false;
        stopQuantumStrobeLoop();
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
