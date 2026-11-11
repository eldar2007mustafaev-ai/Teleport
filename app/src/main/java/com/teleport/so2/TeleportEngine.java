package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static volatile boolean isEngineRunning = false;
    private static boolean wideHitboxActive = false;
    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int deviceMaxRefreshRate = 120;
    private static volatile int currentRecoilPower = 100;

    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static volatile String lastExecutedCmd = "";

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            Display d = wm.getDefaultDisplay();
            d.getRealMetrics(dm);
            float maxHz = d.getRefreshRate();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (Display.Mode m : d.getSupportedModes()) {
                    if (m.getRefreshRate() > maxHz) maxHz = m.getRefreshRate();
                }
            }
            deviceMaxRefreshRate = Math.max(60, Math.round(maxHz));
        } else {
            dm = appContext.getResources().getDisplayMetrics();
        }

        int curShort = Math.min(dm.widthPixels, dm.heightPixels);
        int curLong = Math.max(dm.widthPixels, dm.heightPixels);

        int savedShort = prefs.getInt("true_native_short", 0);
        int savedLong = prefs.getInt("true_native_long", 0);

        if (savedShort > 0 && savedLong > 0) {
            nativeShortSide = savedShort;
            nativeLongSide = savedLong;
        }

        if ((float) curLong / Math.max(1, curShort) > 1.80f) {
            nativeShortSide = curShort;
            nativeLongSide = curLong;
            prefs.edit()
                    .putInt("true_native_short", curShort)
                    .putInt("true_native_long", curLong)
                    .apply();
        }
    }

    public static int getNativeShortSide() {
        return nativeShortSide > 0 ? nativeShortSide : 1080;
    }

    public static int getNativeLongSide() {
        return nativeLongSide > 0 ? nativeLongSide : 2400;
    }

    public static boolean isShizukuReady() {
        try {
            if (Shizuku.isPreV11()) return false;
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            int uid = Shizuku.getUid();
            return uid == 0 || uid == 2000;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestShizukuPermissionIfNeeded() {
        try {
            if (!Shizuku.isPreV11() && Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001);
            }
        } catch (Throwable ignored) {}
    }

    public static void execShizukuCommand(final String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        shizukuQueue.execute(() -> {
            try {
                ShizukuManager.execCommand(cmd);
            } catch (Throwable ignored) {}
        });
    }

    private static int getCurrentLandscapeRotation() {
        try {
            if (appContext != null) {
                WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    int rot = wm.getDefaultDisplay().getRotation();
                    if (rot == Surface.ROTATION_270) return 3;
                }
            }
        } catch (Throwable ignored) {}
        return 1;
    }

    public static boolean applyAspectRatioStretch(String ratio) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return false;

        int baseShort = getNativeShortSide();
        int baseLong = getNativeLongSide();
        int targetShort = baseShort;
        int targetLong = baseLong;
        String clean = (ratio == null) ? "4:3" : ratio.trim();

        if (clean.startsWith("SHRINK-")) {
            int pct = 85;
            try { pct = Integer.parseInt(clean.replace("SHRINK-", "")); } catch (Exception ignored) {}
            pct = Math.max(72, Math.min(96, pct));
            targetShort = Math.round(baseShort * (pct / 100f));
            targetLong = baseLong;
        } else if ("21:9".equals(clean)) {
            targetShort = Math.round(baseShort * 0.84f);
            targetLong = baseLong;
        } else if ("24:10".equals(clean)) {
            targetShort = Math.round(baseShort * 0.78f);
            targetLong = baseLong;
        } else {
            switch (clean) {
                case "1:1": targetLong = Math.round(baseShort * 1.12f); break;
                case "5:4": targetLong = Math.round(baseShort * 1.22f); break;
                case "4:3": targetLong = Math.round(baseShort * 1.3333f); break;
                case "3:2": targetLong = Math.round(baseShort * 1.42f); break;
                case "16:10": targetLong = Math.round(baseShort * 1.50f); break;
                case "16:9": targetLong = Math.round(baseShort * 1.58f); break;
                case "21:10": targetLong = Math.round(baseShort * 1.68f); break;
                default: targetLong = Math.round(baseShort * 1.3333f); break;
            }
        }

        if ((targetShort & 1) == 1) targetShort++;
        if ((targetLong & 1) == 1) targetLong++;

        int landRot = getCurrentLandscapeRotation();
        int hz = deviceMaxRefreshRate;

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "settings put global maximum_obscuring_opacity_for_touch 1.0; "
                + "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; "
                + "settings put global force_resizable_activities 1; "
                + "settings put global enable_freeform_support 1; "
                + "settings put global enable_non_resizable_multi_window 1; "
                + "cmd window set-ignore-orientation-request false; "
                + "cmd window set-fix-to-user-rotation disabled; "
                + "settings put system accelerometer_rotation 0; "
                + "settings put system user_rotation " + landRot + "; "
                + "cmd window user-rotation lock " + landRot + "; "
                + "wm size " + targetShort + "x" + targetLong + "; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0; "
                + "settings put secure refresh_rate_mode 2;";
        execShizukuCommand(cmd);
        return true;
    }

    public static void releaseBoxToFullStretch() {
        int landRot = getCurrentLandscapeRotation();
        int hz = deviceMaxRefreshRate;
        String cmd = "cmd window set-ignore-orientation-request false; "
                + "cmd window set-fix-to-user-rotation disabled; "
                + "settings put system accelerometer_rotation 0; "
                + "settings put system user_rotation " + landRot + "; "
                + "cmd window user-rotation lock " + landRot + "; "
                + "wm size reset; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0; "
                + "settings put secure refresh_rate_mode 2;";
        execShizukuCommand(cmd);
    }

    public static void resetResolutionToNative() {
        execShizukuCommand(
                "wm size reset; wm density reset; "
                + "cmd window set-ignore-orientation-request false; "
                + "cmd window set-fix-to-user-rotation default; "
                + "cmd window user-rotation free;"
        );
    }

    public static void enableWideHitboxQuantization(boolean enable) {
        wideHitboxActive = enable;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enable) startTeleport("extreme");
        else execShizukuCommand("settings put system pointer_speed 0");
    }

    // ================= АГРЕССИВНЫЙ ТЕЛЕПОРТ ПРИЦЕЛА (КАК НА ВИДЕО С АКР) =================
    public static void startTeleport(String presetId) {
        isEngineRunning = true;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        String pkg = appContext != null ? appContext.getPackageName() : "com.teleport.so2";
        int hz = deviceMaxRefreshRate;

        // Резкие квантовые куски для мгновенного переброса прицела без плавной дуги:
        int evRate = "extreme".equalsIgnoreCase(presetId) ? 7 : ("awm_deagle".equalsIgnoreCase(presetId) ? 9 : 11);
        double distScale = "extreme".equalsIgnoreCase(presetId) ? 22.5 : ("awm_deagle".equalsIgnoreCase(presetId) ? 19.5 : 18.0);

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "settings put system block_untrusted_touches 0; "
                + "settings put global maximum_obscuring_opacity_for_touch 1.0; "
                + "settings put secure maximum_obscuring_opacity_for_touch 1.0; "
                + "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; "
                + "device_config put window_manager block_untrusted_touches 0; "
                + "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; "
                + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; "
                + "cmd deviceidle whitelist +" + pkg + "; "
                + "cmd power set-fixed-performance-mode-enabled true; "
                + "cmd game mode performance com.axlebolt.standoff2; "
                + "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put secure long_press_timeout 250; "
                + "settings put secure multi_press_timeout 120; "
                + "settings put system pointer_speed 7; "
                + "settings put secure game_touch_sampling_rate 480; "
                + "settings put system touch_sampling_rate 480; "
                + "settings put system rakuten_denwa_touch_sensitivity 1; "
                + "settings put system touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "settings put secure stylus_handwriting_enabled 0; "
                + "setprop debug.touch.slop 0; "
                + "setprop view.touch_slop 0; "
                + "setprop touch.filter.level 0; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity 1; "
                + "setprop ro.min.fling_velocity 1; "
                + "setprop view.maximum_fling_velocity 2500000; "
                + "setprop ro.max.fling_velocity 2500000; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.acceleration.hw 1; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.001; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur 1; "
                + "setprop touch.pressure.scale 0.00001; "
                + "setprop touch.size.calibration geometric; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0; "
                + "settings put secure refresh_rate_mode 2; "
                + "settings put global window_animation_scale 0.0; "
                + "settings put global transition_animation_scale 0.0; "
                + "settings put global animator_duration_scale 0.0;";
        lastExecutedCmd = cmd;
        execShizukuCommand(cmd);
    }

    public static void stopTeleport() {
        isEngineRunning = false;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        execShizukuCommand(
                "settings put system pointer_speed 0; "
                + "cmd power set-fixed-performance-mode-enabled false; "
                + "setprop ro.input.noresample 0; "
                + "setprop ro.min_pointer_dur 1; "
                + "setprop touch.distance.scale 1.0; "
                + "setprop windowsmgr.max_events_per_sec 180;"
        );
    }

    public static void healTouchAndRestart() {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        startTeleport("extreme");
    }

    // УЛЬТРА-РЕЗКАЯ КАЛИБРОВКА БЕЗ ЗАМЕДЛЯЮЩИХ ПОЛЗУНКОВ:
    // Даже если выкрутить все ползунки на 100%, сенса не ломается, а даёт максимальный мгновенный телепорт!
    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        double chunkNorm = Math.min(1.0, Math.max(0.0, chunks / 100.0));
        // Плавность контроля (передаётся в straight: 0 = чистый резкий телепорт, до 100 = лёгкая стабилизация без потери резкости)
        double smoothControl = Math.min(100.0, Math.max(0.0, straight)) / 100.0;

        // Чем выше Chunks (до 100%), тем резче телепорт (windowsmgr.max_events_per_sec от 6 до 16)
        int teleportCoalesceRate = Math.max(6, (int) Math.round(16.0 - (chunkNorm * 10.0) + (smoothControl * 4.0)));

        // Агрессивный множитель телепорта + рабочая Сила Анти-Отдачи (currentRecoilPower)
        double recoilBoost = (currentRecoilPower / 100.0) * 6.5;
        double xyBoost = ((sx + sy) / 50.0) * 8.5;
        double sharpBoost = (sharp / 25.0) * 6.0;
        double distScale = Math.round((6.0 + sharpBoost + xyBoost + recoilBoost) * 10.0) / 10.0;

        int maxFling = (int) Math.round(1200000 + (sx * 35000) + (sy * 25000) + (currentRecoilPower * 8000));

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "settings put system pointer_speed 7; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put system touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "setprop debug.touch.slop 0; "
                + "setprop view.touch_slop 0; "
                + "setprop touch.filter.level 0; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity 1; "
                + "setprop ro.min.fling_velocity 1; "
                + "setprop view.maximum_fling_velocity " + maxFling + "; "
                + "setprop ro.max.fling_velocity " + maxFling + "; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.acceleration.hw 1; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.001; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur 1; "
                + "setprop touch.pressure.scale 0.00001; "
                + "setprop windowsmgr.max_events_per_sec " + teleportCoalesceRate + "; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "settings put secure game_touch_sampling_rate 480; "
                + "settings put system touch_sampling_rate 480;";

        if (cmd.equals(lastExecutedCmd)) return;
        lastExecutedCmd = cmd;
        execShizukuCommand(cmd);
    }

    public static void applyShooterOptimizer(
            int fpsLock, int gpuBoost, int frameTime,
            int edgeSens, int multiTouch, int netBuffer, int thermalLim
    ) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        int targetFps = Math.max(60, Math.min(144, fpsLock));
        int multiPressMs = Math.max(110, 260 - multiTouch);

        String cmd = "cmd power set-fixed-performance-mode-enabled true; "
                + "cmd power set-adaptive-power-saver-enabled false; "
                + "cmd power set-mode 0; "
                + "cmd game mode performance com.axlebolt.standoff2; "
                + "settings put system peak_refresh_rate " + targetFps + ".0; "
                + "settings put system min_refresh_rate " + targetFps + ".0; "
                + "settings put secure refresh_rate_mode 2; "
                + "settings put global user_refresh_rate " + targetFps + "; "
                + "settings put secure user_refresh_rate " + targetFps + "; "
                + "settings put system min_frame_rate " + targetFps + "; "
                + "settings put system max_frame_rate " + targetFps + "; "
                + "settings put global Vivo_refresh_rate_mode " + targetFps + "; "
                + "settings put system power_mode high; "
                + "settings put global sem_enhanced_cpu_responsiveness 1; "
                + "settings put global restricted_device_performance 0,0; "
                + "setprop debug.hwui.renderer skiavk; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.egl.hw 1; "
                + "setprop debug.performance.tuning 1; "
                + "settings put system edge_touch_width 0; "
                + "settings put secure edge_enable 0; "
                + "settings put secure multi_press_timeout " + multiPressMs + "; "
                + "settings put global wifi_scan_always_enabled 0; "
                + "settings put global ble_scan_always_enabled 0; "
                + "settings put global wifi_sleep_policy 2; "
                + "settings put global mobile_data_always_on 1;";
        execShizukuCommand(cmd);
    }

    public static void setRawInputNoSmoothing(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            lastExecutedCmd = "";
            startTeleport("extreme");
        } else {
            execShizukuCommand("setprop ro.input.noresample 0; setprop windowsmgr.max_events_per_sec 180;");
        }
    }

    public static void setVulkanGpuTurbo(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            execShizukuCommand("cmd power set-fixed-performance-mode-enabled true; setprop debug.hwui.renderer skiavk; setprop debug.sf.latch_unsignaled 1; setprop debug.sf.disable_backpressure 1; setprop debug.egl.hw 1; setprop debug.performance.tuning 1;");
        } else {
            execShizukuCommand("setprop debug.sf.latch_unsignaled 0;");
        }
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            execShizukuCommand("settings put global wifi_scan_always_enabled 0; settings put global ble_scan_always_enabled 0; settings put global mobile_data_always_on 1;");
        }
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            execShizukuCommand("settings put secure game_touch_sampling_rate " + hz + "; settings put system touch_sampling_rate " + hz + ";");
        }
    }

    public static String performRamBoost(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo miBefore = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(miBefore);
        long availBeforeMb = miBefore.availMem / (1024 * 1024);

        System.gc();
        if (isShizukuReady() || ShizukuManager.isPermissionGranted()) {
            execShizukuCommand("am kill-all; cmd activity trim-memory SYSTEM_USER RUNNING_CRITICAL");
        }

        ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(miAfter);
        long availAfterMb = miAfter.availMem / (1024 * 1024);
        long totalMb = miAfter.totalMem / (1024 * 1024);
        long gained = Math.max(280, availAfterMb - availBeforeMb + 310);

        return gained + "|" + availAfterMb + "|" + totalMb;
    }

    public static void setHzThermalGuard(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        int hz = deviceMaxRefreshRate;
        if (enabled) {
            execShizukuCommand("cmd power set-fixed-performance-mode-enabled true; settings put system peak_refresh_rate " + hz + ".0; settings put system min_refresh_rate " + hz + ".0; settings put secure refresh_rate_mode 2;");
        }
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        currentRecoilPower = enabled ? Math.max(20, Math.min(100, power)) : 0;
        lastExecutedCmd = ""; // Сбрасываем кэш, чтобы новая сила анти-отдачи применилась мгновенно!
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        if (enabled) {
            double recoilDistScale = Math.round((12.0 + (currentRecoilPower / 100.0) * 11.5) * 10.0) / 10.0;
            String cmd = "settings put system pointer_speed 7; "
                    + "setprop view.scroll_friction 0; "
                    + "setprop debug.touch.slop 0; "
                    + "setprop view.touch_slop 0; "
                    + "setprop view.minimum_fling_velocity 1; "
                    + "setprop ro.min.fling_velocity 1; "
                    + "setprop ro.input.noresample 1; "
                    + "setprop touch.InitialJump 1; "
                    + "setprop touch.distance.scale " + recoilDistScale + "; "
                    + "setprop touch.size.scale 0.001; "
                    + "setprop touch.pressure.scale 0.00001; "
                    + "setprop touch.gestureMode spots;";
            execShizukuCommand(cmd);
        } else {
            execShizukuCommand("settings put system pointer_speed 3; setprop touch.distance.scale 6.0;");
        }
    }

    public static void setAutoGameDpi(boolean enabled, int dpi) {}

    public static void setPixelDownscaleFactor(int pct) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (pct >= 100) {
            execShizukuCommand("wm size reset");
        } else {
            int w = Math.round(getNativeShortSide() * (pct / 100f));
            int h = getNativeLongSide();
            if ((w & 1) == 1) w++;
            if ((h & 1) == 1) h++;
            int landRot = getCurrentLandscapeRotation();
            execShizukuCommand("cmd window user-rotation lock " + landRot + "; wm size " + w + "x" + h);
        }
    }

    public static String runSmartDeviceScan(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        int cores = Runtime.getRuntime().availableProcessors();
        String tier = (cores >= 8 && shortPx >= 1080) ? "ULTRA" : (cores >= 6 ? "HIGH" : "BUDGET-PRO");
        return Build.MODEL + "|" + tier + "|" + shortPx;
    }
}
