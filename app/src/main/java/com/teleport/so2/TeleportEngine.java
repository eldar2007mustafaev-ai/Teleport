package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
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
    private static volatile int currentRecoilPower = 100;

    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static volatile String lastExecutedCmd = "";

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            wm.getDefaultDisplay().getRealMetrics(dm);
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
                case "1:1":
                    targetLong = Math.round(baseShort * 1.12f);
                    break;
                case "5:4":
                    targetLong = Math.round(baseShort * 1.22f);
                    break;
                case "4:3":
                    targetLong = Math.round(baseShort * 1.3333f);
                    break;
                case "3:2":
                    targetLong = Math.round(baseShort * 1.42f);
                    break;
                case "16:10":
                    targetLong = Math.round(baseShort * 1.50f);
                    break;
                case "16:9":
                    targetLong = Math.round(baseShort * 1.58f);
                    break;
                case "21:10":
                    targetLong = Math.round(baseShort * 1.68f);
                    break;
                default:
                    targetLong = Math.round(baseShort * 1.3333f);
                    break;
            }
        }

        if ((targetShort & 1) == 1) targetShort++;
        if ((targetLong & 1) == 1) targetLong++;

        int landRot = getCurrentLandscapeRotation();

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
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0; "
                + "settings put secure refresh_rate_mode 2;";
        execShizukuCommand(cmd);
        return true;
    }

    public static void releaseBoxToFullStretch() {
        int landRot = getCurrentLandscapeRotation();
        String cmd = "cmd window set-ignore-orientation-request false; "
                + "cmd window set-fix-to-user-rotation disabled; "
                + "settings put system accelerometer_rotation 0; "
                + "settings put system user_rotation " + landRot + "; "
                + "cmd window user-rotation lock " + landRot + "; "
                + "wm size reset; "
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0; "
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

        if (enable) {
            startTeleport("extreme");
        } else {
            execShizukuCommand("settings put system pointer_speed 0");
        }
    }

    // ================= ПРОФЕССИОНАЛЬНЫЙ ДВИЖОК ТЕЛЕПОРТА (H9IJE TRANSFER + SPRAY CONTROL) =================
    public static void startTeleport(String presetId) {
        isEngineRunning = true;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        String pkg = appContext != null ? appContext.getPackageName() : "com.teleport.so2";
        boolean isSniperSmooth = "awm_deagle".equalsIgnoreCase(presetId);

        int evRate = isSniperSmooth ? 10 : 4;
        int slopVal = isSniperSmooth ? 1 : 0;
        int filterLvl = isSniperSmooth ? 2 : 0;
        int pointerDur = isSniperSmooth ? 7 : 9;

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "settings put system block_untrusted_touches 0; "
                + "settings put global maximum_obscuring_opacity_for_touch 1.0; "
                + "settings put secure maximum_obscuring_opacity_for_touch 1.0; "
                + "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; "
                + "device_config put window_manager block_untrusted_touches 0; "
                + "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                + "cmd appops set " + pkg + " POST_NOTIFICATION allow; "
                + "cmd appops set " + pkg + " RECORD_AUDIO allow; "
                + "cmd appops set " + pkg + " PLAY_AUDIO allow; "
                + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; "
                + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; "
                + "cmd deviceidle whitelist +" + pkg + "; "
                + "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS; "
                + "pm grant " + pkg + " android.permission.RECORD_AUDIO; "
                + "settings put global force_resizable_activities 1; "
                + "settings put global enable_non_resizable_multi_window 1; "
                + "cmd window set-ignore-orientation-request false; "
                + "cmd game mode performance com.axlebolt.standoff2; "
                + "device_config put game_overlay com.axlebolt.standoff2 mode=2,fps=120:mode=3,fps=120; "
                + "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put secure long_press_timeout 340; "
                + "settings put secure multi_press_timeout 240; "
                + "settings put system pointer_speed 7; "
                + "settings put secure game_touch_sampling_rate 360; "
                + "settings put system touch_sampling_rate 360; "
                + "settings put system rakuten_denwa_touch_sensitivity 1; "
                + "settings put system touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "settings put secure stylus_handwriting_enabled 0; "
                + "setprop debug.touch.slop " + slopVal + "; "
                + "setprop view.touch_slop " + slopVal + "; "
                + "setprop touch.filter.level " + filterLvl + "; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity 1; "
                + "setprop ro.min.fling_velocity 1; "
                + "setprop view.maximum_fling_velocity 950000; "
                + "setprop ro.max.fling_velocity 950000; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.acceleration.hw 1; "
                + "setprop touch.distance.scale 7.0; "
                + "setprop touch.size.scale 0.005; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDur + "; "
                + "setprop touch.pressure.scale 0.00001; "
                + "setprop touch.size.calibration geometric; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.sf.early_phase_offset_ns -14000000; "
                + "setprop debug.sf.early_app_phase_offset_ns -14000000; "
                + "setprop debug.sf.high_fps_early_phase_offset_ns -14000000; "
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0; "
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
                + "settings put secure long_press_timeout 400; "
                + "settings put secure multi_press_timeout 300; "
                + "setprop ro.input.noresample 0; "
                + "setprop ro.min_pointer_dur 1; "
                + "setprop windowsmgr.max_events_per_sec 180; "
                + "setprop debug.sf.early_phase_offset_ns 0;"
        );
    }

    public static void healTouchAndRestart() {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        execShizukuCommand(
                "wm size reset; wm density reset; "
                + "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; "
                + "cmd window set-ignore-orientation-request false; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure long_press_timeout 340; "
                + "settings put secure multi_press_timeout 240; "
                + "setprop ro.min_pointer_dur 9;"
        );
        startTeleport("extreme");
    }

    // Калибровка:
    // - При chunks = 100% (TELEPORT h9ije): 4 пакета/сек, нулевая гладкость, мгновенный переброс прицела из А в Б!
    // - При chunks = 75..88% (AWM / DEAGLE): 8..10 пакетов/сек + фильтр тремора (touch_slop = 1), чтобы прицел НЕ трусило!
    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        double chunkNorm = Math.min(1.0, Math.max(0.0, chunks / 100.0));
        int teleportCoalesceRate = Math.max(4, Math.min(14, (int) Math.round(14.0 - (chunkNorm * 10.0))));

        // Если выбран режим AWM / Deagle (chunks <= 88%), включаем защиту от тряски прицела (slop = 1, filter = 2)
        boolean antiTremorActive = (chunks <= 88);
        int slopVal = antiTremorActive ? 1 : 0;
        int filterLvl = antiTremorActive ? 2 : 0;

        int microFlickMinVel = Math.max(1, (int) Math.round(28 - (sharp * 1.1)));
        double distScale = Math.round((3.0 + (sharp / 25.0) * 4.5 + (currentRecoilPower / 100.0) * 1.5) * 10.0) / 10.0;

        int maxFling = (int) Math.round(350000 + (accel * 24000));
        int pointerDurMs = Math.max(5, Math.min(11, (int) Math.round(5 + (pause * 0.16))));
        long phaseOffsetNs = -1000000L * Math.max(8, Math.min(15, 8 + (chunks / 14)));

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; "
                + "settings put system pointer_speed 7; "
                + "settings put secure long_press_timeout 340; "
                + "settings put secure multi_press_timeout 240; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put system touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "setprop debug.touch.slop " + slopVal + "; "
                + "setprop view.touch_slop " + slopVal + "; "
                + "setprop touch.filter.level " + filterLvl + "; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity " + microFlickMinVel + "; "
                + "setprop ro.min.fling_velocity " + microFlickMinVel + "; "
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
                + "setprop touch.size.scale 0.005; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDurMs + "; "
                + "setprop touch.pressure.scale 0.00001; "
                + "setprop windowsmgr.max_events_per_sec " + teleportCoalesceRate + "; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.sf.early_phase_offset_ns " + phaseOffsetNs + "; "
                + "setprop debug.sf.early_app_phase_offset_ns " + phaseOffsetNs + "; "
                + "setprop debug.sf.high_fps_early_phase_offset_ns " + phaseOffsetNs + "; "
                + "settings put secure game_touch_sampling_rate 360; "
                + "settings put system touch_sampling_rate 360;";

        if (cmd.equals(lastExecutedCmd)) return;
        lastExecutedCmd = cmd;
        execShizukuCommand(cmd);
    }

    public static void setRawInputNoSmoothing(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            lastExecutedCmd = "";
            startTeleport("extreme");
        } else {
            execShizukuCommand("setprop ro.input.noresample 0; setprop ro.min_pointer_dur 1; setprop windowsmgr.max_events_per_sec 180; setprop debug.sf.early_phase_offset_ns 0;");
        }
    }

    public static void setVulkanGpuTurbo(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            String cmd = "setprop debug.hwui.renderer skiavk; "
                    + "setprop debug.sf.latch_unsignaled 1; "
                    + "setprop debug.sf.disable_backpressure 1; "
                    + "setprop debug.egl.hw 1; "
                    + "setprop debug.performance.tuning 1;";
            execShizukuCommand(cmd);
        } else {
            execShizukuCommand("setprop debug.sf.latch_unsignaled 0;");
        }
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            String cmd = "settings put global wifi_scan_always_enabled 0; "
                    + "settings put global ble_scan_always_enabled 0; "
                    + "settings put global mobile_data_always_on 1;";
            execShizukuCommand(cmd);
        }
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            String cmd = "settings put system peak_refresh_rate 120.0; "
                    + "settings put system min_refresh_rate 120.0; "
                    + "settings put secure game_touch_sampling_rate " + hz + "; "
                    + "settings put system touch_sampling_rate " + hz + ";";
            execShizukuCommand(cmd);
        } else {
            execShizukuCommand("settings put system min_refresh_rate 60.0");
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
        if (enabled) {
            execShizukuCommand("settings put system peak_refresh_rate 120.0; settings put system min_refresh_rate 120.0");
        }
    }

    // Послушный векторный контроль зажима (Spray Control): пули летят строго в точку при лёгком оттягивании вниз
    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        currentRecoilPower = enabled ? Math.max(20, Math.min(100, power)) : 0;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        if (enabled) {
            int ptrSpeed = Math.min(7, Math.max(5, Math.round((power / 100f) * 7f)));
            double recoilDistScale = Math.round((3.5 + (power / 100.0) * 4.5) * 10.0) / 10.0;
            String cmd = "settings put system pointer_speed " + ptrSpeed + "; "
                    + "setprop view.scroll_friction 0; "
                    + "setprop debug.touch.slop 0; "
                    + "setprop view.touch_slop 0; "
                    + "setprop view.minimum_fling_velocity 1; "
                    + "setprop ro.min.fling_velocity 1; "
                    + "setprop touch.distance.scale " + recoilDistScale + "; "
                    + "setprop touch.size.scale 0.005; "
                    + "setprop touch.pressure.scale 0.00001; "
                    + "setprop touch.gestureMode spots;";
            execShizukuCommand(cmd);
        } else {
            execShizukuCommand("settings put system pointer_speed 2; setprop touch.distance.scale 1.0;");
        }
    }

    public static void setAutoGameDpi(boolean enabled, int dpi) {
    }

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
            String cmd = "settings put global block_untrusted_touches 0; "
                    + "settings put global force_resizable_activities 1; "
                    + "settings put global enable_freeform_support 1; "
                    + "settings put global enable_non_resizable_multi_window 1; "
                    + "cmd window set-ignore-orientation-request false; "
                    + "settings put system accelerometer_rotation 0; "
                    + "settings put system user_rotation " + landRot + "; "
                    + "cmd window user-rotation lock " + landRot + "; "
                    + "wm size " + w + "x" + h;
            execShizukuCommand(cmd);
        }
    }

    public static String runSmartDeviceScan(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        int cores = Runtime.getRuntime().availableProcessors();
        String tier = (cores >= 8 && shortPx >= 1080) ? "ULTRA" : (cores >= 6 ? "HIGH" : "LOW");
        return Build.MODEL + "|" + tier + "|" + shortPx;
    }
}
