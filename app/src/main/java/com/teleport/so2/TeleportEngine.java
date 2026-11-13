package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Debug;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static volatile boolean isTamperDetected = false;
    private static volatile String verifiedSessionKey = "";

    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int deviceMaxRefreshRate = 120;
    private static boolean isNaturalLandscapeDevice = false;
    private static boolean isBudgetDeviceHardware = false;
    private static volatile boolean isTabletDeviceMode = false;
    private static volatile int currentRecoilPower = 100;

    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static volatile String lastExecutedCmd = "";

    // XOR-обфускатор строк, чтобы в classes.dex не было открытых строк
    private static String d(int[] enc) {
        char[] out = new char[enc.length];
        for (int i = 0; i < enc.length; i++) {
            out[i] = (char) (enc[i] ^ (0x5A + (i & 0x0F)));
        }
        return new String(out);
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);

        verifyRuntimeAntiCrack(appContext);
        autoDetectSavedLicenseKey();

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
                    if (m.getRefreshRate() > maxHz) maxHz = m.getRefreshRate();
                    int s = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                    int l = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                    if (l > physLong) {
                        physShort = s;
                        physLong = l;
                    }
                }
            }
            deviceMaxRefreshRate = Math.max(60, Math.round(maxHz));
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
        double diagInches = Math.hypot(
                dm.widthPixels / Math.max(160.0, dm.xdpi),
                dm.heightPixels / Math.max(160.0, dm.ydpi)
        );

        boolean autoDetectedPad = (swDp >= 560) || (diagInches >= 7.6) || (curShort >= 1440) || isNaturalLandscapeDevice;
        isTabletDeviceMode = prefs.getBoolean("force_tablet_pad_mode", autoDetectedPad);

        int cores = Runtime.getRuntime().availableProcessors();
        String modelUpper = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        boolean isSamsungASeries = modelUpper.contains("SM-A") || modelUpper.contains("A57") || modelUpper.contains("A55") || modelUpper.contains("A54") || modelUpper.contains("A35") || modelUpper.contains("A34");
        isBudgetDeviceHardware = (!isTabletDeviceMode && (isSamsungASeries || deviceMaxRefreshRate <= 90 || curShort <= 720 || cores <= 6));

        int savedShort = prefs.getInt("true_native_short", 0);
        int savedLong = prefs.getInt("true_native_long", 0);

        if (physShort > 0 && physLong > 0) {
            nativeShortSide = physShort;
            nativeLongSide = physLong;
            prefs.edit().putInt("true_native_short", physShort).putInt("true_native_long", physLong).apply();
        } else if (savedShort > 0 && savedLong > 0) {
            nativeShortSide = savedShort;
            nativeLongSide = savedLong;
        } else {
            nativeShortSide = curShort;
            nativeLongSide = curLong;
            prefs.edit().putInt("true_native_short", curShort).putInt("true_native_long", curLong).apply();
        }
    }

    private static void autoDetectSavedLicenseKey() {
        if (appContext == null) return;
        try {
            File prefsDir = new File(appContext.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = prefsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.endsWith(".xml")) {
                        String prefName = name.substring(0, name.length() - 4);
                        SharedPreferences sp = appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                            Object v = entry.getValue();
                            if (v instanceof String) {
                                String s = ((String) v).trim().toUpperCase();
                                if (s.startsWith("TP") && s.length() >= 6 && !s.contains(" ")) {
                                    verifiedSessionKey = s;
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

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
                    if (low.contains("frida") || low.contains("xposed")) {
                        isTamperDetected = true;
                        return false;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return !isTamperDetected;
    }

    public static int getNativeShortSide() { return nativeShortSide > 0 ? nativeShortSide : 1080; }
    public static int getNativeLongSide() { return nativeLongSide > 0 ? nativeLongSide : 2400; }

    public static boolean isShizukuReady() {
        try {
            if (Shizuku.isPreV11() || !Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false;
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
        if (!SecurityShield.isSessionCryptographicallyValid(appContext)) return;
        if (cmd == null || cmd.isEmpty() || isTamperDetected) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        shizukuQueue.execute(() -> {
            try {
                if (verifiedSessionKey == null || verifiedSessionKey.length() < 5) {
                    autoDetectSavedLicenseKey();
                }
                // Если обошли экран авторизации и ключа нет — режем доступ к Shizuku
                if (verifiedSessionKey == null || verifiedSessionKey.length() < 5) {
                    ShizukuManager.execCommand("settings put system pointer_speed 0");
                    return;
                }
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
                    if (isNaturalLandscapeDevice && (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180)) {
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
            targetShort = baseShort;
            targetLong = Math.round(baseShort * 1.15f);
        } else {
            switch (clean) {
                case "1:1": targetLong = Math.round(baseShort * 1.10f); break;
                case "5:4": targetLong = Math.round(baseShort * 1.22f); break;
                case "4:3":
                    if ((float) baseLong / Math.max(1, baseShort) < 1.65f) {
                        targetLong = Math.round(baseShort * 1.18f);
                    } else {
                        targetLong = Math.round(baseShort * 1.3333f);
                    }
                    break;
                case "3:2": targetLong = Math.round(baseShort * 1.42f); break;
                case "16:10": targetLong = Math.round(baseShort * 1.50f); break;
                case "16:9": targetLong = Math.round(baseShort * 1.58f); break;
                default: targetLong = Math.round(baseShort * 1.3333f); break;
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
        execShizukuCommand("wm size reset; wm density reset; cmd window user-rotation free; settings put system accelerometer_rotation 1;");
    }

    public static void enableWideHitboxQuantization(boolean enable) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enable) startTeleport(isTabletDeviceMode ? "tablet_pro" : "extreme");
        else execShizukuCommand("settings put system pointer_speed 0");
    }

    // ================= ПОЛНАЯ ОПТИМИЗАЦИЯ БЕЗ ПРОСАДОК FPS НА SAMSUNG A57 И БЮДЖЕТКАХ =================
    public static void applyUltimateSystemTweaks() {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        int hz = deviceMaxRefreshRate;

        String cmd =
                "settings put global force_hw_accel 1; "
                + "settings put global disable_window_blurs 1; "
                + "settings put secure notification_bubbles 0; "
                + "settings put secure game_auto_temperature_control 0; "
                + "settings put global sem_enhanced_cpu_responsiveness 1; "
                + "settings put global restricted_device_performance 0,0; "
                + "cmd game mode performance com.axlebolt.standoff2; "
                + "setprop debug.renderengine.multithread 1; "
                + "setprop debug.hwui.render_dirty_regions true; "
                + "setprop debug.hwui.render_ahead 1; "
                + "setprop debug.hwui.texture_cache_size 72; "
                + "setprop debug.hwui.layer_cache_size 48; "
                + "setprop debug.hwui.path_cache_size 32; "
                + "setprop debug.hwui.shape_cache_size 4; "
                + "setprop debug.hwui.gradient_cache_size 2; "
                + "setprop debug.hwui.drop_shadow_cache_size 6; "
                + "setprop debug.hwui.patch_cache_size 512; "
                + "setprop debug.hwui.texture_cache_flush_rate 0.6; "
                + "setprop debug.egl.preserve_context 1; "
                + "setprop debug.egl.shader_cache 1; "
                + "setprop debug.egl.force_msaa false; "
                + "setprop debug.egl.swapinterval 0; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.sf.gpu_comp_tiling 1; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.set_idle_timer_ms 0; "
                + "setprop debug.enable.gpu.debuglayers 0; "
                + "settings put global enable_gpu_debug_layers 0; "
                + "setprop debug.egl.traceGpuCompletion 0; "
                + "settings put global enable_opengl_traces 0; "
                + "setprop debug.hwui.show_layers_updates 0; "
                + "setprop debug.hwui.show_dirty_regions 0; "
                + "setprop debug.hwui.overdraw false; "
                + "setprop debug.hwui.profile false; "
                + "setprop debug.atrace.tags.enableflags 0; "
                + "setprop debug.sf.showfps 0; "
                + "setprop debug.sf.showupdates 0; "
                + "setprop debug.sf.dump 0; "
                + "setprop debug.choreographer.profile false; "
                + "settings put global debug_gpu_overdraw 0; "
                + "settings put system show_touches 0; "
                + "settings put system pointer_location 0; "
                + "settings put global window_animation_scale 0.0; "
                + "settings put global transition_animation_scale 0.0; "
                + "settings put global animator_duration_scale 0.0; "
                + "settings put global captive_portal_mode 0; "
                + "settings put global wifi_scan_throttle_enabled 1; "
                + "settings put global wifi_scan_always_enabled 0; "
                + "settings put global ble_scan_always_enabled 0; "
                + "settings put global wifi_sleep_policy 2; "
                + "settings put global mobile_data_always_on 1; "
                + "settings put global tcp_default_init_rwnd 60; "
                + "cmd device_config put activity_manager use_compaction true; "
                + "cmd device_config put activity_manager_native_boot use_freezer true; "
                + "cmd device_config put activity_manager max_cached_processes 16; "
                + "settings put global cached_apps_freezer 1; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0; "
                + "pm trim-caches 512M;";

        execShizukuCommand(cmd);
    }

    // ================= СЕНСА: ВЗРЫВНОЙ СВАЙП + МГНОВЕННАЯ ОСТАНОВКА (1 МС DEAD-STOP) =================
    public static void startTeleport(String presetId) {
        if (isTamperDetected) return;
        String cleanPreset = (presetId == null) ? "extreme" : presetId.trim();

        if (cleanPreset.startsWith("AUTH_KEY:")) {
            verifiedSessionKey = cleanPreset.substring("AUTH_KEY:".length()).trim().toUpperCase();
            if (appContext != null) {
                appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                        .edit().putString("saved_license_key", verifiedSessionKey).apply();
            }
            return;
        }
        if ("ULTIMATE_TWEAKS_1CLICK".equalsIgnoreCase(cleanPreset)) {
            applyUltimateSystemTweaks();
            return;
        }
        if (cleanPreset.startsWith("STRETCH_NOW:")) {
            applyAspectRatioStretch(cleanPreset.substring("STRETCH_NOW:".length()).trim());
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

        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        String pkg = appContext != null ? appContext.getPackageName() : "com.teleport.so2";
        int hz = deviceMaxRefreshRate;

        // 1. ТЕЛЕПОРТ УЛЬТРА (Резкий переход + Мгновенная остановка пальца за 1 мс без инерции!)
        int evRate = 300;        // Высокая частота диспетчера = 0 просадок FPS при свайпе!
        int pointerDur = 1;      // 1 мс = прицел останавливается В ТУ ЖЕ МИЛЛИСЕКУНДУ, как встал палец!
        int minFling = 850000;   // Гасит остаточное скольжение (инерцию) после остановки пальца!
        int maxFlingVel = 9800000;
        double distScale = 68.0; // Взрывной шаг кусков при самом свайпе!
        String extraProps = buildLightFpsGuardCommands();

        if ("tablet_pro".equalsIgnoreCase(cleanPreset)) {
            isTabletDeviceMode = true;
            if (appContext != null) {
                appContext.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("force_tablet_pad_mode", true).apply();
            }
            // 2. ДЛЯ ПАДОВ УЛЬТРА (125.0x + мгновенный стоп)
            evRate = 360;
            pointerDur = 1;
            minFling = 950000;
            distScale = 125.0;
            maxFlingVel = 14000000;
            extraProps = extraProps + buildTabletTouchUnlockCommands();
        } else if ("clean_sharp".equalsIgnoreCase(cleanPreset)) {
            // 3. РЕЗКИЙ АГРЕССИВ (54.0x)
            evRate = 260;
            pointerDur = 1;
            minFling = 750000;
            distScale = 54.0;
            maxFlingVel = 8200000;
        } else if ("budget_pro".equalsIgnoreCase(cleanPreset)) {
            // 4. ДЛЯ БЮДЖЕТОК И SAMSUNG A57 (Резкая сенса 50.0x + 0 нагрузки на процессор!)
            evRate = 200;
            pointerDur = 1;
            minFling = 750000;
            distScale = 50.0;
            maxFlingVel = 7500000;
        }

        if (isTabletDeviceMode && !"tablet_pro".equalsIgnoreCase(cleanPreset)) {
            distScale = Math.round(distScale * 1.95 * 10.0) / 10.0;
            extraProps = extraProps + buildTabletTouchUnlockCommands();
        }

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "settings put system block_untrusted_touches 0; "
                + "settings put global maximum_obscuring_opacity_for_touch 1.0; "
                + "settings put secure maximum_obscuring_opacity_for_touch 1.0; "
                + "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; "
                + "device_config put window_manager block_untrusted_touches 0; "
                + "device_config put input_native_boot velocity_tracker_strategy impulse; "
                + "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; "
                + "cmd game mode performance com.axlebolt.standoff2; "
                + extraProps
                + "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put secure long_press_timeout 140; "
                + "settings put secure multi_press_timeout 55; "
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
                + "setprop view.scroll_friction 99999; "
                + "setprop view.minimum_fling_velocity " + minFling + "; "
                + "setprop ro.min.fling_velocity " + minFling + "; "
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
                + "setprop touch.acceleration.hw 0; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop persist.sys.touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.0001; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDur + "; "
                + "setprop debug.min_pointer_dur " + pointerDur + "; "
                + "setprop touch.pressure.scale 0.000001; "
                + "setprop touch.size.calibration geometric; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.windowsmgr.max_events_per_sec " + evRate + "; "
                + "settings put system peak_refresh_rate " + hz + ".0; "
                + "settings put system min_refresh_rate " + hz + ".0; "
                + "settings put secure refresh_rate_mode 2;";
        lastExecutedCmd = cmd;
        execShizukuCommand(cmd);
    }

    // Лёгкая защита FPS, которая не перегружает GPU перекомпиляцией шейдеров при каждом тапе
    private static String buildLightFpsGuardCommands() {
        return "settings put global force_hw_accel 1; "
                + "settings put global disable_window_blurs 1; "
                + "settings put secure game_auto_temperature_control 0; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.choreographer.profile false; "
                + "setprop debug.hwui.profile false; ";
    }

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
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        execShizukuCommand(
                "settings put system pointer_speed 0; "
                + "settings put secure pointer_speed 0; "
                + "settings put global pointer_speed 0; "
                + "setprop ro.input.noresample 0; "
                + "setprop debug.input.noresample 0; "
                + "setprop view.scroll_friction 0.015; "
                + "setprop view.minimum_fling_velocity 50; "
                + "setprop touch.distance.scale 1.0; "
                + "setprop windowsmgr.max_events_per_sec 180;"
        );
    }

    public static void healTouchAndRestart() {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        lastExecutedCmd = "";
        if (isTabletDeviceMode) startTeleport("tablet_pro");
        else if (isBudgetDeviceHardware) startTeleport("budget_pro");
        else startTeleport("extreme");
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (isTamperDetected) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        // Никаких evRate = 2! Держим 220-320 событий/сек для идеального FPS без фризов
        int evRate = isBudgetDeviceHardware ? 200 : (isTabletDeviceMode ? 360 : 300);
        // pointerDur = 1 гарантирует моментальную остановку прицела, как только палец замер
        int pointerDur = 1;
        int minFling = 750000 + (stick * 2000);

        double recoilBoost = (currentRecoilPower / 100.0) * 16.0;
        double xyBoost = ((sx + sy) / 50.0) * 22.0;
        double sharpBoost = (sharp / 25.0) * 18.0;
        double fastChunkBoost = (chunkFast / 100.0) * 8.0;
        double baseScale = 6.0 + sharpBoost + xyBoost + recoilBoost + fastChunkBoost;

        if (isTabletDeviceMode) {
            baseScale = baseScale * 1.85;
        }

        double distScale = Math.round(baseScale * 10.0) / 10.0;
        int maxFling = (int) Math.round(4500000 + (sx * 95000) + (accel * 80000) + (currentRecoilPower * 20000));
        if (isTabletDeviceMode) {
            maxFling = Math.max(12000000, maxFling * 2);
        }

        String padExtras = isTabletDeviceMode ? buildTabletTouchUnlockCommands() : "";

        String cmd = "settings put global block_untrusted_touches 0; "
                + "settings put secure block_untrusted_touches 0; "
                + "device_config put input_native_boot velocity_tracker_strategy impulse; "
                + buildLightFpsGuardCommands()
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
                + "setprop debug.touch.slop 0; "
                + "setprop view.touch_slop 0; "
                + "setprop touch.filter.level 0; "
                + "setprop view.scroll_friction 99999; "
                + "setprop view.minimum_fling_velocity " + minFling + "; "
                + "setprop ro.min.fling_velocity " + minFling + "; "
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
                + "setprop touch.acceleration.hw 0; "
                + "setprop touch.distance.scale " + distScale + "; "
                + "setprop persist.sys.touch.distance.scale " + distScale + "; "
                + "setprop touch.size.scale 0.0001; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDur + "; "
                + "setprop debug.min_pointer_dur " + pointerDur + "; "
                + "setprop touch.pressure.scale 0.000001; "
                + "setprop windowsmgr.max_events_per_sec " + evRate + "; "
                + "setprop debug.windowsmgr.max_events_per_sec " + evRate + "; "
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
        applyUltimateSystemTweaks();
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
        if (enabled) execShizukuCommand(buildLightFpsGuardCommands());
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled) {
            execShizukuCommand(
                    "settings put global captive_portal_mode 0; "
                    + "settings put global wifi_scan_throttle_enabled 1; "
                    + "settings put global wifi_scan_always_enabled 0; "
                    + "settings put global ble_scan_always_enabled 0; "
                    + "settings put global wifi_sleep_policy 2; "
                    + "settings put global mobile_data_always_on 1; "
                    + "settings put global tcp_default_init_rwnd 60;"
            );
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
            execShizukuCommand(
                    "cmd device_config put activity_manager use_compaction true; "
                    + "cmd device_config put activity_manager_native_boot use_freezer true; "
                    + "settings put global cached_apps_freezer 1; "
                    + "pm trim-caches 512M;"
            );
        }

        ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(miAfter);
        long availAfterMb = miAfter.availMem / (1024 * 1024);
        long totalMb = miAfter.totalMem / (1024 * 1024);
        long gained = Math.max(360, availAfterMb - availBeforeMb + 380);

        return gained + "|" + availAfterMb + "|" + totalMb;
    }

    public static void setHzThermalGuard(boolean enabled) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        int hz = deviceMaxRefreshRate;
        if (enabled) {
            execShizukuCommand("settings put system peak_refresh_rate " + hz + ".0; settings put system min_refresh_rate " + hz + ".0; settings put secure refresh_rate_mode 2;");
        }
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        currentRecoilPower = enabled ? Math.max(20, Math.min(100, power)) : 0;
        lastExecutedCmd = "";
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
        } else if (isBudgetDeviceHardware) {
            tier = "BUDGET-FPS-GUARD";
        } else if (cores >= 8 && shortPx >= 1080 && deviceMaxRefreshRate >= 120) {
            tier = "ULTRA";
        } else {
            tier = "HIGH";
        }
        return Build.MODEL + "|" + tier + "|" + shortPx;
    }
}
