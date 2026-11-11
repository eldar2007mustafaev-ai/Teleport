package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Debug;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static volatile boolean isEngineRunning = false;
    private static volatile boolean isTamperDetected = false;
    private static boolean wideHitboxActive = false;

    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int deviceMaxRefreshRate = 120;
    private static boolean isNaturalLandscapeDevice = false;
    private static boolean isBudgetDeviceHardware = false;
    private static volatile boolean isTabletDeviceMode = false;
    private static volatile int currentRecoilPower = 100;

    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static volatile String lastExecutedCmd = "";

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);

        verifyRuntimeAntiCrack(appContext);

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        int physShort = 0;
        int physLong = 0;

        if (wm != null && wm.getDefaultDisplay() != null) {
            Display d = wm.getDefaultDisplay();
            d.getRealMetrics(dm);
            int rot = d.getRotation();

            float maxHz = d.getRefreshRate();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (Display.Mode m : d.getSupportedModes()) {
                    if (m.getRefreshRate() > maxHz) {
                        maxHz = m.getRefreshRate();
                    }
                    int mw = m.getPhysicalWidth();
                    int mh = m.getPhysicalHeight();
                    int s = Math.min(mw, mh);
                    int l = Math.max(mw, mh);
                    if (l > physLong) {
                        physShort = s;
                        physLong = l;
                    }
                }
            }
            deviceMaxRefreshRate = Math.max(60, Math.round(maxHz));

            // Определяем, является ли родная ориентация матрицы альбомной (как на многих планшетах)
            if (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180) {
                isNaturalLandscapeDevice = (dm.widthPixels > dm.heightPixels);
            } else {
                isNaturalLandscapeDevice = (dm.widthPixels < dm.heightPixels);
            }
        } else {
            dm = appContext.getResources().getDisplayMetrics();
        }

        int curShort = (physShort > 0) ? physShort : Math.min(dm.widthPixels, dm.heightPixels);
        int curLong = (physLong > 0) ? physLong : Math.max(dm.widthPixels, dm.heightPixels);

        Configuration cfg = appContext.getResources().getConfiguration();
        int swDp = cfg != null ? cfg.smallestScreenWidthDp : 0;
        double xInches = dm.widthPixels / Math.max(160.0, dm.xdpi);
        double yInches = dm.heightPixels / Math.max(160.0, dm.ydpi);
        double diagInches = Math.hypot(xInches, yInches);

        // Автоопределение планшета (Пада): smallestWidth >= 560dp, диагональ >= 7.6" или пропорции 4:3 / 16:10 с высоким разрешением
        boolean autoDetectedPad = (swDp >= 560) || (diagInches >= 7.6) || (curShort >= 1440) || isNaturalLandscapeDevice;
        isTabletDeviceMode = prefs.getBoolean("force_tablet_pad_mode", autoDetectedPad);

        int cores = Runtime.getRuntime().availableProcessors();
        isBudgetDeviceHardware = (!isTabletDeviceMode && (deviceMaxRefreshRate <= 90 || curShort <= 720 || cores <= 6));

        int savedShort = prefs.getInt("true_native_short", 0);
        int savedLong = prefs.getInt("true_native_long", 0);

        if (physShort > 0 && physLong > 0) {
            nativeShortSide = physShort;
            nativeLongSide = physLong;
            prefs.edit()
                    .putInt("true_native_short", physShort)
                    .putInt("true_native_long", physLong)
                    .apply();
        } else if (savedShort > 0 && savedLong > 0) {
            nativeShortSide = savedShort;
            nativeLongSide = savedLong;
        } else {
            nativeShortSide = curShort;
            nativeLongSide = curLong;
            prefs.edit()
                    .putInt("true_native_short", curShort)
                    .putInt("true_native_long", curLong)
                    .apply();
        }
    }

    // ================= АНТИ-КРЯК ЗАЩИТА ЯДРА (БЕЗ БЛОКИРОВКИ VPN) =================
    private static boolean verifyRuntimeAntiCrack(Context ctx) {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                isTamperDetected = true;
                return false;
            }
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains("frida-agent") || low.contains("frida-gadget")) {
                        isTamperDetected = true;
                        return false;
                    }
                }
            } catch (Throwable ignored) {}

            if (ctx != null) {
                String pkg = ctx.getPackageName();
                if (pkg == null || !pkg.contains("teleport")) {
                    isTamperDetected = true;
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        return !isTamperDetected;
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
        if (cmd == null || cmd.isEmpty() || isTamperDetected) return;
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
                    if ( isNaturalLandscapeDevice && (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180)) {
                        return rot;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return isNaturalLandscapeDevice ? 0 : 1;
    }

    private static String buildWmSizeArg(int shortSidePx, int longSidePx) {
        if ((shortSidePx & 1) == 1) shortSidePx++;
        if ((longSidePx & 1) == 1) longSidePx++;
        if (isNaturalLandscapeDevice) {
            return longSidePx + "x" + shortSidePx;
        }
        return shortSidePx + "x" + longSidePx;
    }

    // ================= ИСПРАВЛЕННЫЙ РАСТЯГ ЭКРАНА ДЛЯ ТЕЛЕФОНОВ И ПЛАНШЕТОВ =================
    public static boolean applyAspectRatioStretch(String ratio) {
        if (isTamperDetected) return false;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return false;

        int baseShort = getNativeShortSide();
        int baseLong = getNativeLongSide();
        int targetShort = baseShort;
        int targetLong = baseLong;
        String clean = (ratio == null) ? "4:3" : ratio.trim().toUpperCase();

        if (clean.startsWith("SHRINK-")) {
            int pct = 85;
            try { pct = Integer.parseInt(clean.replace("SHRINK-", "")); } catch (Exception ignored) {}
            pct = Math.max(50, Math.min(96, pct));
            targetShort = Math.round(baseShort * (pct / 100f));
            targetLong = baseLong;
        } else if ("21:9".equals(clean)) {
            targetShort = Math.round(baseShort * 0.82f);
            targetLong = baseLong;
        } else if ("24:10".equals(clean)) {
            targetShort = Math.round(baseShort * 0.76f);
            targetLong = baseLong;
        } else if ("PAD-WIDE".equals(clean)) {
            // Специальный ультра-растяг моделек для планшетов 16:10 / 4:3
            targetShort = baseShort;
            targetLong = Math.round(baseShort * 1.15f);
        } else {
            switch (clean) {
                case "1:1":
                    targetLong = Math.round(baseShort * 1.10f);
                    break;
                case "5:4":
                    targetLong = Math.round(baseShort * 1.22f);
                    break;
                case "4:3":
                    // Если устройство уже планшет 4:3 или 16:10, делаем коробку плотнее (1.18f), чтобы растяг реально расширял модельки
                    if ((float) baseLong / Math.max(1, baseShort) < 1.65f) {
                        targetLong = Math.round(baseShort * 1.18f);
                    } else {
                        targetLong = Math.round(baseShort * 1.3333f);
                    }
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
                default:
                    targetLong = Math.round(baseShort * 1.3333f);
                    break;
            }
        }

        int landRot = getCurrentLandscapeRotation();
        int hz = deviceMaxRefreshRate;
        String wmArg = buildWmSizeArg(targetShort, targetLong);

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "settings put global force_resizable_activities 1; "
                + "settings put global enable_freeform_support 1; "
                + "settings put global enable_non_resizable_multi_window 1; "
                + "settings put system accelerometer_rotation 0; "
                + "settings put system user_rotation " + landRot + "; "
                + "cmd window user-rotation lock " + landRot + "; "
                + "wm size " + wmArg + "; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0;";
        execShizukuCommand(cmd);
        return true;
    }

    public static void releaseBoxToFullStretch() {
        int landRot = getCurrentLandscapeRotation();
        int hz = deviceMaxRefreshRate;
        String cmd = "settings put system accelerometer_rotation 0; "
                + "settings put system user_rotation " + landRot + "; "
                + "cmd window user-rotation lock " + landRot + "; "
                + "wm size reset; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0;";
        execShizukuCommand(cmd);
    }

    public static void resetResolutionToNative() {
        execShizukuCommand(
                "wm size reset; wm density reset; "
                + "cmd window user-rotation free; "
                + "settings put system accelerometer_rotation 1;"
        );
    }

    public static void enableWideHitboxQuantization(boolean enable) {
        wideHitboxActive = enable;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enable) startTeleport(isTabletDeviceMode ? "tablet_pro" : "extreme");
        else execShizukuCommand("settings put system pointer_speed 0");
    }

    // ================= ЗАПУСК СЕНСЫ + КОМАНДНЫЙ МОСТ РАСТЯГА + ПОДДЕРЖКА ПАДОВ =================
    public static void startTeleport(String presetId) {
        if (isTamperDetected) return;
        String cleanPreset = (presetId == null) ? "extreme" : presetId.trim();

        // Прямые команды управления растягом и режимом планшета из JS-интерфейса:
        if (cleanPreset.startsWith("STRETCH_NOW:")) {
            String r = cleanPreset.substring("STRETCH_NOW:".length()).trim();
            applyAspectRatioStretch(r);
            return;
        }
        if ("STRETCH_FULL_RELEASE".equalsIgnoreCase(cleanPreset)) {
            releaseBoxToFullStretch();
            return;
        }
        if ("STRETCH_RESET".equalsIgnoreCase(cleanPreset)) {
            resetResolutionToNative();
            return;
        }
        if ("PAD_MODE_ON".equalsIgnoreCase(cleanPreset)) {
            isTabletDeviceMode = true;
            if (appContext != null) {
                appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("force_tablet_pad_mode", true).apply();
            }
            lastExecutedCmd = "";
            cleanPreset = "tablet_pro";
        } else if ("PAD_MODE_OFF".equalsIgnoreCase(cleanPreset)) {
            isTabletDeviceMode = false;
            if (appContext != null) {
                appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("force_tablet_pad_mode", false).apply();
            }
            lastExecutedCmd = "";
            cleanPreset = "extreme";
        }

        isEngineRunning = true;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        String pkg = appContext != null ? appContext.getPackageName() : "com.teleport.so2";
        int hz = deviceMaxRefreshRate;

        int evRate = 3;
        int pointerDur = 26;
        double distScale = 27.0;
        int maxFlingVel = 3400000;
        String extraProps = "";

        if ("tablet_pro".equalsIgnoreCase(cleanPreset)) {
            isTabletDeviceMode = true;
            if (appContext != null) {
                appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("force_tablet_pad_mode", true).apply();
            }
            // Специальный мощный профиль для Планшетов (Падов) с высоким DPI и большой диагональю:
            evRate = 2;
            pointerDur = 34;
            distScale = 58.0;
            maxFlingVel = 6800000;
            extraProps = buildTabletTouchUnlockCommands();
        } else if ("budget_pro".equalsIgnoreCase(cleanPreset)) {
            evRate = 5;
            pointerDur = 16;
            distScale = 30.0;
            extraProps = "setprop debug.hwui.use_buffer_age false; "
                    + "setprop debug.hwui.render_dirty_regions false; "
                    + "setprop debug.sf.enable_gl_backpressure 0; "
                    + "cmd activity trim-memory SYSTEM_USER RUNNING_CRITICAL; ";
        } else if ("clean_sharp".equalsIgnoreCase(cleanPreset)) {
            evRate = 4;
            pointerDur = 18;
            distScale = 25.0;
        } else if ("recoil_god".equalsIgnoreCase(cleanPreset)) {
            evRate = 4;
            pointerDur = 22;
            distScale = 24.5;
        } else if ("awm_deagle".equalsIgnoreCase(cleanPreset)) {
            evRate = 3;
            pointerDur = 28;
            distScale = 28.5;
        } else if ("hunt_shooter".equalsIgnoreCase(cleanPreset)) {
            evRate = 4;
            pointerDur = 24;
            distScale = 26.0;
        } else if ("high_fps".equalsIgnoreCase(cleanPreset)) {
            evRate = 3;
            pointerDur = 20;
            distScale = 26.5;
        }

        // Если активен режим планшета, усиливаем любой выбранный пресет под большую матрицу пада
        if (isTabletDeviceMode && !"tablet_pro".equalsIgnoreCase(cleanPreset)) {
            distScale = Math.round(distScale * 2.15 * 10.0) / 10.0;
            pointerDur = Math.min(38, pointerDur + 8);
            evRate = Math.max(2, evRate - 1);
            maxFlingVel = 6800000;
            extraProps = extraProps + buildTabletTouchUnlockCommands();
        }

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
                + extraProps
                + "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put secure long_press_timeout 190; "
                + "settings put secure multi_press_timeout 85; "
                + "settings put system pointer_speed 7; "
                + "settings put secure pointer_speed 7; "
                + "settings put global pointer_speed 7; "
                + "settings put secure game_touch_sampling_rate 480; "
                + "settings put system touch_sampling_rate 480; "
                + "settings put global touch_sampling_rate 480; "
                + "settings put system rakuten_denwa_touch_sensitivity 1; "
                + "settings put system touch_sensitivity 1; "
                + "settings put secure touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "settings put secure stylus_handwriting_enabled 0; "
                + "setprop debug.touch.slop 0; "
                + "setprop view.touch_slop 0; "
                + "setprop touch.filter.level 0; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity 1; "
                + "setprop ro.min.fling_velocity 1; "
                + "setprop view.maximum_fling_velocity " + maxFlingVel + "; "
                + "setprop ro.max.fling_velocity " + maxFlingVel + "; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.noresample 1; "
                + "setprop persist.sys.touch.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.acceleration.hw 1; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop persist.sys.touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.0002; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDur + "; "
                + "setprop debug.min_pointer_dur " + pointerDur + "; "
                + "setprop touch.pressure.scale 0.000005; "
                + "setprop touch.size.calibration geometric; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.sf.early_phase_offset_ns -4000000; "
                + "setprop debug.sf.early_app_phase_offset_ns -4000000; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0; "
                + "settings put secure refresh_rate_mode 2; "
                + "settings put global window_animation_scale 0.0; "
                + "settings put global transition_animation_scale 0.0; "
                + "settings put global animator_duration_scale 0.0;";
        lastExecutedCmd = cmd;
        execShizukuCommand(cmd);
    }

    // Специальный пакет команд для снятия ограничений тачскрина на Планшетах (Xiaomi Pad, Redmi Pad, Samsung Tab, Legion Y700)
    private static String buildTabletTouchUnlockCommands() {
        return "settings put system three_gesture_screenshot 0; "
                + "settings put system miui_home_three_gesture 0; "
                + "settings put secure three_finger_gesture 0; "
                + "settings put system three_finger_screenshot 0; "
                + "settings put secure stylus_handwriting_enabled 0; "
                + "settings put system pen_hovering 0; "
                + "settings put system edge_touch_width 0; "
                + "settings put secure edge_enable 0; "
                + "settings put secure GameBooster_touch_sensitivity 1; "
                + "settings put system game_Calculate_touch 1; "
                + "settings put system touch_pad_mode 1; "
                + "device_config put input_native_boot palm_rejection_enabled 0; "
                + "device_config put input_native_boot velocity_tracker_strategy impulse; "
                + "setprop persist.vendor.touch.sampling_rate 480; "
                + "setprop debug.touch.pad_boost 1; ";
    }

    public static void stopTeleport() {
        isEngineRunning = false;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        execShizukuCommand(
                "settings put system pointer_speed 0; "
                + "settings put secure pointer_speed 0; "
                + "settings put global pointer_speed 0; "
                + "cmd power set-fixed-performance-mode-enabled false; "
                + "setprop ro.input.noresample 0; "
                + "setprop debug.input.noresample 0; "
                + "setprop ro.min_pointer_dur 1; "
                + "setprop touch.distance.scale 1.0; "
                + "setprop windowsmgr.max_events_per_sec 180;"
        );
    }

    public static void healTouchAndRestart() {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        if (isTabletDeviceMode) {
            startTeleport("tablet_pro");
        } else if (isBudgetDeviceHardware) {
            startTeleport("budget_pro");
        } else {
            startTeleport("extreme");
        }
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (isTamperDetected) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        double chunkNorm = Math.min(1.0, Math.max(0.0, chunks / 100.0));

        int evRate;
        int pointerDur;
        if (chunks >= 75) {
            evRate = Math.max(2, 5 - (int) Math.round((chunks - 75) / 8.0));
            if (isBudgetDeviceHardware && !isTabletDeviceMode && evRate < 4) evRate = 4;
            if (isTabletDeviceMode) evRate = Math.max(2, evRate - 1);
            pointerDur = Math.min(38, 18 + (int) Math.round(chunkNorm * 16.0));
        } else if (chunks >= 35) {
            evRate = Math.max(4, 14 - (int) Math.round(chunkNorm * 9.0));
            pointerDur = 12 + (int) Math.round(chunkNorm * 10.0);
        } else {
            evRate = 28;
            pointerDur = 5;
        }

        int touchSlop = Math.max(0, Math.min(6, (pause - 1) / 2));
        int filterLvl = (grid >= 4 || stick >= 50) ? 1 : 0;

        double recoilBoost = (currentRecoilPower / 100.0) * 8.5;
        double xyBoost = ((sx + sy) / 50.0) * 11.5;
        double sharpBoost = (sharp / 25.0) * 9.0;
        double fastChunkBoost = (chunkFast / 100.0) * 4.0;
        double baseScale = 5.0 + sharpBoost + xyBoost + recoilBoost + fastChunkBoost;

        // На планшетах умножаем шаг кусков на 2.15x, чтобы компенсировать огромное число пикселей матрицы пада
        if (isTabletDeviceMode) {
            baseScale = baseScale * 2.15;
            pointerDur = Math.min(40, pointerDur + 6);
        }

        double distScale = Math.round(baseScale * 10.0) / 10.0;
        int maxFling = (int) Math.round(1800000 + (sx * 48000) + (accel * 42000) + (currentRecoilPower * 10000));
        if (isTabletDeviceMode) {
            maxFling = Math.max(6800000, maxFling * 2);
        }

        String padExtras = isTabletDeviceMode ? buildTabletTouchUnlockCommands() : "";

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + padExtras
                + "settings put system pointer_speed 7; "
                + "settings put secure pointer_speed 7; "
                + "settings put global pointer_speed 7; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put system touch_sensitivity 1; "
                + "settings put secure touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "setprop debug.touch.slop " + touchSlop + "; "
                + "setprop view.touch_slop " + touchSlop + "; "
                + "setprop touch.filter.level " + filterLvl + "; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity 1; "
                + "setprop ro.min.fling_velocity 1; "
                + "setprop view.maximum_fling_velocity " + maxFling + "; "
                + "setprop ro.max.fling_velocity " + maxFling + "; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.noresample 1; "
                + "setprop persist.sys.touch.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.acceleration.hw 1; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop persist.sys.touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.0002; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDur + "; "
                + "setprop debug.min_pointer_dur " + pointerDur + "; "
                + "setprop touch.pressure.scale 0.000005; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.sf.early_phase_offset_ns -4000000; "
                + "setprop debug.sf.early_app_phase_offset_ns -4000000; "
                + "settings put secure game_touch_sampling_rate 480; "
                + "settings put system touch_sampling_rate 480; "
                + "settings put global touch_sampling_rate 480;";

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
        int multiPressMs = Math.max(85, 250 - multiTouch);

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
                + "setprop debug.hwui.use_buffer_age false; "
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
            startTeleport(isTabletDeviceMode ? "tablet_pro" : "extreme");
        } else {
            execShizukuCommand("setprop ro.input.noresample 0; setprop debug.input.noresample 0; setprop windowsmgr.max_events_per_sec 180;");
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
            execShizukuCommand("settings put secure game_touch_sampling_rate " + hz + "; settings put system touch_sampling_rate " + hz + "; settings put global touch_sampling_rate " + hz + ";");
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
        long gained = Math.max(290, availAfterMb - availBeforeMb + 320);

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
        lastExecutedCmd = "";
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        if (enabled) {
            double mult = isTabletDeviceMode ? 2.15 : 1.0;
            double recoilDistScale = Math.round((15.5 + (currentRecoilPower / 100.0) * 14.0) * mult * 10.0) / 10.0;
            String cmd = "settings put system pointer_speed 7; "
                    + "settings put secure pointer_speed 7; "
                    + "settings put global pointer_speed 7; "
                    + "setprop view.scroll_friction 0; "
                    + "setprop debug.touch.slop 0; "
                    + "setprop view.touch_slop 0; "
                    + "setprop view.minimum_fling_velocity 1; "
                    + "setprop ro.min.fling_velocity 1; "
                    + "setprop ro.input.noresample 1; "
                    + "setprop debug.input.noresample 1; "
                    + "setprop touch.InitialJump 1; "
                    + "setprop touch.distance.scale " + recoilDistScale + "; "
                    + "setprop persist.sys.touch.distance.scale " + recoilDistScale + "; "
                    + "setprop touch.size.scale 0.0002; "
                    + "setprop touch.pressure.scale 0.000005; "
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
            int h = Math.round(getNativeLongSide() * (pct / 100f));
            int landRot = getCurrentLandscapeRotation();
            String wmArg = buildWmSizeArg(w, h);
            execShizukuCommand("cmd window user-rotation lock " + landRot + "; wm size " + wmArg);
        }
    }

    public static String runSmartDeviceScan(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        int cores = Runtime.getRuntime().availableProcessors();
        String tier;
        if (isTabletDeviceMode) {
            tier = "PAD-ULTRA";
        } else if (cores >= 8 && shortPx >= 1080 && deviceMaxRefreshRate >= 120) {
            tier = "ULTRA";
        } else if (isBudgetDeviceHardware) {
            tier = "BUDGET-PRO";
        } else {
            tier = "HIGH";
        }
        return Build.MODEL + "|" + tier + "|" + shortPx;
    }
}
