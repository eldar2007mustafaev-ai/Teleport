package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaDrm;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public class TeleportEngine {

    // ЗАКРЫТИЕ УЯЗВИМОСТИ #17: В <clinit> строго false!
    private static volatile boolean isServerAuthorized = false;

    private static Context appContext;
    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int nativeDensityDpi = 420;
    private static int pixelDownscalePct = 100;
    private static boolean teleportActive = false;
    private static String currentRatio = "4:3";

    public static void setServerAuthorizedFromVerifiedHmac(boolean auth) {
        isServerAuthorized = auth && AndroidBridge.isNativeServerAuthorized();
    }

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        detectNativeScreenMetrics(appContext);
    }

    private static void detectNativeScreenMetrics(Context ctx) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null && wm.getDefaultDisplay() != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                int w = dm.widthPixels;
                int h = dm.heightPixels;
                nativeShortSide = Math.min(w, h);
                nativeLongSide = Math.max(w, h);
                nativeDensityDpi = dm.densityDpi > 0 ? dm.densityDpi : 420;
            }
        } catch (Throwable ignored) {}
    }

    public static int getNativeShortSide() {
        return nativeShortSide;
    }

    public static int getNativeLongSide() {
        return nativeLongSide;
    }

    public static String getDeviceHwid(Context context) {
        StringBuilder raw = new StringBuilder();
        try {
            UUID widevineUuid = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm drm = new MediaDrm(widevineUuid);
            byte[] drmId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            if (drmId != null) {
                for (byte b : drmId) raw.append(String.format("%02x", b));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close();
            else drm.release();
        } catch (Throwable ignored) {}

        raw.append("|").append(Build.BOARD)
           .append("|").append(Build.BRAND)
           .append("|").append(Build.DEVICE)
           .append("|").append(Build.HARDWARE)
           .append("|").append(Build.MODEL);

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            String drmHash = hex.toString();

            String aid = "";
            if (context != null) {
                aid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            }
            if (aid != null && !aid.trim().isEmpty()) {
                return drmHash + "|" + aid.trim().toLowerCase(Locale.US);
            }
            return drmHash;
        } catch (Throwable t) {
            return "hw7744aabb1122cc";
        }
    }

    public static boolean isShizukuReady() {
        try {
            return ShizukuManager.isPermissionGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestShizukuPermissionIfNeeded() {
        try {
            if (!ShizukuManager.isPermissionGranted()) {
                ShizukuManager.requestPermission();
            }
        } catch (Throwable ignored) {}
    }

    // ЗАКРЫТИЕ УЯЗВИМОСТИ #17: Первой строкой проверяем isServerAuthorized и нативный флаг в libso2.so!
    public static void execShizukuCommand(String cmd) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !ApiManager.isSessionVerified()) {
            return;
        }
        if (cmd == null || cmd.trim().isEmpty()) return;
        try {
            ShizukuManager.execCommand(cmd.trim());
        } catch (Throwable ignored) {}
    }

    public static void startTeleport(String presetId) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) return;
        teleportActive = true;
        if (presetId != null && presetId.startsWith("SET_RATIO:")) {
            currentRatio = presetId.substring("SET_RATIO:".length()).trim();
            return;
        }
        if (!isShizukuReady()) return;

        String p = (presetId == null) ? "pro" : presetId.toLowerCase(Locale.US);
        int ptrSpeed = 5;
        long longPress = 200;
        long multiPress = 150;

        if (p.contains("1") || p.contains("ultra") || p.contains("extreme") || p.contains("max")) {
            ptrSpeed = 7;
            longPress = 160;
            multiPress = 110;
        } else if (p.contains("2") || p.contains("pad") || p.contains("sharp")) {
            ptrSpeed = 6;
            longPress = 180;
            multiPress = 130;
        } else if (p.contains("4") || p.contains("fps") || p.contains("clean")) {
            ptrSpeed = 4;
            longPress = 220;
            multiPress = 170;
        }

        execShizukuCommand(
                "settings put system pointer_speed " + ptrSpeed + "; " +
                "settings put secure long_press_timeout " + longPress + "; " +
                "settings put secure multi_press_timeout " + multiPress + "; " +
                "settings put global window_animation_scale 0.5; " +
                "settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5;"
        );
    }

    public static void stopTeleport() {
        teleportActive = false;
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        int computedSpeed = Math.max(-7, Math.min(7, (int) Math.round((sharp - 1.0) * 3.0 + (sx + sy) * 0.5)));
        int tapTimeout = Math.max(120, Math.min(350, 260 - chunks));
        int touchSlop = Math.max(1, Math.min(16, 10 - (grid / 15)));

        execShizukuCommand(
                "settings put system pointer_speed " + computedSpeed + "; " +
                "settings put secure long_press_timeout " + tapTimeout + "; " +
                "settings put secure multi_press_timeout " + Math.max(100, tapTimeout - 40) + "; " +
                "device_config put input_native_boot touch_slop " + touchSlop + ";"
        );
    }

    public static boolean applyAspectRatioStretch(String ratioStr) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return false;
        if (ratioStr != null && !ratioStr.trim().isEmpty()) {
            currentRatio = ratioStr.trim().toUpperCase(Locale.US);
        }

        double scale = Math.max(0.50, Math.min(1.0, pixelDownscalePct / 100.0));
        int baseShort = (int) Math.round(nativeShortSide * scale);
        int targetLong = (int) Math.round(nativeLongSide * scale);

        if (currentRatio.contains("4:3")) {
            targetLong = (int) Math.round(baseShort * (4.0 / 3.0));
        } else if (currentRatio.contains("3:2")) {
            targetLong = (int) Math.round(baseShort * 1.5);
        } else if (currentRatio.contains("16:10")) {
            targetLong = (int) Math.round(baseShort * 1.6);
        } else if (currentRatio.contains("5:4")) {
            targetLong = (int) Math.round(baseShort * 1.25);
        } else if (currentRatio.contains("16:9")) {
            targetLong = (int) Math.round(baseShort * (16.0 / 9.0));
        }

        baseShort = (baseShort / 2) * 2;
        targetLong = (targetLong / 2) * 2;

        execShizukuCommand(
                "wm size " + baseShort + "x" + targetLong + "; " +
                "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation 1;"
        );
        return true;
    }

    public static void releaseBoxToFullStretch() {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        double scale = Math.max(0.50, Math.min(1.0, pixelDownscalePct / 100.0));
        int s = ((int) Math.round(nativeShortSide * scale) / 2) * 2;
        int l = ((int) Math.round(nativeLongSide * scale) / 2) * 2;
        execShizukuCommand("wm size " + s + "x" + l + ";");
    }

    public static void resetResolutionToNative() {
        pixelDownscalePct = 100;
        execShizukuCommand("wm size reset; wm density reset; settings put system accelerometer_rotation 1;");
    }

    public static void setPixelDownscaleFactor(int pct) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) return;
        pixelDownscalePct = Math.max(50, Math.min(100, pct));
        if (!isShizukuReady()) return;

        if (pixelDownscalePct >= 100) {
            execShizukuCommand("wm size reset; wm density reset;");
        } else {
            double factor = pixelDownscalePct / 100.0;
            int w = ((int) Math.round(nativeShortSide * factor) / 2) * 2;
            int h = ((int) Math.round(nativeLongSide * factor) / 2) * 2;
            int d = Math.max(160, (int) Math.round(nativeDensityDpi * factor));
            execShizukuCommand("wm size " + w + "x" + h + "; wm density " + d + ";");
        }
    }

    public static void healTouchAndRestart() {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put secure long_press_timeout 250; " +
                "settings put secure multi_press_timeout 200; " +
                "settings put system pointer_speed 3; " +
                "settings put global block_untrusted_touches 0;"
        );
    }

    public static void setRawInputNoSmoothing(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put global window_animation_scale " + (enabled ? "0.0" : "0.5") + "; " +
                "settings put global transition_animation_scale " + (enabled ? "0.0" : "0.5") + "; " +
                "settings put global animator_duration_scale " + (enabled ? "0.0" : "0.5") + ";"
        );
    }

    public static void setVulkanGpuTurbo(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "setprop debug.hwui.renderer " + (enabled ? "skiavk" : "opengl") + "; " +
                "setprop debug.egl.hw 1; " +
                "setprop debug.sf.latch_unsignaled 1;"
        );
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put global wifi_scan_always_enabled " + (enabled ? "0" : "1") + "; " +
                "settings put global ble_scan_always_enabled " + (enabled ? "0" : "1") + ";"
        );
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "setprop debug.sf.early_phase_offset_ns " + (enabled ? "500000" : "1000000") + "; " +
                "settings put system peak_refresh_rate " + hz + ".0; " +
                "settings put system min_refresh_rate " + (enabled ? hz + ".0" : "60.0") + ";"
        );
    }

    public static void setHzThermalGuard(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put system peak_refresh_rate " + (enabled ? "120.0" : "60.0") + "; " +
                "settings put secure refresh_rate_mode " + (enabled ? "2" : "0") + ";"
        );
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        int speed = enabled ? Math.min(7, 2 + (power / 20)) : 0;
        execShizukuCommand("settings put system pointer_speed " + speed + ";");
    }

    public static void enableWideHitboxQuantization(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand("setprop debug.sf.disable_backpressure " + (enabled ? "1" : "0") + ";");
    }

    public static void applyShooterOptimizer(
            int fpsLock, int gpuBoost, int frameTime,
            int edgeSens, int multiTouch, int netBuffer, int thermalLim
    ) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put system peak_refresh_rate " + fpsLock + ".0; " +
                "settings put system min_refresh_rate " + fpsLock + ".0; " +
                "setprop debug.hwui.renderer skiagl; " +
                "cmd power set-fixed-performance-mode-enabled true;"
        );
    }

    public static String performRamBoost(Context context) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) return "NEED_AUTH";
        try {
            if (isShizukuReady()) {
                execShizukuCommand("am kill-all; cmd activity kill-all;");
            }
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long availMb = mi.availMem / (1024L * 1024L);
                long totalMb = mi.totalMem / (1024L * 1024L);
                return "ОЗУ очищено! Свободно: " + availMb + " МБ из " + totalMb + " МБ";
            }
        } catch (Throwable ignored) {}
        return "Кэш фоновых процессов очищен!";
    }

    public static String runSmartDeviceScan(Context context) {
        try {
            return DeviceScanner.scanDeviceSpecs(context);
        } catch (Throwable t) {
            return Build.MANUFACTURER + " " + Build.MODEL + " • Android " + Build.VERSION.RELEASE;
        }
    }
}
