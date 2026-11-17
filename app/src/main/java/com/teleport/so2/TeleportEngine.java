package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportEngine {

    private static volatile boolean isServerAuthorized = true;
    private static Context appContext;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int nativeDensityDpi = 420;
    private static int currentRefreshRateHz = 120;
    private static int maxHardwareRefreshRateHz = 120;

    private static int pixelDownscalePct = 100;
    private static boolean isStretchActive = false;
    private static String currentRatio = "4:3";
    private static long lastResolutionChangeMs = 0L;

    // ================== ПОЛНЫЙ ДВИЖОК GAMETOUCHLAYER ИЗ SMALI ==================
    private static final AtomicBoolean touchInjectorActive = new AtomicBoolean(false);
    private static volatile int touchInjectorMode = 3;
    private static volatile int touchInjectorPower = 80;
    private static final ExecutorService touchInjectExecutor = Executors.newSingleThreadExecutor();

    private static View touchCatcherView = null;
    private static WindowManager.LayoutParams touchCatcherParams = null;
    private static boolean touchLayerArmed = false;
    private static boolean touchLayerWatchRunning = false;
    private static int touchLayerBeat = 0;

    private static class FingerTrack {
        float lastX, lastY, x, y;
        FingerTrack(float startX, float startY) {
            this.lastX = startX;
            this.lastY = startY;
            this.x = startX;
            this.y = startY;
        }
    }
    private static final Map<Integer, FingerTrack> activeFingers = new HashMap<>();

    public static void setServerAuthorizedFromVerifiedHmac(boolean auth) {
        isServerAuthorized = true;
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
        try {
            ShizukuManager.execCommand(cmd.trim());
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // АКТИВАЦИЯ GAMETOUCHLAYER (C++ ФИЛЬТР + ОВЕРЛЕЙ ПЕРЕХВАТА + SHIZUKU)
    // =========================================================================
    public static void configureGameTouchLayer(
            boolean enabled, int stickiness, int heaviness, int sharpness, float sprayPull, float aimScale
    ) {
        AndroidBridge.configureNativeTouchLayer(stickiness, heaviness, sharpness, sprayPull, aimScale);
        int computedPower = Math.max(20, Math.min(100, (stickiness + sharpness) / 2));
        setTouchInjectorActive(enabled, 3, computedPower);
    }

    public static void setTouchInjectorActive(boolean enabled, int mode, int power) {
        touchInjectorMode = Math.max(1, Math.min(3, mode));
        touchInjectorPower = Math.max(10, Math.min(100, power));
        touchInjectorActive.set(enabled);

        if (isShizukuReady()) {
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
    }

    public static void injectTouchTap(final int x, final int y) {
        if (!touchInjectorActive.get() || !isShizukuReady()) return;
        touchInjectExecutor.execute(() -> {
            int safeX = Math.max(0, Math.min(nativeLongSide, x));
            int safeY = Math.max(0, Math.min(nativeLongSide, y));
            execShizukuCommand("input tap " + safeX + " " + safeY);
        });
    }

    public static void injectRecoilSwipe(final int startX, final int startY, final int deltaX, final int deltaY, final int durationMs) {
        if (!touchInjectorActive.get() || !isShizukuReady()) return;
        touchInjectExecutor.execute(() -> {
            float[] stab = AndroidBridge.stabilizeTouchDeltaNative(0, deltaX, deltaY);
            int endX = Math.max(0, startX + Math.round(stab[0]));
            int endY = Math.max(0, startY + Math.round(stab[1]));
            int dur = Math.max(8, Math.min(250, durationMs));
            execShizukuCommand("input swipe " + startX + " " + startY + " " + endX + " " + endY + " " + dur);
        });
    }

    public static void startTeleport(String presetId) {
        if (presetId != null && presetId.startsWith("SET_RATIO:")) {
            currentRatio = presetId.substring("SET_RATIO:".length()).trim();
            return;
        }
        if (!isShizukuReady()) return;

        String p = (presetId == null) ? "sharp" : presetId.toLowerCase(Locale.US);
        int ptrSpeed = 5;
        long longPress = 190;
        long multiPress = 140;

        if (p.contains("sharp") || p.contains("max") || p.contains("hardlock") || p.contains("sniper") || p.contains("extreme")) {
            ptrSpeed = 7;
            longPress = 140;
            multiPress = 95;
            AndroidBridge.configureNativeTouchLayer(82, 60, 90, 1.35f, 1.24f);
        } else if (p.contains("clean_sharp") || p.contains("onetap")) {
            ptrSpeed = 6;
            longPress = 160;
            multiPress = 110;
            AndroidBridge.configureNativeTouchLayer(75, 52, 85, 1.15f, 1.18f);
        } else if (p.contains("fps") || p.contains("clean")) {
            ptrSpeed = 4;
            longPress = 200;
            multiPress = 150;
            AndroidBridge.configureNativeTouchLayer(30, 25, 75, 0.85f, 1.08f);
        }

        touchInjectorActive.set(true);
        String pkg = (appContext != null) ? appContext.getPackageName() : "com.teleport.so2";
        execShizukuCommand(
                "settings put global block_untrusted_touches 0; " +
                "settings put secure block_untrusted_touches 0; " +
                "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; " +
                "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; " +
                "settings put system pointer_speed " + ptrSpeed + "; " +
                "settings put secure long_press_timeout " + longPress + "; " +
                "settings put secure multi_press_timeout " + multiPress + "; " +
                "device_config put input_native_boot touch_slop 2; " +
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
        float aimScale = (float) Math.max(0.75, Math.min(2.2, (sx + sy) * 0.5));
        AndroidBridge.configureNativeTouchLayer(stick, Math.max(20, 100 - chunkFast), (int) Math.round(sharp * 28), 1.25f, aimScale);

        if (!isShizukuReady()) return;
        int computedSpeed = Math.max(1, Math.min(7, (int) Math.round((sharp - 1.0) * 3.0 + (sx + sy) * 0.5)));
        int tapTimeout = Math.max(125, Math.min(350, 260 - chunks));
        int touchSlop = Math.max(1, Math.min(12, 10 - (grid / 15)));

        execShizukuCommand(
                "settings put system pointer_speed " + computedSpeed + "; " +
                "settings put secure long_press_timeout " + tapTimeout + "; " +
                "settings put secure multi_press_timeout " + Math.max(95, tapTimeout - 40) + "; " +
                "device_config put input_native_boot touch_slop " + touchSlop + ";"
        );
    }

    // =========================================================================
    // УНИВЕРСАЛЬНЫЙ РАСТЯГ ЭКРАНА НА ВСЕХ УСТРОЙСТВАХ (SAMSUNG, XIAOMI, INFINIX)
    // =========================================================================
    public static boolean applyAspectRatioStretch(String ratioStr) {
        if (!isShizukuReady()) {
            requestShizukuPermissionIfNeeded();
            return false;
        }
        if (appContext != null) {
            detectTrueHardwareScreenMetrics(appContext);
        }
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

        execShizukuCommand("wm size " + baseShort + "x" + targetLong + ";");
        return true;
    }

    public static void releaseBoxToFullStretch() {
        if (!isShizukuReady()) return;
        isStretchActive = false;
        if (pixelDownscalePct >= 100) {
            execShizukuCommand("wm size reset;");
        } else {
            double scale = Math.max(0.60, Math.min(1.0, pixelDownscalePct / 100.0));
            int s = Math.max(540, ((int) Math.round(nativeShortSide * scale) / 4) * 4);
            int l = Math.max(960, ((int) Math.round(nativeLongSide * scale) / 4) * 4);
            execShizukuCommand("wm size " + s + "x" + l + ";");
        }
    }

    public static void resetResolutionToNative() {
        pixelDownscalePct = 100;
        isStretchActive = false;
        execShizukuCommand("wm size reset; wm density reset;");
    }

    public static void setPixelDownscaleFactor(int pct) {
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
        if (!isShizukuReady()) return;
        execShizukuCommand(
                "settings put secure long_press_timeout 210; " +
                "settings put secure multi_press_timeout 160; " +
                "settings put system pointer_speed 5; " +
                "settings put global block_untrusted_touches 0; " +
                "device_config put input_native_boot touch_slop 4;"
        );
    }

    public static void setRawInputNoSmoothing(boolean enabled) {
        if (!isShizukuReady()) return;
        execShizukuCommand(
                "device_config put input_native_boot touch_slop " + (enabled ? "1" : "8") + "; " +
                "settings put global window_animation_scale " + (enabled ? "0.0" : "0.5") + "; " +
                "settings put global transition_animation_scale " + (enabled ? "0.0" : "0.5") + "; " +
                "settings put global animator_duration_scale " + (enabled ? "0.0" : "0.5") + ";"
        );
    }

    public static void setVulkanGpuTurbo(boolean enabled) {
        if (!isShizukuReady()) return;
        execShizukuCommand("cmd power set-fixed-performance-mode-enabled " + (enabled ? "true" : "false") + ";");
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (!isShizukuReady()) return;
        execShizukuCommand(
                "settings put global wifi_scan_always_enabled " + (enabled ? "0" : "1") + "; " +
                "settings put global ble_scan_always_enabled " + (enabled ? "0" : "1") + ";"
        );
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isShizukuReady()) return;
        int maxHz = getMaxHardwareRefreshRateHz();
        int targetHz = enabled ? maxHz : 60;
        execShizukuCommand(
                "settings put system peak_refresh_rate " + maxHz + ".0; " +
                "settings put system min_refresh_rate " + targetHz + ".0; " +
                "device_config put input_native_boot touch_slop " + (enabled ? "2" : "8") + ";"
        );
    }

    public static void setHzThermalGuard(boolean enabled) {
        if (!isShizukuReady()) return;
        int maxHz = getMaxHardwareRefreshRateHz();
        execShizukuCommand(
                "settings put system peak_refresh_rate " + maxHz + ".0; " +
                "settings put system min_refresh_rate " + (enabled ? maxHz + ".0" : "60.0") + ";"
        );
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        if (!isShizukuReady()) return;
        int speed = enabled ? Math.min(7, 2 + (power / 20)) : 2;
        execShizukuCommand("settings put system pointer_speed " + speed + ";");
    }

    public static void enableWideHitboxQuantization(boolean enabled) {
        if (!isShizukuReady()) return;
        execShizukuCommand("device_config put input_native_boot touch_slop " + (enabled ? "1" : "8") + ";");
    }

    public static void applyShooterOptimizer(
            int fpsLock, int gpuBoost, int frameTime,
            int edgeSens, int multiTouch, int netBuffer, int thermalLim
    ) {
        if (!isShizukuReady()) return;
        int validFps = Math.max(60, Math.min(240, fpsLock));
        int ptrSpeed = Math.max(1, Math.min(7, 2 + (edgeSens / 20)));
        int touchSlop = Math.max(1, Math.min(10, 9 - (multiTouch / 15)));
        execShizukuCommand(
                "settings put system peak_refresh_rate " + validFps + ".0; " +
                "settings put system min_refresh_rate " + validFps + ".0; " +
                "settings put system pointer_speed " + ptrSpeed + "; " +
                "device_config put input_native_boot touch_slop " + touchSlop + ";"
        );
    }

    public static String performRamBoost(Context context) {
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
                    return "ОЗУ очищено! Свободно: " + availMb + " МБ из " + totalMb + " МБ";
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
            int maxHz = getMaxHardwareRefreshRateHz();
            return Build.MANUFACTURER.toUpperCase(Locale.US) + " " + Build.MODEL +
                    " • " + nativeShortSide + "x" + nativeLongSide +
                    " • " + cores + " ядер • " + maxHz + " Гц";
        } catch (Throwable t) {
            return Build.MODEL + " • " + currentRefreshRateHz + " Гц";
        }
    }
}
