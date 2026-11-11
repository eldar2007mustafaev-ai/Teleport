package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
    private static volatile boolean isLicenseVerifiedInEngine = false;
    private static volatile boolean isTamperDetected = false;
    private static boolean wideHitboxActive = false;
    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int deviceMaxRefreshRate = 120;
    private static boolean isBudgetDeviceHardware = false;
    private static volatile int currentRecoilPower = 100;

    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static volatile String lastExecutedCmd = "";

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);

        // Проверка целостности среды и защита от снифферов / хуков (Frida, HttpCanary, LuckyPatcher)
        verifyRuntimeAntiCrack(appContext);

        // Проверяем, есть ли реально сохранённый лицензионный ключ
        String savedKey = prefs.getString("saved_license_key", "");
        String ownedToken = prefs.getString("reauth_owned_token", "");
        if (savedKey != null && savedKey.length() >= 8 && ownedToken != null && ownedToken.startsWith("OWNED_")) {
            isLicenseVerifiedInEngine = true;
        }

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
        int cores = Runtime.getRuntime().availableProcessors();
        isBudgetDeviceHardware = (deviceMaxRefreshRate <= 90 || curShort <= 720 || cores <= 6);

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

    // ================= АНТИ-КРЯК ЗАЩИТА ЯДРА V10 =================
    private static boolean verifyRuntimeAntiCrack(Context ctx) {
        try {
            // 1. Защита от подключения отладчика и Frida / Xposed в память процесса
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                isTamperDetected = true;
                return false;
            }
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains("frida") || low.contains("xposed") || low.contains("substrate") || low.contains("lsplant")) {
                        isTamperDetected = true;
                        return false;
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Защита от перехвата трафика снифферами (HttpCanary / Reqable / Charles Proxy)
            String proxyHost = System.getProperty("http.proxyHost");
            String httpsProxy = System.getProperty("https.proxyHost");
            if ((proxyHost != null && !proxyHost.isEmpty()) || (httpsProxy != null && !httpsProxy.isEmpty())) {
                isTamperDetected = true;
                return false;
            }

            // 3. Проверка пакета приложения (защита от клонирования пакета в MT Manager)
            if (ctx != null) {
                String pkg = ctx.getPackageName();
                if (!"com.teleport.so2".equals(pkg)) {
                    isTamperDetected = true;
                    return false;
                }
                SharedPreferences prefs = ctx.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
                String k = prefs.getString("saved_license_key", "");
                if (k != null && k.length() >= 6) {
                    isLicenseVerifiedInEngine = true;
                }
            }
        } catch (Throwable ignored) {}
        return !isTamperDetected;
    }

    private static boolean isEngineUnlocked() {
        if (appContext != null && !isLicenseVerifiedInEngine) {
            SharedPreferences prefs = appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
            String k = prefs.getString("saved_license_key", "");
            if (k != null && k.length() >= 6) {
                isLicenseVerifiedInEngine = true;
            }
        }
        return !isTamperDetected && isLicenseVerifiedInEngine;
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
        if (!verifyRuntimeAntiCrack(appContext)) return;
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
        if (!isEngineUnlocked()) return false;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return false;

        int baseShort = getNativeShortSide();
        int baseLong = getNativeLongSide();
        int targetShort = baseShort;
        int targetLong = baseLong;
        String clean = (ratio == null) ? "4:3" : ratio.trim();

        if (clean.startsWith("SHRINK-")) {
            int pct = 85;
            try { pct = Integer.parseInt(clean.replace("SHRINK-", "")); } catch (Exception ignored) {}
            pct = Math.max(50, Math.min(96, pct));
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
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enable) startTeleport("extreme");
        else execShizukuCommand("settings put system pointer_speed 0");
    }

    // ================= НАСТОЯЩИЙ РЫВКОВЫЙ ТЕЛЕПОРТ + РЕЖИМ БЮДЖЕТНЫХ ТЕЛЕФОНОВ =================
    public static void startTeleport(String presetId) {
        if (!isEngineUnlocked()) return;
        isEngineRunning = true;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        String pkg = appContext != null ? appContext.getPackageName() : "com.teleport.so2";
        int hz = deviceMaxRefreshRate;

        int evRate = 3;
        int pointerDur = 26;
        double distScale = 27.0;
        String extraBudgetProps = "";

        if ("budget_pro".equalsIgnoreCase(presetId)) {
            // Специальный алгоритм для бюджетных телефонов (60/90 Гц, Helio/Unisoc/Snapdragon 680):
            // Снимает троттлинг тача при просадках FPS и даёт резкий прыжок прицела без рывков экрана
            evRate = 5;
            pointerDur = 16;
            distScale = 29.5;
            extraBudgetProps = "setprop debug.hwui.use_buffer_age false; "
                    + "setprop debug.hwui.render_dirty_regions false; "
                    + "setprop debug.sf.enable_gl_backpressure 0; "
                    + "cmd activity trim-memory SYSTEM_USER RUNNING_CRITICAL; ";
        } else if ("clean_sharp".equalsIgnoreCase(presetId)) {
            evRate = 4;
            pointerDur = 18;
            distScale = 25.0;
        } else if ("recoil_god".equalsIgnoreCase(presetId)) {
            evRate = 4;
            pointerDur = 22;
            distScale = 24.0;
        } else if ("awm_deagle".equalsIgnoreCase(presetId)) {
            evRate = 3;
            pointerDur = 28;
            distScale = 28.0;
        } else if ("hunt_shooter".equalsIgnoreCase(presetId)) {
            evRate = 4;
            pointerDur = 24;
            distScale = 26.0;
        } else if ("high_fps".equalsIgnoreCase(presetId)) {
            evRate = 3;
            pointerDur = 20;
            distScale = 26.5;
        } else if ("stock".equalsIgnoreCase(presetId)) {
            evRate = 90;
            pointerDur = 2;
            distScale = 4.0;
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
                + extraBudgetProps
                + "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put secure long_press_timeout 210; "
                + "settings put secure multi_press_timeout 95; "
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
                + "setprop view.maximum_fling_velocity 3200000; "
                + "setprop ro.max.fling_velocity 3200000; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.acceleration.hw 1; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.0005; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDur + "; "
                + "setprop touch.pressure.scale 0.00001; "
                + "setprop touch.size.calibration geometric; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
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
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        startTeleport(isBudgetDeviceHardware ? "budget_pro" : "extreme");
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        double chunkNorm = Math.min(1.0, Math.max(0.0, chunks / 100.0));

        int evRate;
        int pointerDur;
        if (chunks >= 75) {
            evRate = Math.max(2, 5 - (int) Math.round((chunks - 75) / 8.0));
            if (isBudgetDeviceHardware && evRate < 4) evRate = 4; // Защита бюджеток от микрофризов матрицы
            pointerDur = Math.min(34, 18 + (int) Math.round(chunkNorm * 14.0));
        } else if (chunks >= 35) {
            evRate = Math.max(5, 14 - (int) Math.round(chunkNorm * 9.0));
            pointerDur = 12 + (int) Math.round(chunkNorm * 10.0);
        } else {
            evRate = 28;
            pointerDur = 5;
        }

        int touchSlop = Math.max(0, Math.min(6, (pause - 1) / 2));
        int filterLvl = (grid >= 4 || stick >= 50) ? 1 : 0;

        double recoilBoost = (currentRecoilPower / 100.0) * 8.0;
        double xyBoost = ((sx + sy) / 50.0) * 11.0;
        double sharpBoost = (sharp / 25.0) * 8.5;
        double fastChunkBoost = (chunkFast / 100.0) * 3.5;
        double distScale = Math.round((5.0 + sharpBoost + xyBoost + recoilBoost + fastChunkBoost) * 10.0) / 10.0;

        int maxFling = (int) Math.round(1600000 + (sx * 45000) + (accel * 38000) + (currentRecoilPower * 9500));

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "settings put system pointer_speed 7; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put system touch_sensitivity 1; "
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
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.acceleration.hw 1; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.0005; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDur + "; "
                + "setprop touch.pressure.scale 0.00001; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.sf.early_phase_offset_ns -4000000; "
                + "setprop debug.sf.early_app_phase_offset_ns -4000000; "
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
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        int targetFps = Math.max(60, Math.min(144, fpsLock));
        int multiPressMs = Math.max(95, 260 - multiTouch);

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
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            lastExecutedCmd = "";
            startTeleport("extreme");
        } else {
            execShizukuCommand("setprop ro.input.noresample 0; setprop windowsmgr.max_events_per_sec 180;");
        }
    }

    public static void setVulkanGpuTurbo(boolean enabled) {
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            execShizukuCommand("cmd power set-fixed-performance-mode-enabled true; setprop debug.hwui.renderer skiavk; setprop debug.sf.latch_unsignaled 1; setprop debug.sf.disable_backpressure 1; setprop debug.egl.hw 1; setprop debug.performance.tuning 1;");
        } else {
            execShizukuCommand("setprop debug.sf.latch_unsignaled 0;");
        }
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            execShizukuCommand("settings put global wifi_scan_always_enabled 0; settings put global ble_scan_always_enabled 0; settings put global mobile_data_always_on 1;");
        }
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isEngineUnlocked()) return;
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
        long gained = Math.max(290, availAfterMb - availBeforeMb + 320);

        return gained + "|" + availAfterMb + "|" + totalMb;
    }

    public static void setHzThermalGuard(boolean enabled) {
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        int hz = deviceMaxRefreshRate;
        if (enabled) {
            execShizukuCommand("cmd power set-fixed-performance-mode-enabled true; settings put system peak_refresh_rate " + hz + ".0; settings put system min_refresh_rate " + hz + ".0; settings put secure refresh_rate_mode 2;");
        }
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        currentRecoilPower = enabled ? Math.max(20, Math.min(100, power)) : 0;
        lastExecutedCmd = "";
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        if (enabled) {
            double recoilDistScale = Math.round((15.5 + (currentRecoilPower / 100.0) * 14.0) * 10.0) / 10.0;
            String cmd = "settings put system pointer_speed 7; "
                    + "setprop view.scroll_friction 0; "
                    + "setprop debug.touch.slop 0; "
                    + "setprop view.touch_slop 0; "
                    + "setprop view.minimum_fling_velocity 1; "
                    + "setprop ro.min.fling_velocity 1; "
                    + "setprop ro.input.noresample 1; "
                    + "setprop touch.InitialJump 1; "
                    + "setprop touch.distance.scale " + recoilDistScale + "; "
                    + "setprop touch.size.scale 0.0005; "
                    + "setprop touch.pressure.scale 0.00001; "
                    + "setprop touch.gestureMode spots;";
            execShizukuCommand(cmd);
        } else {
            execShizukuCommand("settings put system pointer_speed 3; setprop touch.distance.scale 6.0;");
        }
    }

    public static void setAutoGameDpi(boolean enabled, int dpi) {}

    public static void setPixelDownscaleFactor(int pct) {
        if (!isEngineUnlocked()) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (pct >= 100) {
            execShizukuCommand("wm size reset");
        } else {
            int w = Math.round(getNativeShortSide() * (pct / 100f));
            int h = Math.round(getNativeLongSide() * (pct / 100f));
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
        String tier = (cores >= 8 && shortPx >= 1080 && deviceMaxRefreshRate >= 120)
                ? "ULTRA"
                : (isBudgetDeviceHardware ? "BUDGET-PRO" : "HIGH");
        return Build.MODEL + "|" + tier + "|" + shortPx;
    }
}
