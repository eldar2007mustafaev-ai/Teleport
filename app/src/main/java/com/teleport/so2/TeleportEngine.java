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
    private static boolean antiRecoilEnabled = true;
    private static int antiRecoilStrength = 80;
    private static boolean hzThermalGuardEnabled = true;

    // Зверские параметры по умолчанию (со скриншотов)
    private static float sharpness = 6.00f;
    private static float sensX = 6.00f;
    private static float sensY = 6.00f;
    private static int chunks = 60;
    private static int packetRateHz = 30;
    private static float straightening = 0.86f;
    private static int stepGrid = 9;
    private static int stickStrength = 12;
    private static int shotPause = 2;
    private static float accel = 2.00f;

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
        stickStrength = Math.max(0, Math.min(50, stick));
        stepGrid = Math.max(0, Math.min(45, grid));
        shotPause = Math.max(0, Math.min(30, pause));
        accel = Math.max(0.0f, Math.min(4.0f, ac));

        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    /**
     * УЛЬТРА-АГРЕССИВНЫЙ ТЕЛЕПОРТ + ЛЕГАЛЬНЫЙ КОНТРОЛЬ ЗАЖИМА БЕЗ ПОТЕРИ 120 FPS:
     * 1. Быстрые флики ("Куски только быстрее" + "Потолок куска") срываются с места с максимальным ускорением.
     * 2. Медленная оттяжка пальца вниз при зажиме ("Мелочь мимо кусков" + "Хват по осям") проходит через
     *    осевой стабилизатор, убирая горизонтальную отдачу и позволяя зажимать в одну точку.
     */
    private static String buildQuantizedShizukuCommand(boolean includeHeavySettings) {
        // 1. Дискретный порог куска с учётом обхода мелких движений для контроля зажима
        int quantumSlop = Math.max(1, Math.min(20, (stepGrid / 2) + (chunks / 5)));

        // 2. Легальный стабилизатор зажима и хват по осям (удерживает прицел от бокового разброса)
        float sprayLockFriction = antiRecoilEnabled ? (antiRecoilStrength * 0.00065f) : 0.0f;
        float axisFriction = (smartAwpLockEnabled || "awp".equalsIgnoreCase(currentPreset))
                ? 60.0f
                : Math.max(0.004f, (stickStrength * 0.015f) + (straightening * 0.042f) + sprayLockFriction);

        // 3. Зверский импульс срыва для мгновенного телепорта между мишенями
        float burstGain = (sharpness * 0.55f) + (sensX * 0.45f) + (accel * 0.65f);
        int pointerSpd = Math.min(7, Math.max(3, Math.round(burstGain)));
        int minFling = Math.max(350, Math.round((chunks * 55) + (burstGain * 500)));
        int maxFling = Math.max(48000, Math.round((burstGain + (packetRateHz * 0.2f)) * 19500));

        // 4. Квантовая выдержка точки при остановке в голове
        int pointerDur = Math.max(1, Math.min(24, (chunks / 5) + shotPause));

        // 5. Строго 240 событий/сек, чтобы контроллер LTPO на S25 Ultra держал 120 FPS без единого дропа
        int safeWindowEvents = 240;

        StringBuilder sb = new StringBuilder(680);

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
