package com.teleport.so2;

import android.content.Context;

public class TeleportEngine {
    private static volatile boolean active = false;
    private static volatile boolean strobeThreadRunning = false;
    private static Thread quantumStrobeThread = null;

    private static String currentPreset = "max_teleport";
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = false;
    private static int antiRecoilStrength = 75;
    private static boolean hzThermalGuardEnabled = true;

    // Параметры тонкой настройки сенсы
    private static float sharpness = 5.00f;
    private static float sensX = 5.00f;
    private static float sensY = 4.80f;
    private static int chunks = 50;
    private static int strobeSpeed = 10;
    private static float straightening = 0.98f;
    private static int stickStrength = 6;
    private static int deadZone = 0;
    private static int shotPause = 0;
    private static float accel = 0.00f;

    public static boolean isActive() {
        return active;
    }

    public static int getCurrentChunks() {
        return Math.max(5, chunks);
    }

    public static int getStrobeIntervalMs() {
        // Чем выше чанки и скорость строба, тем жестче окно режет плавный свайп на дискретные прыжки
        int base = 64 - (chunks / 2) - (strobeSpeed * 2);
        return Math.max(14, Math.min(55, base));
    }

    public static boolean isSmartAwpLockEnabled() {
        return smartAwpLockEnabled;
    }

    public static boolean isAntiRecoilEnabled() {
        return antiRecoilEnabled;
    }

    public static void setSmartAwpLock(boolean enabled) {
        smartAwpLockEnabled = enabled;
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    public static void setAntiRecoilQuantization(boolean enabled, int strength) {
        antiRecoilEnabled = enabled;
        antiRecoilStrength = Math.max(10, Math.min(100, strength));
        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
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

    public static void updateParameters(
            float sharp, float sx, float sy,
            int ch, int stSpeed, float straight,
            int stick, int dz, int pause, float ac
    ) {
        sharpness = sharp;
        sensX = sx;
        sensY = sy;
        chunks = ch;
        strobeSpeed = stSpeed;
        straightening = straight;
        stickStrength = stick;
        deadZone = dz;
        shotPause = pause;
        accel = ac;

        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    private static String buildQuantizedShizukuCommand() {
        int effectiveChunks = Math.max(10, Math.round(chunks * (sharpness / 1.2f)));
        int quantizedRateHz = Math.max(1, 30 - (effectiveChunks / 2));
        int holdDurationMs = Math.max(140, effectiveChunks * 4 + (shotPause * 5));
        long phaseOffsetNs = effectiveChunks * 480000L;

        // В режиме AWP или высокого зацепа включаем мгновенный тормоз инерции после рывка
        String frictionVal = (smartAwpLockEnabled || "awp".equalsIgnoreCase(currentPreset) || stickStrength >= 8)
                ? "9999.0" : "0.0";

        int pointerSpd = Math.min(7, Math.max(3, Math.round(sharpness + 2.0f)));

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
            "settings put system pointer_speed " + pointerSpd + "; " +
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
            "setprop view.touch_slop " + deadZone + "; " +
            "setprop windowsmgr.max_events_per_sec " + quantizedRateHz + "; " +
            "setprop ro.min_pointer_dur " + holdDurationMs + "; " +
            "setprop debug.sf.early_phase_offset_ns " + phaseOffsetNs + "; " +
            "setprop debug.sf.early_gl_phase_offset_ns " + phaseOffsetNs + "; " +
            "setprop debug.sf.early_app_phase_offset_ns " + phaseOffsetNs + "; " +
            "setprop debug.sf.late.sf.duration " + phaseOffsetNs + "; " +
            "setprop debug.sf.early.app.duration " + phaseOffsetNs + "; " +
            "setprop view.scroll_friction " + frictionVal + "; " +
            "setprop view.minimum_fling_velocity 1; " +
            "setprop view.maximum_fling_velocity 140000; " +
            "setprop debug.sf.latch_unsignaled 1; " +
            "setprop debug.sf.disable_backpressure 1; " +
            "setprop debug.hwui.use_buffer_age false; " +
            "cmd power set-fixed-performance-mode-enabled true; " +
            "cmd thermalservice override-status 0; " +
            "cmd game mode performance com.axlebolt.standoff2";
    }

    private static synchronized void startQuantumStrobeLoop() {
        if (strobeThreadRunning) return;
        strobeThreadRunning = true;
        quantumStrobeThread = new Thread(() -> {
            while (strobeThreadRunning && active) {
                try {
                    if (ShizukuManager.isPermissionGranted()) {
                        int sleepMs = Math.max(160, 750 - (chunks * 10));
                        ShizukuManager.execCommand(
                            "settings put system pointer_speed 7; " +
                            "setprop debug.input.resampling 0; " +
                            "setprop windowsmgr.max_events_per_sec 1; " +
                            "setprop debug.sf.latch_unsignaled 1"
                        );
                        Thread.sleep(sleepMs);
                    } else {
                        Thread.sleep(500);
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

    public static void startTeleport(Context context, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "max_teleport";
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
