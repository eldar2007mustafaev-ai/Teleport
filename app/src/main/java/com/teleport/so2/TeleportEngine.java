package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportEngine {

    private static volatile boolean isServerAuthorized = false;
    private static Context appContext;

    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int nativeDensityDpi = 420;
    private static int currentRefreshRateHz = 120;
    private static int maxHardwareRefreshRateHz = 120;

    private static int pixelDownscalePct = 100;
    private static boolean isStretchActive = false;
    private static String currentRatio = "4:3";
    private static long lastResolutionChangeMs = 0L;

    private static final AtomicBoolean touchInjectorActive = new AtomicBoolean(false);
    private static volatile int touchInjectorMode = 3;
    private static volatile int touchInjectorPower = 80;
    private static final ExecutorService touchInjectExecutor = Executors.newSingleThreadExecutor();

    public static void setServerAuthorizedFromVerifiedHmac(boolean auth) {
        isServerAuthorized = auth && AndroidBridge.isNativeServerAuthorized();
        if (!isServerAuthorized) {
            touchInjectorActive.set(false);
        }
    }

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        detectTrueHardwareScreenMetrics(appContext);
    }

    public static void detectTrueHardwareScreenMetrics(Context ctx) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                Display d = wm.getDefaultDisplay();
                DisplayMetrics dm = new DisplayMetrics();
                d.getRealMetrics(dm);

                int bestShort = Math.min(dm.widthPixels, dm.heightPixels);
                int bestLong = Math.max(dm.widthPixels, dm.heightPixels);
                float liveHz = d.getRefreshRate();
                float maxHz = liveHz;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Display.Mode[] modes = d.getSupportedModes();
                    if (modes != null) {
                        for (Display.Mode m : modes) {
                            int ms = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                            int ml = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                            if (ms >= bestShort && ml >= bestLong) {
                                bestShort = ms;
                                bestLong = ml;
                            }
                            if (m.getRefreshRate() > maxHz) {
                                maxHz = m.getRefreshRate();
                            }
                        }
                    }
                }

                if (bestShort >= 720 && bestLong >= 1280) {
                    nativeShortSide = bestShort;
                    nativeLongSide = bestLong;
                }
                if (dm.densityDpi > 0) {
                    nativeDensityDpi = dm.densityDpi;
                }
                currentRefreshRateHz = Math.max(60, Math.round(liveHz));
                maxHardwareRefreshRateHz = Math.max(currentRefreshRateHz, Math.round(maxHz));
            }
        } catch (Throwable ignored) {}
    }

    public static int getNativeShortSide() {
        return nativeShortSide;
    }

    public static int getNativeLongSide() {
        return nativeLongSide;
    }

    public static int getLiveRefreshRateHz(Context ctx) {
        try {
            Context target = (ctx != null) ? ctx : appContext;
            if (target != null) {
                WindowManager wm = (WindowManager) target.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    currentRefreshRateHz = Math.max(60, Math.round(wm.getDefaultDisplay().getRefreshRate()));
                }
            }
        } catch (Throwable ignored) {}
        return currentRefreshRateHz;
    }

    public static int getMaxHardwareRefreshRateHz() {
        return maxHardwareRefreshRateHz;
    }

    public static String getDeviceHwid(Context context) {
        return AndroidBridge.getNativeHardwareHwid(context != null ? context : appContext);
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
            if (ShizukuManager.isPermissionGranted()) return;
            String[] possibleMethods = new String[]{
                    "requestPermission",
                    "requestShizukuPermission",
                    "checkAndRequestPermission",
                    "init"
            };
            for (String mName : possibleMethods) {
                try {
                    Method m = ShizukuManager.class.getDeclaredMethod(mName);
                    m.setAccessible(true);
                    m.invoke(null);
                    return;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void execShizukuCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        String gatedCmd = AndroidBridge.gateCommandThroughNative(cmd.trim());
        if (gatedCmd == null || gatedCmd.isEmpty()) return;
        if (!cmd.startsWith("wm size reset") && !isServerAuthorized) return;
        try {
            ShizukuManager.execCommand(gatedCmd);
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // УСИЛЕННЫЙ ИНЖЕКТ КАСАНИЙ (GAME TOUCH LAYER + SHIZUKU INPUT DISPATCHER)
    // =========================================================================
    public static void configureGameTouchLayer(
            boolean enabled, int stickiness, int heaviness, int sharpness, float sprayPull, float aimScale
    ) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) {
            touchInjectorActive.set(false);
            return;
        }
        AndroidBridge.configureNativeTouchLayer(stickiness, heaviness, sharpness, sprayPull, aimScale);
        int computedPower = Math.max(20, Math.min(100, (stickiness + sharpness) / 2));
        setTouchInjectorActive(enabled, 3, computedPower);
    }

    public static void setTouchInjectorActive(boolean enabled, int mode, int power) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) {
            touchInjectorActive.set(false);
            return;
        }
        touchInjectorMode = Math.max(1, Math.min(3, mode));
        touchInjectorPower = Math.max(10, Math.min(100, power));
        touchInjectorActive.set(enabled);

        if (!isShizukuReady()) return;

        if (enabled) {
            int ptrSpeed = Math.min(7, 3 + (touchInjectorPower / 22));
            int touchSlop = Math.max(1, 8 - (touchInjectorPower / 14));
            int longPress = Math.max(130, 210 - (touchInjectorPower / 2));
            int multiPress = Math.max(95, 150 - (touchInjectorPower / 3));

            String pkg = (appContext != null) ? appContext.getPackageName() : "com.teleport.so2";
            execShizukuCommand(
                    "settings put global block_untrusted_touches 0; " +
                    "settings put secure block_untrusted_touches 0; " +
                    "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; " +
                    "device_config put input_native_boot touch_slop " + touchSlop + "; " +
                    "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; " +
                    "settings put system pointer_speed " + ptrSpeed + "; " +
                    "settings put secure long_press_timeout " + longPress + "; " +
                    "settings put secure multi_press_timeout " + multiPress + ";"
            );
        } else {
            execShizukuCommand(
                    "device_config put input_native_boot touch_slop 8; " +
                    "settings put system pointer_speed 2; " +
                    "settings put secure long_press_timeout 250; " +
                    "settings put secure multi_press_timeout 180;"
            );
        }
    }

    public static void injectTouchTap(final int x, final int y) {
        if (!touchInjectorActive.get() || !isServerAuthorized || !isShizukuReady()) return;
        touchInjectExecutor.execute(() -> {
            int safeX = Math.max(0, Math.min(nativeLongSide, x));
            int safeY = Math.max(0, Math.min(nativeShortSide, y));
            execShizukuCommand("input tap " + safeX + " " + safeY);
        });
    }

    public static void injectRecoilSwipe(final int startX, final int startY, final int deltaX, final int deltaY, final int durationMs) {
        if (!touchInjectorActive.get() || !isServerAuthorized || !isShizukuReady()) return;
        touchInjectExecutor.execute(() -> {
            float[] stab = AndroidBridge.stabilizeTouchDeltaNative(0, deltaX, deltaY);
            int endX = Math.max(0, startX + Math.round(stab[0]));
            int endY = Math.max(0, startY + Math.round(stab[1]));
            int dur = Math.max(8, Math.min(250, durationMs));
            execShizukuCommand("input swipe " + startX + " " + startY + " " + endX + " " + endY + " " + dur);
        });
    }

    public static void startTeleport(String presetId) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) return;
        if (presetId != null && presetId.startsWith("SET_RATIO:")) {
            currentRatio = presetId.substring("SET_RATIO:".length()).trim();
            return;
        }
        if (!isShizukuReady()) return;

        String p = (presetId == null) ? "sharp" : presetId.toLowerCase(Locale.US);
        int ptrSpeed = 5;
        long longPress = 190;
        long multiPress = 140;

        if (p.contains("sharp") || p.contains("max") || p.contains("hardlock") || p.contains("sniper")) {
            ptrSpeed = 7;
            longPress = 145;
            multiPress = 100;
        } else if (p.contains("clean_sharp") || p.contains("onetap")) {
            ptrSpeed = 6;
            longPress = 165;
            multiPress = 115;
        } else if (p.contains("fps") || p.contains("clean")) {
            ptrSpeed = 4;
            longPress = 205;
            multiPress = 155;
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
        touchInjectorActive.set(false);
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        int computedSpeed = Math.max(-7, Math.min(7, (int) Math.round((sharp - 1.0) * 3.0 + (sx + sy) * 0.5)));
        int tapTimeout = Math.max(125, Math.min(350, 260 - chunks));
        int touchSlop = Math.max(1, Math.min(16, 10 - (grid / 15)));

        float aimScale = (float) Math.max(0.75, Math.min(2.0, (sx + sy) * 0.5));
        AndroidBridge.configureNativeTouchLayer(stick, Math.max(20, 100 - chunkFast), (int) Math.round(sharp * 28), 1.25f, aimScale);

        execShizukuCommand(
                "settings put system pointer_speed " + computedSpeed + "; " +
                "settings put secure long_press_timeout " + tapTimeout + "; " +
                "settings put secure multi_press_timeout " + Math.max(95, tapTimeout - 40) + "; " +
                "device_config put input_native_boot touch_slop " + touchSlop + ";"
        );
    }

    // =========================================================================
    // БЕЗОПАСНЫЙ РАСТЯГ И СНИЖЕНИЕ РАЗРЕШЕНИЯ (БЕЗ КРАША SYSTEM_SERVER!)
    // =========================================================================
    public static boolean applyAspectRatioStretch(String ratioStr) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return false;
        if (ratioStr != null && !ratioStr.trim().isEmpty()) {
            currentRatio = ratioStr.trim().toUpperCase(Locale.US);
        }
        isStretchActive = true;

        double scale = Math.max(0.60, Math.min(1.0, pixelDownscalePct / 100.0));
        int baseShort = (int) Math.round(nativeShortSide * scale);
        int targetLong = (int) Math.round(nativeLongSide * scale);

        if (currentRatio.contains("4:3")) {
            targetLong = (int) Math.round(baseShort * (4.0 / 3.0));
        } else if (currentRatio.contains("5:4")) {
            targetLong = (int) Math.round(baseShort * 1.25);
        } else if (currentRatio.contains("3:2")) {
            targetLong = (int) Math.round(baseShort * 1.5);
        } else if (currentRatio.contains("16:10")) {
            targetLong = (int) Math.round(baseShort * 1.6);
        } else if (currentRatio.contains("16:9")) {
            targetLong = (int) Math.round(baseShort * (16.0 / 9.0));
        }

        baseShort = Math.max(540, (baseShort / 4) * 4);
        targetLong = Math.max(720, (targetLong / 4) * 4);

        execShizukuCommand(
                "wm size " + baseShort + "x" + targetLong + "; " +
                "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation 1;"
        );
        return true;
    }

    public static void releaseBoxToFullStretch() {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        isStretchActive = false;
        double scale = Math.max(0.60, Math.min(1.0, pixelDownscalePct / 100.0));
        int s = Math.max(540, ((int) Math.round(nativeShortSide * scale) / 4) * 4);
        int l = Math.max(960, ((int) Math.round(nativeLongSide * scale) / 4) * 4);
        execShizukuCommand("wm size " + s + "x" + l + ";");
    }

    public static void resetResolutionToNative() {
        pixelDownscalePct = 100;
        isStretchActive = false;
        execShizukuCommand("wm size reset; wm density reset; settings put system accelerometer_rotation 1;");
    }

    // Защита от спама wm size (чтобы ползунок никогда не вызывал перезагрузку телефона!)
    public static void setPixelDownscaleFactor(int pct) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastResolutionChangeMs < 350L && pct != 100) return;
        lastResolutionChangeMs = now;

        pixelDownscalePct = Math.max(60, Math.min(100, pct));
        if (!isShizukuReady()) return;

        if (pixelDownscalePct >= 100 && !isStretchActive) {
            execShizukuCommand("wm size reset;");
            return;
        }

        if (isStretchActive) {
            applyAspectRatioStretch(currentRatio);
        } else {
            double factor = pixelDownscalePct / 100.0;
            int w = Math.max(540, ((int) Math.round(nativeShortSide * factor) / 4) * 4);
            int h = Math.max(960, ((int) Math.round(nativeLongSide * factor) / 4) * 4);
            execShizukuCommand("wm size " + w + "x" + h + ";");
        }
    }

    public static void healTouchAndRestart() {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put secure long_press_timeout 220; " +
                "settings put secure multi_press_timeout 170; " +
                "settings put system pointer_speed 4; " +
                "settings put global block_untrusted_touches 0; " +
                "device_config put input_native_boot touch_slop 6; " +
                "settings put global window_animation_scale 0.5; " +
                "settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5;"
        );
    }

    public static void setRawInputNoSmoothing(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "device_config put input_native_boot touch_slop " + (enabled ? "2" : "8") + "; " +
                "settings put global window_animation_scale " + (enabled ? "0.0" : "0.5") + "; " +
                "settings put global transition_animation_scale " + (enabled ? "0.0" : "0.5") + "; " +
                "settings put global animator_duration_scale " + (enabled ? "0.0" : "0.5") + ";"
        );
    }

    // Убраны опасные setprop debug.sf.latch_unsignaled, ронявшие SurfaceFlinger на One UI!
    public static void setVulkanGpuTurbo(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "cmd power set-fixed-performance-mode-enabled " + (enabled ? "true" : "false") + "; " +
                "settings put global cached_apps_freezer enabled;"
        );
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put global wifi_scan_always_enabled " + (enabled ? "0" : "1") + "; " +
                "settings put global ble_scan_always_enabled " + (enabled ? "0" : "1") + "; " +
                "settings put global wifi_suspend_optimizations_enabled " + (enabled ? "0" : "1") + ";"
        );
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        int maxHz = getMaxHardwareRefreshRateHz();
        int targetHz = enabled ? maxHz : 60;
        execShizukuCommand(
                "settings put system peak_refresh_rate " + maxHz + ".0; " +
                "settings put system min_refresh_rate " + targetHz + ".0; " +
                "device_config put input_native_boot touch_slop " + (enabled ? "2" : "8") + ";"
        );
    }

    public static void setHzThermalGuard(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        int maxHz = getMaxHardwareRefreshRateHz();
        execShizukuCommand(
                "settings put system peak_refresh_rate " + maxHz + ".0; " +
                "settings put system min_refresh_rate " + (enabled ? maxHz + ".0" : "60.0") + "; " +
                "settings put secure refresh_rate_mode " + (enabled ? "2" : "0") + ";"
        );
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        int speed = enabled ? Math.min(7, 2 + (power / 20)) : 2;
        execShizukuCommand("settings put system pointer_speed " + speed + ";");
    }

    public static void enableWideHitboxQuantization(boolean enabled) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand("device_config put input_native_boot touch_slop " + (enabled ? "1" : "8") + ";");
    }

    // Полностью исправленная работа всех ползунков Бустера (без перезагрузки устройства!)
    public static void applyShooterOptimizer(
            int fpsLock, int gpuBoost, int frameTime,
            int edgeSens, int multiTouch, int netBuffer, int thermalLim
    ) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        int validFps = Math.max(60, Math.min(240, fpsLock));
        int ptrSpeed = Math.max(1, Math.min(7, 2 + (edgeSens / 20)));
        int touchSlop = Math.max(1, Math.min(10, 9 - (multiTouch / 15)));
        int longPress = Math.max(130, Math.min(280, 240 - frameTime));
        boolean perfMode = (gpuBoost >= 50);
        boolean netOpt = (netBuffer >= 50);

        execShizukuCommand(
                "settings put system peak_refresh_rate " + validFps + ".0; " +
                "settings put system min_refresh_rate " + validFps + ".0; " +
                "settings put system pointer_speed " + ptrSpeed + "; " +
                "device_config put input_native_boot touch_slop " + touchSlop + "; " +
                "settings put secure long_press_timeout " + longPress + "; " +
                "settings put global wifi_scan_always_enabled " + (netOpt ? "0" : "1") + "; " +
                "cmd power set-fixed-performance-mode-enabled " + (perfMode ? "true" : "false") + ";"
        );
    }

    // Безопасная очистка памяти (без kill-all, ронявшего системный интерфейс!)
    public static String performRamBoost(Context context) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) return "NEED_AUTH";
        try {
            if (isShizukuReady()) {
                execShizukuCommand("cmd activity send-trim-memory com.axlebolt.standoff2 RUNNING_LOW; sync;");
            }
            System.gc();
            Context targetCtx = (context != null) ? context : appContext;
            if (targetCtx != null) {
                ActivityManager am = (ActivityManager) targetCtx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    long availMb = mi.availMem / (1024L * 1024L);
                    long totalMb = mi.totalMem / (1024L * 1024L);
                    return "ОЗУ оптимизировано! Свободно: " + availMb + " МБ из " + totalMb + " МБ";
                }
            }
        } catch (Throwable ignored) {}
        return "Фоновый кэш успешно очищен!";
    }

    public static String runSmartDeviceScan(Context context) {
        try {
            Context targetCtx = (context != null) ? context : appContext;
            if (targetCtx != null) {
                detectTrueHardwareScreenMetrics(targetCtx);
            }
            int cores = Runtime.getRuntime().availableProcessors();
            int liveHz = getLiveRefreshRateHz(targetCtx);
            int maxHz = getMaxHardwareRefreshRateHz();
            String hzStr = (liveHz == maxHz) ? (liveHz + " Гц") : (liveHz + "/" + maxHz + " Гц");
            return Build.MANUFACTURER.toUpperCase(Locale.US) + " " + Build.MODEL +
                    " • " + nativeShortSide + "x" + nativeLongSide +
                    " • " + cores + " ядер • " + hzStr;
        } catch (Throwable t) {
            return Build.MANUFACTURER + " " + Build.MODEL + " • " + currentRefreshRateHz + " Гц";
        }
    }
}
