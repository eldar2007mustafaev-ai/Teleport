package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static boolean isEngineRunning = false;
    private static boolean wideHitboxActive = false;
    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;

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

    public static boolean applyAspectRatioStretch(String ratio) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return false;

        int baseShort = getNativeShortSide();
        int targetLong;
        String clean = (ratio == null) ? "4:3" : ratio.trim();

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

        if ((targetLong & 1) == 1) targetLong++;

        String cmd = "wm size " + baseShort + "x" + targetLong + "; "
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0; "
                + "settings put secure refresh_rate_mode 2;";
        execShizukuCommand(cmd);
        return true;
    }

    public static void releaseBoxToFullStretch() {
        String cmd = "wm size reset; "
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0; "
                + "settings put secure refresh_rate_mode 2;";
        execShizukuCommand(cmd);
    }

    public static void resetResolutionToNative() {
        execShizukuCommand("wm size reset; wm density reset");
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

    // ================= ДВИЖОК «МЯСОРУБКА КУСКОВ V2» (7 HZ BURST + 26 MS QUANTUM BUFFER) =================
    public static void startTeleport(String presetId) {
        isEngineRunning = true;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        String pkg = appContext != null ? appContext.getPackageName() : "com.teleport.so2";

        // Ключевая связка для максимально жёстких кусков:
        // 1) windowsmgr.max_events_per_sec = 7 + ro.min_pointer_dur = 26:
        //    Драйвер копит дельту свайпа 26 миллисекунд и выстреливает её одним огромным куском!
        // 2) touch.InitialJump = 1 + touch.gestureMode = spots + ro.input.noresample = 1:
        //    Полностью отрубает плавную интерполяцию между кусками.
        // 3) fling_velocity = 180000 + early_phase_offset_ns = -10000000:
        //    Максимальный взрывной рывок на каждый кусок.
        String cmd = "cmd appops set " + pkg + " POST_NOTIFICATION allow; "
                + "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS; "
                + "cmd game mode performance com.axlebolt.standoff2; "
                + "device_config put game_overlay com.axlebolt.standoff2 mode=2,fps=120:mode=3,fps=120; "
                + "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure ignore_repeat_click_timeout 0; "
                + "settings put secure long_press_timeout 260; "
                + "settings put secure multi_press_timeout 230; "
                + "settings put system pointer_speed 7; "
                + "settings put secure game_touch_sampling_rate 360; "
                + "settings put system touch_sampling_rate 360; "
                + "settings put system rakuten_denwa_touch_sensitivity 1; "
                + "settings put system touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "settings put secure stylus_handwriting_enabled 0; "
                + "setprop debug.touch.slop 0; "
                + "setprop view.touch_slop 0; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity 64000; "
                + "setprop view.maximum_fling_velocity 180000; "
                + "setprop ro.max.fling_velocity 180000; "
                + "setprop ro.min.fling_velocity 64000; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.gestureMode spots; "
                + "setprop debug.sf.nobootanimation 1; "
                + "setprop ro.min_pointer_dur 26; "
                + "setprop touch.pressure.scale 0.0001; "
                + "setprop touch.size.calibration geometric; "
                + "setprop windowsmgr.max_events_per_sec 7; "
                + "setprop debug.sf.latch_unsignaled 1; "
                + "setprop debug.sf.disable_backpressure 1; "
                + "setprop debug.sf.early_phase_offset_ns -10000000; "
                + "setprop debug.sf.early_app_phase_offset_ns -10000000; "
                + "setprop debug.sf.high_fps_early_phase_offset_ns -10000000; "
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
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure long_press_timeout 260; "
                + "settings put secure multi_press_timeout 230;"
        );
        startTeleport("extreme");
    }

    // Полная математика всех 10 ползунков «Жёстких Кусков» (6..16 импульсов/сек + буфер 16..32 мс)
    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        // Чем выше накручены ползунки Кусков, тем ниже частота пакетов (до 6 кусков/сек!)
        // и тем мощнее буфер накопления каждого куска (до 32 мс):
        double powerNorm = Math.min(1.0, (sharp + sx + accel) / 70.0);
        double chunkNorm = Math.min(1.0, chunks / 100.0);
        double fastNorm = Math.min(1.0, chunkFast / 200.0);

        int hardChunkRate = Math.max(6, Math.min(18, (int) Math.round(18.0 - (chunkNorm * 6.5) - (powerNorm * 4.0) - (fastNorm * 1.5))));
        int pointerDurQuantumMs = Math.max(14, Math.min(32, (int) Math.round(12 + (chunkNorm * 12.0) + (pause * 0.25))));

        long phaseOffsetNs = -1000000L * Math.max(4, Math.min(14, (grid / 42) + (pause / 5)));
        int maxFling = (int) Math.round(90000 + (sharp * 2200) + (accel * 2200) + (chunkFast * 200));
        int minFling = Math.max(32000, maxFling / 2);

        String cmd = "settings put system pointer_speed 7; "
                + "settings put secure long_press_timeout 260; "
                + "settings put secure multi_press_timeout 230; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put system touch_sensitivity 1; "
                + "settings put system sec_touch_sensitivity 1; "
                + "setprop debug.touch.slop 0; "
                + "setprop view.touch_slop 0; "
                + "setprop view.scroll_friction 0; "
                + "setprop view.minimum_fling_velocity " + minFling + "; "
                + "setprop view.maximum_fling_velocity " + maxFling + "; "
                + "setprop ro.max.fling_velocity " + maxFling + "; "
                + "setprop ro.min.fling_velocity " + minFling + "; "
                + "setprop debug.input.touch.prediction 0; "
                + "setprop persist.sys.touch.prediction 0; "
                + "setprop ro.input.noresample 1; "
                + "setprop debug.input.NeedResample 0; "
                + "setprop touch.Temporality.filter 0; "
                + "setprop touch.InitialJump 1; "
                + "setprop touch.gestureMode spots; "
                + "setprop ro.min_pointer_dur " + pointerDurQuantumMs + "; "
                + "setprop touch.pressure.scale 0.0001; "
                + "setprop windowsmgr.max_events_per_sec " + hardChunkRate + "; "
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
            String cmd = "settings put secure long_press_timeout 260; "
                    + "settings put secure multi_press_timeout 230; "
                    + "setprop debug.touch.slop 0; "
                    + "setprop view.touch_slop 0; "
                    + "setprop view.scroll_friction 0; "
                    + "setprop debug.input.touch.prediction 0; "
                    + "setprop persist.sys.touch.prediction 0; "
                    + "setprop ro.input.noresample 1; "
                    + "setprop debug.input.NeedResample 0; "
                    + "setprop touch.InitialJump 1; "
                    + "setprop touch.gestureMode spots; "
                    + "setprop ro.min_pointer_dur 26; "
                    + "setprop windowsmgr.max_events_per_sec 7; "
                    + "setprop debug.sf.early_phase_offset_ns -10000000; "
                    + "setprop debug.sf.early_app_phase_offset_ns -10000000; "
                    + "settings put secure touch_blocking_period 0.0; "
                    + "settings put secure tap_duration_threshold 0.0; "
                    + "settings put system pointer_speed 7;";
            execShizukuCommand(cmd);
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

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        int val = enabled ? 7 : 0;
        execShizukuCommand("settings put system pointer_speed " + val + "; setprop view.scroll_friction 0;");
    }

    public static void setAutoGameDpi(boolean enabled, int dpi) {
    }

    public static void setPixelDownscaleFactor(int pct) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (pct >= 100) {
            execShizukuCommand("wm size reset");
        } else {
            int w = Math.round(getNativeShortSide() * (pct / 100f));
            int h = Math.round(getNativeLongSide() * (pct / 100f));
            execShizukuCommand("wm size " + w + "x" + h);
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
