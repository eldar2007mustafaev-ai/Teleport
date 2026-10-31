package com.teleport.so2;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportEngine {
    private static volatile boolean active = false;
    private static volatile boolean baseSettingsApplied = false;
    private static final AtomicBoolean commandInProgress = new AtomicBoolean(false);
    private static volatile boolean pendingUpdate = false;

    private static String currentPreset = "friend";
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = false;
    private static int antiRecoilStrength = 75;
    private static boolean hzThermalGuardEnabled = true;

    // Параметры с большим запасом (по умолчанию стоят точные числа с видео друга)
    private static float sharpness = 1.00f;
    private static float sensX = 1.00f;
    private static float sensY = 1.00f;
    private static int chunks = 10;
    private static int packetRateHz = 120;
    private static float straightening = 0.80f;
    private static int stepGrid = 3;
    private static int stickStrength = 4;
    private static int shotPause = 0;
    private static float accel = 0.30f;

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
        packetRateHz = Math.max(10, Math.min(120, pktHz));
        straightening = Math.max(0.0f, Math.min(1.0f, straight));
        stickStrength = Math.max(0, Math.min(35, stick));
        stepGrid = Math.max(1, Math.min(40, grid));
        shotPause = Math.max(0, Math.min(30, pause));
        accel = Math.max(0.0f, Math.min(2.0f, ac));

        if (active && ShizukuManager.isPermissionGranted()) {
            applyLiveQuantization();
        }
    }

    private static String buildQuantizedShizukuCommand(boolean includeHeavySettings) {
        // 1. Дискретный шаг куска (Куски + Отсечка шума + Мёртвая зона)
        int quantumSlop = Math.max(1, Math.min(32, stepGrid + (chunks / 3)));

        // 2. Осевое удержание и выпрямление по линии головы (Прилипание + Выпрямление + Хват)
        float recoilFactor = antiRecoilEnabled ? (antiRecoilStrength * 0.0004f) : 0.0f;
        float axisFriction = (smartAwpLockEnabled || "awp".equalsIgnoreCase(currentPreset))
                ? 45.0f
                : Math.max(0.002f, (stickStrength * 0.012f) + (straightening * 0.025f) + recoilFactor);

        // 3. Импульс броска между кусками
        float effectiveGain = (sharpness * 0.65f) + (sensX * 0.35f);
        int pointerSpd = Math.min(7, Math.max(1, Math.round(effectiveGain * 2.2f)));
        int minFling = Math.max(80, Math.round((chunks * 22) + (effectiveGain * 180)));
        int maxFling = Math.max(24000, Math.round(effectiveGain * 28000 * (1.0f + accel)));

        // 4. Выдержка куска (Пауза после выстрела + Куски)
        int pointerDur = Math.max(2, Math.min(35, (chunks / 2) + shotPause));

        // 5. Держим очередь 240 Гц, чтобы LTPO экран S25 Ultra НИКОГДА не падал в 60 Гц
        int safeWindowEvents = 240;

        StringBuilder sb = new StringBuilder(600);

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
        currentPreset = presetId != null ? presetId : "friend";
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
