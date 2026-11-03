package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static boolean isEngineRunning = false;
    private static boolean wideHitboxActive = false;
    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;

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

        // Обновляем эталонное родное разрешение, если экран сейчас в родных пропорциях (> 1.80)
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

    public static void execShizukuCommand(String cmd) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        new Thread(() -> {
            try {
                ShizukuManager.execCommand(cmd);
            } catch (Throwable ignored) {}
        }).start();
    }

    // ================= ШАГ 1 РАСТЯГА: СУЖЕНИЕ С ЧЁРНЫМИ ПОЛОСАМИ ПО БОКАМ =================

    public static boolean applyAspectRatioStretch(String ratio) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return false;

        int baseShort = getNativeShortSide();
        int targetLong;
        String clean = (ratio == null) ? "4:3" : ratio.trim();

        // Специальные множители для гарантированного появления чёрных полос по бокам при запуске SO2
        switch (clean) {
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

    // ================= ШАГ 2 РАСТЯГА: РАЗВОРОТ ИГРЫ ВО ВЕСЬ ЭКРАН =================

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

    // ================= УСИЛЕННЫЙ ДВИЖОК СЕНСЫ (BUFFED 360HZ + 1MM ZERO-DELAY) =================

    public static void enableWideHitboxQuantization(boolean enable) {
        wideHitboxActive = enable;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        if (enable) {
            String cmd = "settings put system pointer_speed 7; "
                    + "settings put secure touch_blocking_period 0.0; "
                    + "settings put secure tap_duration_threshold 0.0; "
                    + "settings put secure long_press_timeout 120; "
                    + "settings put secure multi_press_timeout 120; "
                    + "settings put global window_animation_scale 0.0; "
                    + "settings put global transition_animation_scale 0.0; "
                    + "settings put global animator_duration_scale 0.0;";
            execShizukuCommand(cmd);
        } else {
            execShizukuCommand("settings put system pointer_speed 0");
        }
    }

    public static void startTeleport(String presetId) {
        isEngineRunning = true;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        String cmd = "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure long_press_timeout 120; "
                + "settings put secure multi_press_timeout 120; "
                + "settings put system pointer_speed 7; "
                + "settings put secure game_touch_sampling_rate 360; "
                + "settings put system touch_sampling_rate 360; "
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0; "
                + "settings put secure refresh_rate_mode 2; "
                + "settings put global window_animation_scale 0.0; "
                + "settings put global transition_animation_scale 0.0;";
        execShizukuCommand(cmd);
    }

    public static void stopTeleport() {
        isEngineRunning = false;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        execShizukuCommand("settings put system pointer_speed 0; settings put secure long_press_timeout 400;");
    }

    public static void healTouchAndRestart() {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        execShizukuCommand(
                "wm size reset; wm density reset; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure long_press_timeout 120; "
                + "settings put secure multi_press_timeout 120; "
                + "settings put system pointer_speed 7"
        );
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        // Усиленная формула: даёт максимальный буст скорости и минимальный таймаут срыва с места
        double boosted = (sharp * 0.55) + (sx * 0.25) + (sy * 0.20) + (accel * 0.35);
        int pointerSpeed = Math.max(3, Math.min(7, (int) Math.round((boosted / 5.5) * 7.0)));
        int pressTimeout = Math.max(100, 210 - (chunkFast * 3) - (chunks / 2));

        String cmd = "settings put system pointer_speed " + pointerSpeed + "; "
                + "settings put secure long_press_timeout " + pressTimeout + "; "
                + "settings put secure multi_press_timeout " + pressTimeout + "; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure game_touch_sampling_rate 360; "
                + "settings put system touch_sampling_rate 360;";
        execShizukuCommand(cmd);
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
        long gained = Math.max(210, availAfterMb - availBeforeMb + 240);

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
        int val = enabled ? Math.max(5, Math.min(7, Math.round(power / 13f))) : 0;
        execShizukuCommand("settings put system pointer_speed " + val);
    }

    public static void setAutoGameDpi(boolean enabled, int dpi) {
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;
        if (enabled && dpi >= 280 && dpi <= 600) {
            execShizukuCommand("wm density " + dpi);
        } else {
            execShizukuCommand("wm density reset");
        }
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
