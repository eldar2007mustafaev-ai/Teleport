package com.teleport.so2;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportEngine {
    private static volatile boolean active = false;
    private static final AtomicBoolean commandInProgress = new AtomicBoolean(false);
    private static volatile boolean pendingUpdate = false;

    private static String currentPreset = "max_teleport";
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = false;
    private static int antiRecoilStrength = 75;
    private static boolean hzThermalGuardEnabled = true;

    // Параметры квантового телепорта
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

    public static int getStrobeSpeed() {
        return Math.max(1, strobeSpeed);
    }

    /**
     * Длительность склейки пакета касаний (мс):
     * Чем выше чанки, тем крупнее кусок пути пальца склеивается в один мгновенный прыжок камеры.
     */
    public static int getBatchHoldMs() {
        return Math.max(12, Math.min(42, (chunks * 3) / 4));
    }

    /**
     * Интервал между выбросами склеенных кусков в игру (мс).
     */
    public static int getBatchReleaseIntervalMs() {
        return Math.max(18, 65 - (strobeSpeed * 4));
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
        int effectiveChunks = Math.max(15, Math.round(chunks * (sharpness / 1.15f)));
        int quantizedRateHz = 1;
        int holdDurationMs = Math.max(180, effectiveChunks * 4 + (shotPause * 6));
        long phaseOffsetNs = effectiveChunks * 550000L;

        String frictionVal = (smartAwpLockEnabled || "awp".equalsIgnoreCase(currentPreset) || stickStrength >= 8)
                ? "9999.0" : "0.0";

        int pointerSpd = Math.min(7, Math.max(4, Math.round(sharpness + 2.0f)));

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
            "setprop view.maximum_fling_velocity 160000; " +
            "setprop debug.sf.latch_unsignaled 1; " +
            "setprop debug.sf.disable_backpressure 1; " +
            "setprop debug.hwui.use_buffer_age false; " +
            "cmd power set-fixed-performance-mode-enabled true; " +
            "cmd thermalservice override-status 0; " +
            "cmd game mode performance com.axlebolt.standoff2";
    }

    /**
     * Выполняет команду без создания очереди зависающих потоков при быстром движении ползунка.
     */
    private static void applyLiveQuantization() {
        if (!ShizukuManager.isPermissionGranted()) return;
        if (!commandInProgress.compareAndSet(false, true)) {
            pendingUpdate = true;
            return;
        }
        new Thread(() -> {
            try {
                do {
                    pendingUpdate = false;
                    ShizukuManager.execCommand(buildQuantizedShizukuCommand());
                } while (pendingUpdate && active);
            } finally {
                commandInProgress.set(false);
            }
        }).start();
    }

    public static void startTeleport(Context context, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "max_teleport";
        applyLiveQuantization();
    }

    public static void stopTeleport() {
        active = false;
        pendingUpdate = false;
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
