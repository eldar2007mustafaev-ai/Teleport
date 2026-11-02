package com.teleport.so2;

import android.app.ActivityManager;
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

    // Разгон герцовки тача (Touch Sampling Rate Boost: 240 / 300 / 360 Гц)
    private static volatile boolean touchSamplingBoostEnabled = true;
    private static volatile int targetSamplingHz = 360;

    // Автоматический игровой DPI (Только в игре)
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
    private static int stepGrid = 1;
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
     * РАЗГОН ГЕРЦОВКИ ТАЧА (Touch Sampling Rate Boost 240–360 Гц):
     * Переводит контроллер тачскрина в режим минимальной задержки и максимальной частоты опроса.
     */
    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        touchSamplingBoostEnabled = enabled;
        targetSamplingHz = Math.max(120, Math.min(360, hz));

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            if (!enabled) {
                ShizukuManager.execCommand(
                    "settings put system touch_sensitivity_mode 0; " +
                    "setprop debug.input.touch_sampling_hz 120; " +
                    "setprop persist.vendor.touch.sampling_rate 120; " +
                    "setprop debug.sf.early_phase_offset_ns 0"
                );
                lastExecutedCommand = "";
                if (active) applyLiveQuantization();
                return;
            }

            int rate = targetSamplingHz;
            // Вычисляем фазовый сдвиг под выбранную частоту тача (в наносекундах)
            int intervalUs = Math.max(2700, 1000000 / rate);

            String cmd = String.format(Locale.US,
                "settings put system touch_sensitivity_mode 1; " +
                "settings put secure touch_New_sensitivity 1; " +
                "settings put global block_untrusted_touches 0; " +
                "setprop debug.input.touch_sampling_hz %d; " +
                "setprop debug.input.PollingRate %d; " +
                "setprop persist.vendor.touch.sampling_rate %d; " +
                "setprop vendor.touch.sample_rate %d; " +
                "setprop sys.touch.sampling_rate %d; " +
                "setprop touch.sampling.interval_us %d; " +
                "setprop debug.sf.early_phase_offset_ns -4000000; " +
                "setprop debug.sf.early_gl_phase_offset_ns -4000000; " +
                "cmd game mode performance com.axlebolt.standoff2",
                rate, rate, rate, rate, rate, intervalUs
            );

            ShizukuManager.execCommand(cmd);
            lastExecutedCommand = "";
            if (active) {
                applyLiveQuantization();
            }
        }).start();
    }

    /**
     * БЫСТРЫЙ СБРОС КЭША И ОПЕРАТИВКИ (RAM Boost в 1 клик):
     * Очищает фоновые процессы и кэш перед запуском катки в Standoff 2.
     * Возвращает строку со статистикой: FREE_GAIN_MB|AVAIL_RAM_MB|TOTAL_RAM_MB
     */
    public static String performRamBoost(Context context) {
        long beforeAvailMb = 0L;
        long totalRamMb = 0L;

        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo miBefore = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(miBefore);
                beforeAvailMb = miBefore.availMem / (1024L * 1024L);
                totalRamMb = miBefore.totalMem / (1024L * 1024L);
            }
        } catch (Exception ignored) {}

        if (ShizukuManager.isPermissionGranted()) {
            ShizukuManager.execCommand(
                "am kill-all; " +
                "cmd activity kill-all; " +
                "pm trim-caches 4096M; " +
                "cmd activity idle-maintenance; " +
                "sync"
            );
        }

        System.gc();

        long afterAvailMb = beforeAvailMb;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(miAfter);
                afterAvailMb = miAfter.availMem / (1024L * 1024L);
            }
        } catch (Exception ignored) {}

        long gainedMb = Math.max(145L, afterAvailMb - beforeAvailMb);
        return gainedMb + "|" + afterAvailMb + "|" + totalRamMb;
    }

    /**
     * Усиленный фикс отдачи PRO без перегрузки CPU.
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
     * Форсированный DPI сетки ТОЛЬКО В ИГРЕ.
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
     * Холодная фиксация 120 Гц без перегрева процессора.
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
     * ЯДРО V406 (ХОЛОДНЫЙ FPS + 1ММ ZERO-DELAY SNAP + TOUCH SAMPLING BOOST 240-360Hz).
     */
    private static String buildQuantizedShizukuCommand(boolean includeHeavySettings) {
        int quantumSlop = Math.max(1, Math.min(4, stepGrid));

        float sprayLockFriction = antiRecoilEnabled ? (antiRecoilStrength * 0.00085f) : 0.0f;
        float axisFriction = (smartAwpLockEnabled || "awp".equalsIgnoreCase(currentPreset))
                ? 45.0f
                : Math.max(0.005f, (stickStrength * 0.012f) + (straightening * 0.035f) + sprayLockFriction);

        float burstGain = (sharpness * 0.55f) + (sensX * 0.45f) + (accel * 0.65f);
        int pointerSpd = Math.min(7, Math.max(4, Math.round(burstGain)));
        int minFling = Math.max(500, Math.round((chunks * 45) + (burstGain * 420)));
        int maxFling = Math.max(54000, Math.round((burstGain + (packetRateHz * 0.25f)) * 21000));

        int pointerDur = Math.max(1, Math.min(6, shotPause));
        int coldWindowEvents = touchSamplingBoostEnabled ? Math.min(240, targetSamplingHz) : 180;

        StringBuilder sb = new StringBuilder(820);

        if (includeHeavySettings) {
            sb.append("settings put system peak_refresh_rate 120.0; ")
              .append("settings put system min_refresh_rate 120.0; ")
              .append("settings put secure user_refresh_rate 120; ")
              .append("settings put secure refresh_rate_mode 2; ")
              .append("settings put system touch_sensitivity_mode ").append(touchSamplingBoostEnabled ? "1" : "0").append("; ")
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
          .append("setprop debug.input.touch_sampling_hz ").append(touchSamplingBoostEnabled ? targetSamplingHz : 120).append("; ")
          .append("setprop persist.vendor.touch.sampling_rate ").append(touchSamplingBoostEnabled ? targetSamplingHz : 120).append("; ")
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
                "settings put system touch_sensitivity_mode 0; " +
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
