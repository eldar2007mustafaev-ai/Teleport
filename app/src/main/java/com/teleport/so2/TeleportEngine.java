package com.teleport.so2;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportEngine {
    private static volatile boolean active = false;
    private static volatile boolean baseSettingsApplied = false;
    private static final AtomicBoolean commandInProgress = new AtomicBoolean(false);
    private static volatile boolean pendingUpdate = false;

    private static String currentPreset = "max_teleport";
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = false;
    private static int antiRecoilStrength = 75;
    private static boolean hzThermalGuardEnabled = true;

    // Боевые параметры по умолчанию (МАКС ТЕЛЕПОРТ)
    private static float sharpness = 5.00f;
    private static float sensX = 5.00f;
    private static float sensY = 4.80f;
    private static int chunks = 50;
    private static int packetRateHz = 10;
    private static float straightening = 0.98f;
    private static int stepGrid = 1;
    private static int stickStrength = 6;
    private static int shotPause = 0;
    private static float accel = 0.00f;

    public static boolean isActive() {
        return active;
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
                    "settings put secure refresh_rate_mode 2; " +
                    "settings put global window_animation_scale 0.0; " +
                    "settings put global transition_animation_scale 0.0; " +
                    "settings put global animator_duration_scale 0.0; " +
                    "cmd power set-fixed-performance-mode-enabled true; " +
                    "cmd game mode performance com.axlebolt.standoff2"
                );
            } else {
                ShizukuManager.execCommand(
                    "cmd power set-fixed-performance-mode-enabled false; " +
                    "settings put secure refresh_rate_mode 1"
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
                float scale = Math.max(0.5f, Math.min(0.95f, percent / 100.0f));
                ShizukuManager.execCommand("cmd game downscale " + scale + " com.axlebolt.standoff2");
            }
        }).start();
    }

    public static void updateParameters(
            float sharp, float sx, float sy,
            int ch, int pktHz, float straight,
            int stick, int grid, int pause, float ac
    ) {
        sharpness = Math.max(0.5f, Math.min(10.0f, sharp));
        sensX = Math.max(0.5f, Math.min(10.0f, sx));
        sensY = Math.max(0.5f, Math.min(10.0f, sy));
        chunks = Math.max(1, Math.min(150, ch));
        packetRateHz = Math.max(1, Math.min(120, pktHz));
        straightening = Math.max(0.0f, Math.min(1.0f, straight));
        stickStrength = Math.max(0, Math.min(35, stick));
        stepGrid = Math.max(0, Math.min(40, grid));
        shotPause = Math.max(0, Math.min(30, pause));
        accel = Math.max(0.0f, Math.min(2.0f, ac));

        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    /**
     * АГРЕССИВНЫЙ КВАНТОВЫЙ ГЕНЕРАТОР БЕЗ ПРОСАДОК ДО 60 ГЦ:
     * - Вырубает системный ресэмплинг и фильтрацию (прицел прыгает мгновенными кусками).
     * - Максимально разгоняет стартовый импульс срыва (minimum/maximum_fling_velocity).
     * - Жестко держит 120 Гц и 240 событий/сек в WindowManager, исключая статтеры и троттлинг.
     */
    private static String buildQuantizedShizukuCommand(boolean includeHeavySettings) {
        // 1. Дискретный квантовый шаг (чем выше chunks, тем резче прыжок между точками)
        int quantumSlop = Math.max(1, Math.min(22, stepGrid + (chunks / 4)));

        // 2. Осевой зацеп и стоп-фиксация в точке головы
        float recoilFactor = antiRecoilEnabled ? (antiRecoilStrength * 0.0005f) : 0.0f;
        float axisFriction = (smartAwpLockEnabled || "awp".equalsIgnoreCase(currentPreset))
                ? 55.0f
                : Math.max(0.003f, (stickStrength * 0.016f) + (straightening * 0.035f) + recoilFactor);

        // 3. Зверский импульс срыва с первой миллисекунды касания
        float powerFactor = (sharpness * 0.60f) + (sensX * 0.40f);
        int pointerSpd = Math.min(7, Math.max(2, Math.round(powerFactor * 1.4f)));
        int minFling = Math.max(250, Math.round((chunks * 45) + (powerFactor * 420)));
        int maxFling = Math.max(36000, Math.round((powerFactor + (packetRateHz * 0.15f)) * 16000));

        // 4. Квантовая выдержка строба
        int pointerDur = Math.max(1, Math.min(28, (chunks / 4) + shotPause));

        // 5. Строго 240 Гц на уровне очереди окна, чтобы LTPO экран НИКОГДА не падал в 60 FPS
        int safeWindowEvents = 240;

        StringBuilder sb = new StringBuilder(640);

        if (includeHeavySettings) {
            sb.append("settings put system peak_refresh_rate 120.0; ")
              .append("settings put system min_refresh_rate 120.0; ")
              .append("settings put secure user_refresh_rate 120; ")
              .append("settings put secure refresh_rate_mode 2; ")
              .append("settings put secure long_press_timeout 400; ")
              .append("settings put secure multi_press_timeout 250; ")
              .append("settings delete secure tap_duration_threshold; ")
              .append("settings delete secure touch_blocking_period; ")
              .append("settings put system pointer_location 0; ")
              .append("settings put system show_touches 0; ")
              .append("settings put system pointer_speed ").append(pointerSpd).append("; ")
              .append("settings put global window_animation_scale 0.0; ")
              .append("settings put global transition_animation_scale 0.0; ")
              .append("settings put global animator_duration_scale 0.0; ")
              .append("device_config delete game_overlay com.axlebolt.standoff2; ")
              .append("cmd power set-fixed-performance-mode-enabled true; ")
              .append("cmd game mode performance com.axlebolt.standoff2; ");
        } else {
            sb.append("settings put system pointer_speed ").append(pointerSpd).append("; ");
        }

        sb.append("setprop debug.sf.touch_resampling 0; ")
          .append("setprop debug.input.resampling 0; ")
          .append("setprop persist.sys.input.resampling 0; ")
          .append("setprop ro.input.resampling 0; ")
          .append("setprop ro.input.no_resample 1; ")
          .append("setprop touch.filter.level 0; ")
          .append("setprop touch.coverage.calibration box; ")
          .append("setprop touch.gestureMode spots; ")
          .append("setprop touch.size.calibration geometric; ")
          .append("setprop touch.pressure.scale 0.0001; ")
          .append("setprop touch.orientation.calibration vector; ")
          .append("setprop touch.distance.scale 1; ")
          .append("setprop touch.acceleration.hw ").append(accel > 0.05f ? "1" : "0").append("; ")
          .append("setprop windowsmgr.max_events_per_sec ").append(safeWindowEvents).append("; ")
          .append("setprop persist.sys.scrolling.max_events_per_sec ").append(safeWindowEvents).append("; ")
          .append("setprop view.touch_slop ").append(quantumSlop).append("; ")
          .append("setprop debug.input.touch_slop ").append(quantumSlop).append("; ")
          .append("setprop ro.min_pointer_dur ").append(pointerDur).append("; ")
          .append("setprop view.scroll_friction ").append(axisFriction).append("; ")
          .append("setprop view.minimum_fling_velocity ").append(minFling).append("; ")
          .append("setprop view.maximum_fling_velocity ").append(maxFling).append("; ")
          .append("setprop debug.sf.latch_unsignaled 0; ")
          .append("setprop debug.sf.disable_backpressure 0");

        return sb.toString();
    }

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
                    boolean needHeavy = !baseSettingsApplied;
                    baseSettingsApplied = true;
                    ShizukuManager.execCommand(buildQuantizedShizukuCommand(needHeavy));
                } while (pendingUpdate && active);
            } finally {
                commandInProgress.set(false);
            }
        }).start();
    }

    public static void startTeleport(Context context, String presetId) {
        active = true;
        baseSettingsApplied = false;
        currentPreset = presetId != null ? presetId : "max_teleport";
        applyLiveQuantization();
    }

    public static void stopTeleport() {
        active = false;
        baseSettingsApplied = false;
        pendingUpdate = false;
        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                "settings put system pointer_speed 0; " +
                "setprop debug.sf.touch_resampling 1; " +
                "setprop debug.input.resampling 1; " +
                "setprop persist.sys.input.resampling 1; " +
                "setprop ro.input.no_resample 0; " +
                "setprop windowsmgr.max_events_per_sec 240; " +
                "setprop view.touch_slop 8; " +
                "setprop view.scroll_friction 0.015; " +
                "setprop view.minimum_fling_velocity 50; " +
                "setprop view.maximum_fling_velocity 8000; " +
                "setprop debug.sf.latch_unsignaled 0; " +
                "setprop debug.sf.disable_backpressure 0; " +
                "cmd power set-fixed-performance-mode-enabled false";
            ShizukuManager.execCommand(cmd);
        }).start();
    }
}
