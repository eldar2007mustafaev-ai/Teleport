package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaDrm;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportEngine {

    private static volatile boolean isServerAuthorized = false;
    private static Context appContext;

    // Истинные физические размеры матрицы экрана (не искажаются при повторном запуске)
    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int nativeDensityDpi = 420;
    private static int currentRefreshRateHz = 120;
    private static int maxHardwareRefreshRateHz = 120;

    private static int pixelDownscalePct = 100;
    private static boolean isStretchActive = false;
    private static String currentRatio = "4:3";

    // Движок инжекта касаний (Touch Injection Daemon)
    private static final AtomicBoolean touchInjectorActive = new AtomicBoolean(false);
    private static volatile int touchInjectorMode = 1; // 1 = Recoil Assist, 2 = Ultra Tap, 3 = Full Matrix
    private static volatile int touchInjectorPower = 75;
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

    // ФИКС СНИЖЕНИЯ РАЗРЕШЕНИЯ И ОПРЕДЕЛЕНИЯ FPS/ГЦ:
    // Считываем истинное физическое разрешение из Display.Mode, чтобы не брать уже сжатый wm size!
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
        StringBuilder drmHex = new StringBuilder();
        try {
            UUID widevineUuid = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm drm = new MediaDrm(widevineUuid);
            byte[] drmId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            if (drmId != null) {
                for (byte b : drmId) drmHex.append(String.format("%02x", b));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close();
            else drm.release();
        } catch (Throwable ignored) {}

        String boardInfo = Build.BOARD + "|" + Build.BRAND + "|" + Build.DEVICE + "|" + Build.HARDWARE + "|" + Build.MODEL;
        String aid = "";
        try {
            Context targetCtx = (context != null) ? context : appContext;
            if (targetCtx != null) {
                aid = Settings.Secure.getString(targetCtx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (aid == null) aid = "";
            }
        } catch (Throwable ignored) {}

        return AndroidBridge.computeNativeHwidOnly(drmHex.toString(), aid, boardInfo);
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

    // Все команды проходят через нативный C++ шлюз (Пункты #3, #17, #21)
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
    // ИНЖЕКТ КАСАНИЙ (TOUCH INJECTION & LOW-LATENCY INPUT ENGINE)
    // =========================================================================
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
            int ptrSpeed = Math.min(7, 3 + (touchInjectorPower / 25));
            int touchSlop = Math.max(1, 8 - (touchInjectorPower / 15));
            long phaseOffsetNs = (touchInjectorMode == 3) ? 250000L : 500000L;

            execShizukuCommand(
                    "settings put global block_untrusted_touches 0; " +
                    "settings put secure block_untrusted_touches 0; " +
                    "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; " +
                    "device_config put input_native_boot touch_slop " + touchSlop + "; " +
                    "settings put system pointer_speed " + ptrSpeed + "; " +
                    "settings put secure long_press_timeout 150; " +
                    "settings put secure multi_press_timeout 110; " +
                    "setprop debug.sf.early_phase_offset_ns " + phaseOffsetNs + "; " +
                    "setprop debug.sf.early_gl_phase_offset_ns " + phaseOffsetNs + "; " +
                    "setprop debug.input. resampling 0;"
            );
        } else {
            execShizukuCommand(
                    "device_config put input_native_boot touch_slop 8; " +
                    "settings put system pointer_speed 2; " +
                    "settings put secure long_press_timeout 250;"
            );
        }
    }

    // Аппаратный инжект мгновенного тапа через input tap в фоновом потоке
    public static void injectTouchTap(final int x, final int y) {
        if (!touchInjectorActive.get() || !isServerAuthorized || !isShizukuReady()) return;
        touchInjectExecutor.execute(() -> {
            int safeX = Math.max(0, Math.min(nativeLongSide, x));
            int safeY = Math.max(0, Math.min(nativeShortSide, y));
            execShizukuCommand("input tap " + safeX + " " + safeY);
        });
    }

    // Аппаратный инжект микро-свайпа (компенсация вертикальной отдачи / рывок)
    public static void injectRecoilSwipe(final int startX, final int startY, final int deltaX, final int deltaY, final int durationMs) {
        if (!touchInjectorActive.get() || !isServerAuthorized || !isShizukuReady()) return;
        touchInjectExecutor.execute(() -> {
            int endX = Math.max(0, startX + deltaX);
            int endY = Math.max(0, startY + deltaY);
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
            longPress = 150;
            multiPress = 105;
        } else if (p.contains("clean_sharp") || p.contains("onetap")) {
            ptrSpeed = 6;
            longPress = 170;
            multiPress = 120;
        } else if (p.contains("fps") || p.contains("clean")) {
            ptrSpeed = 4;
            longPress = 210;
            multiPress = 160;
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
        int tapTimeout = Math.max(120, Math.min(350, 260 - chunks));
        int touchSlop = Math.max(1, Math.min(16, 10 - (grid / 15)));

        execShizukuCommand(
                "settings put system pointer_speed " + computedSpeed + "; " +
                "settings put secure long_press_timeout " + tapTimeout + "; " +
                "settings put secure multi_press_timeout " + Math.max(100, tapTimeout - 40) + "; " +
                "device_config put input_native_boot touch_slop " + touchSlop + ";"
        );
    }

    // =========================================================================
    // ФИКС РАСТЯГА ЭКРАНА И СНИЖЕНИЯ РАЗРЕШЕНИЯ (ПИКСЕЛЕЙ)
    // =========================================================================
    public static boolean applyAspectRatioStretch(String ratioStr) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return false;
        if (ratioStr != null && !ratioStr.trim().isEmpty()) {
            currentRatio = ratioStr.trim().toUpperCase(Locale.US);
        }
        isStretchActive = true;

        double scale = Math.max(0.50, Math.min(1.0, pixelDownscalePct / 100.0));
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

        baseShort = Math.max(360, (baseShort / 2) * 2);
        targetLong = Math.max(480, (targetLong / 2) * 2);

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
        double scale = Math.max(0.50, Math.min(1.0, pixelDownscalePct / 100.0));
        int s = Math.max(360, ((int) Math.round(nativeShortSide * scale) / 2) * 2);
        int l = Math.max(640, ((int) Math.round(nativeLongSide * scale) / 2) * 2);
        execShizukuCommand("wm size " + s + "x" + l + ";");
    }

    public static void resetResolutionToNative() {
        pixelDownscalePct = 100;
        isStretchActive = false;
        execShizukuCommand("wm size reset; wm density reset; settings put system accelerometer_rotation 1;");
    }

    // Исправленное снижение разрешения (работает как отдельно, так и поверх активного растяга!)
    public static void setPixelDownscaleFactor(int pct) {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized()) return;
        pixelDownscalePct = Math.max(50, Math.min(100, pct));
        if (!isShizukuReady()) return;

        if (pixelDownscalePct >= 100 && !isStretchActive) {
            execShizukuCommand("wm size reset; wm density reset;");
            return;
        }

        if (isStretchActive) {
            applyAspectRatioStretch(currentRatio);
            int scaledDpi = Math.max(180, (int) Math.round(nativeDensityDpi * (pixelDownscalePct / 100.0)));
            execShizukuCommand("wm density " + scaledDpi + ";");
        } else {
            double factor = pixelDownscalePct / 100.0;
            int w = Math.max(360, ((int) Math.round(nativeShortSide * factor) / 2) * 2);
            int h = Math.max(640, ((int) Math.round(nativeLongSide * factor) / 2) * 2);
            int d = Math.max(180, (int) Math.round(nativeDensityDpi * factor));
            execShizukuCommand("wm size " + w + "x" + h + "; wm density " + d + ";");
        }
    }

    public static void healTouchAndRestart() {
        if (!isServerAuthorized || !AndroidBridge.isNativeServerAuthorized() || !isShizukuReady()) return;
        execShizukuCommand(
                "settings put secure long_press_timeout 250; " +
                "settings put secure multi_press_timeout 200; " +
                "settings put system pointer_speed 3; " +
                "settings put global block_untrusted_touches 0; " +
                "settings put global window_animation_scale 0.5; " +
                "settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5;"
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
        int targetHz = Math.max(60, Math.min(240, hz));
        execShizukuCommand(
                "setprop debug.sf.early_phase_offset_ns " + (enabled ? "500000" : "1000000") + "; " +
                "settings put system peak_refresh_rate " + targetHz + ".0; " +
                "settings put system min_refresh_rate " + (enabled ? targetHz + ".0" : "60.0") + ";"
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
        int validFps = Math.max(60, Math.min(240, fpsLock));
        execShizukuCommand(
                "settings put system peak_refresh_rate " + validFps + ".0; " +
                "settings put system min_refresh_rate " + validFps + ".0; " +
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
        return "Кэш фоновых процессов очищен!";
    }

    // Реальное сканирование герцовки, ядер и разрешения
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
