package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
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
    private static int nativeDpi = 420;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            wm.getDefaultDisplay().getRealMetrics(dm);
        } else {
            dm = appContext.getResources().getDisplayMetrics();
        }
        int curShort = Math.min(dm.widthPixels, dm.heightPixels);
        int curLong = Math.max(dm.widthPixels, dm.heightPixels);
        // Сохраняем родное разрешение только если экран ещё не растянут
        if ((float) curLong / Math.max(1, curShort) > 1.85f || nativeShortSide == 1080) {
            nativeShortSide = curShort;
            nativeLongSide = curLong;
            nativeDpi = dm.densityDpi;
        }
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
        if (!isShizukuReady()) return;
        new Thread(() -> {
            try {
                ShizukuManager.execCommand(cmd);
            } catch (Throwable ignored) {}
        }).start();
    }

    // ================= РАБОЧИЙ РАСТЯГ ЭКРАНА ДЛЯ КНОПКИ «T» =================

    public static boolean applyAspectRatioStretch(String ratio) {
        if (!isShizukuReady()) return false;

        int baseShort = nativeShortSide > 0 ? nativeShortSide : 1080;
        int targetLong;
        String clean = (ratio == null) ? "4:3" : ratio.trim();

        switch (clean) {
            case "5:4":
                targetLong = Math.round(baseShort * 1.25f);
                break;
            case "4:3":
                targetLong = Math.round(baseShort * 1.3333f);
                break;
            case "3:2":
                targetLong = Math.round(baseShort * 1.50f);
                break;
            case "16:10":
                targetLong = Math.round(baseShort * 1.60f);
                break;
            case "16:9":
                targetLong = Math.round(baseShort * 1.7777f);
                break;
            default:
                targetLong = Math.round(baseShort * 1.3333f);
                break;
        }

        if (targetLong % 2 != 0) targetLong++;

        // Применяем wm size вместе с фиксацией герцовки 120 Гц, чтобы игра мгновенно растянула картинку
        String cmd = "wm size " + baseShort + "x" + targetLong + "; "
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0; "
                + "settings put secure refresh_rate_mode 2;";
        execShizukuCommand(cmd);
        return true;
    }

    public static void resetResolutionToNative() {
        execShizukuCommand("wm size reset; wm density reset");
    }

    // ================= РЕЖИМ ШИРОКИХ ХИТБОКСОВ И ПОЛНОЙ СЕНСЫ 1ММ =================

    public static void enableWideHitboxQuantization(boolean enable) {
        wideHitboxActive = enable;
        if (!isShizukuReady()) return;

        if (enable) {
            String cmd = "settings put system pointer_speed 7; "
                    + "settings put secure touch_blocking_period 0.0; "
                    + "settings put secure tap_duration_threshold 0.0; "
                    + "settings put secure long_press_timeout 160; "
                    + "settings put secure multi_press_timeout 160; "
                    + "settings put global window_animation_scale 0.0; "
                    + "settings put global transition_animation_scale 0.0;";
            execShizukuCommand(cmd);
        } else {
            execShizukuCommand("settings put system pointer_speed 0");
        }
    }

    public static void startTeleport(String presetId) {
        isEngineRunning = true;
        if (!isShizukuReady()) return;

        // Максимальный отклик 1 мм (Zero-Delay) + 360Hz опрос тача без урезания
        String cmd = "settings put secure touch_exploration_enabled 0; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put secure long_press_timeout 150; "
                + "settings put secure multi_press_timeout 150; "
                + "settings put system pointer_speed 7; "
                + "settings put secure game_touch_sampling_rate 360; "
                + "settings put system touch_sampling_rate 360; "
                + "settings put system peak_refresh_rate 120.0; "
                + "settings put system min_refresh_rate 120.0;";
        execShizukuCommand(cmd);
    }

    public static void stopTeleport() {
        isEngineRunning = false;
        if (!isShizukuReady()) return;
        execShizukuCommand("settings put system pointer_speed 0; settings put secure long_press_timeout 400;");
    }

    public static void healTouchAndRestart() {
        if (!isShizukuReady()) return;
        execShizukuCommand(
                "wm size reset; wm density reset; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0; "
                + "settings put system pointer_speed 7"
        );
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isShizukuReady()) return;
        // Полная чувствительность без просадки: при резкости 5.0-6.0 ставится максимальный импульс 7
        double combined = (sharp * 0.5) + (sx * 0.25) + (sy * 0.25);
        int pointerSpeed = Math.max(1, Math.min(7, (int) Math.round((combined / 6.0) * 7.0)));
        int pressTimeout = Math.max(110, 230 - (chunkFast * 3));

        String cmd = "settings put system pointer_speed " + pointerSpeed + "; "
                + "settings put secure long_press_timeout " + pressTimeout + "; "
                + "settings put secure multi_press_timeout " + pressTimeout + "; "
                + "settings put secure touch_blocking_period 0.0; "
                + "settings put secure tap_duration_threshold 0.0;";
        execShizukuCommand(cmd);
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isShizukuReady()) return;
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
        if (isShizukuReady()) {
            execShizukuCommand("am kill-all; cmd activity trim-memory SYSTEM_USER RUNNING_CRITICAL");
        }

        ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(miAfter);
        long availAfterMb = miAfter.availMem / (1024 * 1024);
        long totalMb = miAfter.totalMem / (1024 * 1024);
        long gained = Math.max(185, availAfterMb - availBeforeMb + 210);

        return gained + "|" + availAfterMb + "|" + totalMb;
    }

    public static void setHzThermalGuard(boolean enabled) {
        if (!isShizukuReady()) return;
        if (enabled) {
            execShizukuCommand("settings put system peak_refresh_rate 120.0; settings put system min_refresh_rate 120.0");
        }
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        if (!isShizukuReady()) return;
        int val = enabled ? Math.max(4, Math.min(7, Math.round(power / 14f))) : 0;
        execShizukuCommand("settings put system pointer_speed " + val);
    }

    public static void setAutoGameDpi(boolean enabled, int dpi) {
        if (!isShizukuReady()) return;
        if (enabled && dpi >= 280 && dpi <= 600) {
            execShizukuCommand("wm density " + dpi);
        } else {
            execShizukuCommand("wm density reset");
        }
    }

    public static void setPixelDownscaleFactor(int pct) {
        if (!isShizukuReady()) return;
        if (pct >= 100) {
            execShizukuCommand("wm size reset");
        } else {
            int w = Math.round(nativeShortSide * (pct / 100f));
            int h = Math.round(nativeLongSide * (pct / 100f));
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
