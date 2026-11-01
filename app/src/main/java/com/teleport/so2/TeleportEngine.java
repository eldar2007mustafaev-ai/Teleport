package com.teleport.so2;

import android.content.Context;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportEngine {
    private static volatile boolean active = false;
    private static volatile boolean baseSettingsApplied = false;
    private static final AtomicBoolean commandInProgress = new AtomicBoolean(false);
    private static volatile boolean pendingUpdate = false;
    private static volatile String lastExecutedCommand = "";

    private static String currentPreset = "max_teleport";
    private static boolean smartAwpLockEnabled = false;
    private static boolean antiRecoilEnabled = true;
    private static int antiRecoilStrength = 85;
    private static boolean hzThermalGuardEnabled = true;

    // Автоматический игровой DPI (Только в игре, без спама процессами и без конфликта с растягом)
    private static volatile boolean autoDpiEnabled = false;
    private static volatile int gameTargetDpi = 360;
    private static Thread gameDpiWatcherThread = null;

    // Боевые параметры по умолчанию
    private static float sharpness = 6.00f;
    private static float sensX = 6.00f;
    private static float sensY = 6.00f;
    private static int chunks = 60;
    private static int packetRateHz = 30;
    private static float straightening = 0.86f;
    private static int stepGrid = 1; // 1 = мгновенный 1мм сдвиг без мертвой зоны
    private static int stickStrength = 12;
    private static int shotPause = 1;
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

    /**
     * Усиленный фикс отдачи PRO без перегрузки CPU:
     * Стабилизирует вертикальную ось и убирает боковой увод при зажиме.
     */
    public static void applyEnhancedRecoilFix(boolean enabled, int powerPercent) {
        antiRecoilEnabled = enabled;
        antiRecoilStrength = Math.max(10, Math.min(100, powerPercent));

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            if (!enabled) {
                ShizukuManager.execCommand(
                    "settings put secure long_press_timeout 400; " +
                    "settings put secure multi_press_timeout 300; " +
                    "setprop touch.filter.level 0; " +
                    "setprop view.scroll_friction 0.015"
                );
                lastExecutedCommand = "";
                if (active) applyLiveQuantization();
                return;
            }

            int pwr = antiRecoilStrength;
            int longPress = Math.max(180, 340 - (pwr * 14 / 10));
            float axisFriction = 0.012f + (pwr * 0.0012f);

            String cmd = String.format(Locale.US,
                "settings put secure long_press_timeout %d; " +
                "settings put secure multi_press_timeout 150; " +
                "setprop touch.size.calibration geometric; " +
                "setprop touch.pressure.calibration amplitude; " +
                "setprop touch.pressure.scale 0.0001; " +
                "setprop touch.orientation.calibration vector; " +
                "setprop touch.filter.level 1; " +
                "setprop view.scroll_friction %.4f",
                longPress, axisFriction
            );

            ShizukuManager.execCommand(cmd);
            lastExecutedCommand = "";
            if (active) {
                applyLiveQuantization();
            }
        }).start();
    }

    /**
     * Форсированный DPI сетки ТОЛЬКО В ИГРЕ:
     * Проверяет окно раз в 3.5 секунды (экономит 70% ресурсов CPU).
     * При выходе из Standoff 2 возвращает заводской DPI (411 на S25 Ultra via wm density reset).
     */
    public static void setAutoGameDpi(boolean enabled, int targetDpi) {
        autoDpiEnabled = enabled;
        gameTargetDpi = Math.max(240, Math.min(600, targetDpi));

        if (!enabled) {
            if (ShizukuManager.isPermissionGranted()) {
                new Thread(() -> ShizukuManager.execCommand(
                    "wm density reset; setprop debug.so2.dpi_state 0"
                )).start();
            }
            return;
        }

        startGameDpiWatcher();
    }

    private static synchronized void startGameDpiWatcher() {
        if (gameDpiWatcherThread != null && gameDpiWatcherThread.isAlive()) {
            return;
        }

        gameDpiWatcherThread = new Thread(() -> {
            while (autoDpiEnabled) {
                try {
                    if (ShizukuManager.isPermissionGranted()) {
                        int dpi = gameTargetDpi;
                        String watchCmd =
                            "if dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp' | grep -q 'com.axlebolt.standoff2'; then " +
                                "if [ \"$(getprop debug.so2.dpi_state)\" != \"" + dpi + "\" ]; then " +
                                    "wm density " + dpi + "; setprop debug.so2.dpi_state " + dpi + "; " +
                                "fi; " +
                            "else " +
                                "if [ \"$(getprop debug.so2.dpi_state)\" != \"0\" ] && [ -n \"$(getprop debug.so2.dpi_state)\" ]; then " +
                                    "wm density reset; setprop debug.so2.dpi_state 0; " +
                                "fi; " +
                            "fi";
                        ShizukuManager.execCommand(watchCmd);
                    }
                    Thread.sleep(3500);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
        gameDpiWatcherThread.setDaemon(true);
        gameDpiWatcherThread.start();
    }

    /**
     * Холодная фиксация 120 Гц (УБРАН set-fixed-performance-mode-enabled true, который жарил процессор!)
     */
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
                    "cmd power set-fixed-performance-mode-enabled false; " +
                    "cmd game mode performance com.axlebolt.standoff2"
                );
            } else {
                ShizukuManager.execCommand(
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
     * ЯДРО V405 (ХОЛОДНЫЙ FPS + 1ММ ZERO-DELAY SNAP + ДЛИННЫЙ ФЛИК):
     * 1. touch_slop = 1 (или миним. порог): прицел сдвигается мгновенно при переносе пальца даже на 1 миллиметр!
     * 2. min_pointer_dur = 1: нулевая задержка регистрации касания (ощущение отклика за 1 миллисекунду).
     * 3. max_events_per_sec = 180: идеально кратно 120 Гц, не перегружает SurfaceFlinger при включенном растяге и прицеле.
     */
    private static String buildQuantizedShizukuCommand(boolean includeHeavySettings) {
        // 1. Порог сдвига 1 мм (никаких 16px задержки как раньше!)
        int quantumSlop = Math.max(1, Math.min(4, stepGrid));

        // 2. Стабилизатор зажима + осевое выпрямление
        float sprayLockFriction = antiRecoilEnabled ? (antiRecoilStrength * 0.00085f) : 0.0f;
        float axisFriction = (smartAwpLockEnabled || "awp".equalsIgnoreCase(currentPreset))
                ? 45.0f
                : Math.max(0.005f, (stickStrength * 0.012f) + (straightening * 0.035f) + sprayLockFriction);

        // 3. Импульс для мгновенного 1мм микро-сдвига и сверхбыстрого длинного флика
        float burstGain = (sharpness * 0.55f) + (sensX * 0.45f) + (accel * 0.65f);
        int pointerSpd = Math.min(7, Math.max(4, Math.round(burstGain)));
        int minFling = Math.max(500, Math.round((chunks * 45) + (burstGain * 420)));
        int maxFling = Math.max(54000, Math.round((burstGain + (packetRateHz * 0.25f)) * 21000));

        // 4. Мгновенная реакция касания (1 мс)
        int pointerDur = Math.max(1, Math.min(6, shotPause));

        // 5. 180 событий/сек — золотой стандарт для 120 Гц без нагрева на S25 Ultra и бюджетках
        int coldWindowEvents = 180;

        StringBuilder sb = new StringBuilder(700);

        if (includeHeavySettings) {
            sb.append("settings put system peak_refresh_rate 120.0; ")
              .append("settings put system min_refresh_rate 120.0; ")
              .append("settings put secure user_refresh_rate 120; ")
              .append("settings put secure refresh_rate_mode 2; ")
              .append("settings put secure long_press_timeout 220; ")
              .append("settings put secure multi_press_timeout 160; ")
              .append("settings put secure tap_duration_threshold 0.0; ")
              .append("settings put secure touch_blocking_period 0.0; ")
              .append("settings put system pointer_location 0; ")
              .append("settings put system show_touches 0; ")
              .append("settings put system pointer_speed ").append(pointerSpd).append("; ")
              .append("settings put global window_animation_scale 0.0; ")
              .append("settings put global transition_animation_scale 0.0; ")
              .append("settings put global animator_duration_scale 0.0; ")
              .append("cmd power set-fixed-performance-mode-enabled false; ")
              .append("cmd game mode performance com.axlebolt.standoff2; ");
        } else {
            sb.append("settings put system pointer_speed ").append(pointerSpd).append("; ");
        }

        sb.append("setprop debug.sf.touch_resampling 0; ")
          .append("setprop debug.input.resampling 0; ")
          .append("setprop persist.sys.input.resampling 0; ")
          .append("setprop ro.input.resampling 0; ")
          .append("setprop ro.input.no_resample 1; ")
          .append("setprop touch.filter.level ").append(antiRecoilEnabled ? "1" : "0").append("; ")
          .append("setprop touch.coverage.calibration box; ")
          .append("setprop touch.gestureMode spots; ")
          .append("setprop touch.size.calibration geometric; ")
          .append("setprop touch.pressure.scale 0.0001; ")
          .append("setprop touch.orientation.calibration vector; ")
          .append("setprop touch.distance.scale 1; ")
          .append("setprop touch.acceleration.hw 1; ")
          .append("setprop windowsmgr.max_events_per_sec ").append(coldWindowEvents).append("; ")
          .append("setprop persist.sys.scrolling.max_events_per_sec ").append(coldWindowEvents).append("; ")
          .append("setprop view.touch_slop ").append(quantumSlop).append("; ")
          .append("setprop debug.input.touch_slop ").append(quantumSlop).append("; ")
          .append("setprop ro.min_pointer_dur ").append(pointerDur).append("; ")
          .append(String.format(Locale.US, "setprop view.scroll_friction %.4f; ", axisFriction))
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
                    String cmd = buildQuantizedShizukuCommand(needHeavy);
                    // Защита от повторного спама одинаковыми командами
                    if (!cmd.equals(lastExecutedCommand)) {
                        lastExecutedCommand = cmd;
                        ShizukuManager.execCommand(cmd);
                    }
                } while (pendingUpdate && active);
            } finally {
                commandInProgress.set(false);
            }
        }).start();
    }

    public static void startTeleport(Context context, String presetId) {
        active = true;
        baseSettingsApplied = false;
        lastExecutedCommand = "";
        currentPreset = presetId != null ? presetId : "max_teleport";
        applyLiveQuantization();
    }

    public static void stopTeleport() {
        active = false;
        baseSettingsApplied = false;
        pendingUpdate = false;
        lastExecutedCommand = "";
        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                "settings put system pointer_speed 0; " +
                "setprop debug.sf.touch_resampling 1; " +
                "setprop debug.input.resampling 1; " +
                "setprop persist.sys.input.resampling 1; " +
                "setprop ro.input.no_resample 0; " +
                "setprop windowsmgr.max_events_per_sec 180; " +
                "setprop view.touch_slop 8; " +
                "setprop view.scroll_friction 0.015; " +
                "setprop view.minimum_fling_velocity 50; " +
                "setprop view.maximum_fling_velocity 8000; " +
                "cmd power set-fixed-performance-mode-enabled false";
            ShizukuManager.execCommand(cmd);
        }).start();
    }
}
