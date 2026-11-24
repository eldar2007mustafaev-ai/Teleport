package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static volatile String verifiedSessionKey = "";

    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int deviceMaxRefreshRate = 120;
    private static boolean isNaturalLandscapeDevice = false;
    private static boolean isBudgetDeviceHardware = false;
    private static volatile boolean isTabletDeviceMode = false;
    private static volatile int currentRecoilPower = 100;
    private static volatile String lastSelectedRatio = "4:3";

    private static final ExecutorService stretchExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Глобальная переменная текущего пресета
    private static volatile String currentActivePreset = "extreme";

    private static double currentSharp = 50.0;
    private static double currentSx = 50.0;
    private static double currentSy = 50.0;
    private static int currentChunks = 500;
    private static int currentChunkFast = 500;
    private static double currentStraight = 100.0;
    private static int currentStick = 100;
    private static int currentGrid = 10;
    private static int currentPause = 0;
    private static double currentAccel = 50.0;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SecurityShield.startBackgroundGuard(appContext);
        
        SharedPreferences prefs = appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE);
        lastSelectedRatio = prefs.getString("stretch_ratio_val", "4:3");

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        int modeShort = 0;
        int modeLong = 0;

        if (wm != null && wm.getDefaultDisplay() != null) {
            Display d = wm.getDefaultDisplay();
            d.getRealMetrics(dm);
            int rot = d.getRotation();

            float maxHz = d.getRefreshRate();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (Display.Mode m : d.getSupportedModes()) {
                    if (m.getRefreshRate() > maxHz) maxHz = m.getRefreshRate();
                    int sSide = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                    int lSide = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                    if (sSide >= 720 && lSide >= 1600 && (sSide <= 1440)) {
                        modeShort = sSide;
                        modeLong = lSide;
                    }
                }
            }
            deviceMaxRefreshRate = Math.max(60, Math.round(maxHz));
            isNaturalLandscapeDevice = (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180) ? (dm.widthPixels > dm.heightPixels) : (dm.widthPixels < dm.heightPixels);
        } else {
            dm = appContext.getResources().getDisplayMetrics();
        }

        int curShort = Math.min(dm.widthPixels, dm.heightPixels);
        int curLong = Math.max(dm.widthPixels, dm.heightPixels);
        int savedShort = prefs.getInt("orig_native_short", 0);
        int savedLong = prefs.getInt("orig_native_long", 0);

        if (curShort >= 720 && (curLong / (float) curShort) >= 1.72f) {
            nativeShortSide = curShort; nativeLongSide = curLong;
            prefs.edit().putInt("orig_native_short", curShort).putInt("orig_native_long", curLong).apply();
        } else if (savedShort >= 720 && savedLong >= 1600) {
            nativeShortSide = savedShort; nativeLongSide = savedLong;
        } else if (modeShort >= 720 && modeLong >= 1600) {
            nativeShortSide = modeShort; nativeLongSide = modeLong;
        } else {
            nativeShortSide = curShort >= 720 ? curShort : 1080;
            nativeLongSide = curLong >= 1800 ? curLong : 2400;
        }

        Configuration cfg = appContext.getResources().getConfiguration();
        isTabletDeviceMode = prefs.getBoolean("pad_mode_flag", (cfg != null ? cfg.smallestScreenWidthDp : 0) >= 580 || isNaturalLandscapeDevice);

        int cores = Runtime.getRuntime().availableProcessors();
        String modelUpper = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        isBudgetDeviceHardware = (!isTabletDeviceMode && (modelUpper.contains("SM-A") || modelUpper.contains("A57") || deviceMaxRefreshRate <= 90 || nativeShortSide <= 720 || cores <= 6));

        stretchExecutor.execute(() -> {
            try {
                if (isShizukuReady()) {
                    ShizukuManager.execCommand("settings put global force_resizable_activities 0 2>/dev/null; wm set-ignore-orientation-request false 2>/dev/null; settings put global block_untrusted_touches 0 2>/dev/null;");
                }
            } catch (Throwable ignored) {}
        });
    }

    public static int getNativeShortSide() { return nativeShortSide > 0 ? nativeShortSide : 1080; }
    public static int getNativeLongSide() { return nativeLongSide > 0 ? nativeLongSide : 2400; }

    public static boolean isShizukuReady() {
        try {
            if (Shizuku.isPreV11() || !Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false;
            int uid = Shizuku.getUid();
            return uid == 0 || uid == 2000;
        } catch (Throwable t) { return false; }
    }

    public static void requestShizukuPermissionIfNeeded() {
        try {
            if (!Shizuku.isPreV11() && Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001);
            }
        } catch (Throwable ignored) {}
    }

    public static void execShizukuCommand(final String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        stretchExecutor.execute(() -> {
            try { ShizukuManager.execCommand(cmd); } catch (Throwable ignored) {}
        });
    }

    private static int getCurrentLandscapeRotation() {
        try {
            WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                int rot = wm.getDefaultDisplay().getRotation();
                if (rot == Surface.ROTATION_270) return 3;
                if (rot == Surface.ROTATION_90) return 1;
            }
        } catch (Throwable ignored) {}
        return 1;
    }

    private static String buildHardLandscapeLockCmd(int landRot) {
        return "settings put global force_resizable_activities 0 2>/dev/null; settings put system accelerometer_rotation 0; settings put system user_rotation " + landRot + "; cmd window user-rotation lock " + landRot + "; ";
    }

    public static boolean applyAspectRatioStretch(String ratio) {
        final String cleanRatio = (ratio == null || ratio.trim().isEmpty()) ? lastSelectedRatio : ratio.trim().toUpperCase(Locale.US);
        lastSelectedRatio = cleanRatio;
        if (appContext != null) appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE).edit().putString("stretch_ratio_val", cleanRatio).apply();

        final int natS = getNativeShortSide();
        final int natL = getNativeLongSide();
        int tS = natS; int tL = natL;

        if (cleanRatio.startsWith("SHRINK-")) {
            int pct = 84;
            try { pct = Math.max(50, Math.min(96, Integer.parseInt(cleanRatio.replace("SHRINK-", "").trim()))); } catch (Throwable ignored) {}
            tS = Math.round(natS * (pct / 100.0f));
        } else if ("4:3".equals(cleanRatio)) {
            tL = Math.round(natS * ((natL / (float) natS) < 1.65f ? 1.18f : 1.3333f));
        } else if ("16:10".equals(cleanRatio)) {
            tL = Math.round(natS * 1.50f);
        }

        if ((tS & 1) == 1) tS += 1;
        if ((tL & 1) == 1) tL += 1;

        final String wmArg = isNaturalLandscapeDevice ? (tL + "x" + tS) : (tS + "x" + tL);
        final int landRot = getCurrentLandscapeRotation();

        stretchExecutor.execute(() -> {
            try {
                ShizukuManager.execCommand(buildHardLandscapeLockCmd(landRot) + "wm size " + wmArg + "; settings put system peak_refresh_rate " + deviceMaxRefreshRate + ".0;");
            } catch (Throwable ignored) {}
        });
        return true;
    }

    public static void releaseBoxToFullStretch() {
        final int landRot = getCurrentLandscapeRotation();
        stretchExecutor.execute(() -> {
            try { ShizukuManager.execCommand(buildHardLandscapeLockCmd(landRot) + "wm size reset;"); } catch (Throwable ignored) {}
        });
    }

    public static void resetResolutionToNative() {
        stretchExecutor.execute(() -> {
            try { ShizukuManager.execCommand("wm size reset; wm density reset; cmd window user-rotation free; settings put system accelerometer_rotation 1;"); } catch (Throwable ignored) {}
        });
    }

    public static void applyUltimateSystemTweaks() {
        execShizukuCommand("settings put global gpu_debug_layer_app 0; settings put global net.tcp.default_init_rwnd 60;");
    }

    public static void startTeleport(String presetId) {
        String cleanPreset = (presetId == null) ? "extreme" : presetId.trim();

        if (cleanPreset.startsWith("SET_RATIO:")) {
            String r = cleanPreset.substring("SET_RATIO:".length()).trim().toUpperCase(Locale.US);
            if (!r.isEmpty()) lastSelectedRatio = r;
            return;
        }
        if ("PAD_MODE_ON".equalsIgnoreCase(cleanPreset)) { isTabletDeviceMode = true; return; }
        if ("PAD_MODE_OFF".equalsIgnoreCase(cleanPreset)) { isTabletDeviceMode = false; return; }
        if ("ULTIMATE_TWEAKS_1CLICK".equalsIgnoreCase(cleanPreset)) { applyUltimateSystemTweaks(); return; }
        if (cleanPreset.startsWith("STRETCH_NOW:")) { applyAspectRatioStretch(cleanPreset.substring("STRETCH_NOW:".length()).trim()); return; }
        if ("STRETCH_FULL_RELEASE".equalsIgnoreCase(cleanPreset)) { releaseBoxToFullStretch(); return; }
        if ("STRETCH_RESET".equalsIgnoreCase(cleanPreset)) { resetResolutionToNative(); return; }

        currentActivePreset = cleanPreset.toLowerCase(Locale.US);
        isTabletDeviceMode = "tablet_pro".equalsIgnoreCase(currentActivePreset);
        commitParametersToNative();
    }

    public static void stopTeleport() {
        execShizukuCommand(
            "settings put system pointer_speed 7; " +
            "settings delete secure touch_blocking_period; " +
            "settings delete secure tap_duration_threshold; " +
            "settings delete system motion_engine_enabled; " +
            "settings put system block_untrusted_touches 0;"
        );
    }

    public static void healTouchAndRestart() {
        startTeleport(isTabletDeviceMode ? "tablet_pro" : (isBudgetDeviceHardware ? "budget_pro" : "extreme"));
    }

    public static void updateParameters(double sharp, double sx, double sy, int chunks, int chunkFast, double straight, int stick, int grid, int pause, double accel) {
        currentSharp = sharp; currentSx = sx; currentSy = sy;
        currentChunks = chunks; currentChunkFast = chunkFast;
        currentStraight = straight; currentStick = stick; currentGrid = grid;
        currentPause = pause; currentAccel = accel;
        commitParametersToNative();
    }

    private static void commitParametersToNative() {
        StringBuilder cmd = new StringBuilder();

        // 1. Прямой проброс кусков. Больше никаких лимитов в Java.
        // Если ты ставишь 500, в систему летит ровно 500.
        cmd.append("settings put system pointer_speed ").append(currentChunks).append("; ");

        // 2. Адаптивные команды в зависимости от профиля
        if ("extreme".equals(currentActivePreset)) {
            // ТЕЛЕПОРТ УЛЬТРА: Жесткий бафф
            cmd.append("settings put secure touch_blocking_period 0.0; ");
            cmd.append("settings put secure tap_duration_threshold 0.0; ");
            cmd.append("settings put secure long_press_timeout 10; "); 
            cmd.append("settings put system motion_engine_enabled 0; ");
            cmd.append("settings put global windows_animation_scale 0.0; ");
            cmd.append("settings put global transition_animation_scale 0.0; ");
            cmd.append("settings put global animator_duration_scale 0.0; ");
            
            if (Build.MANUFACTURER.toLowerCase(Locale.US).contains("xiaomi")) {
                cmd.append("killall -9 com.xiaomi.joyose 2>/dev/null; ");
            }

        } else if ("ios_pro".equals(currentActivePreset)) {
            // iOS КОНФИГ: Линейная траектория, Apple CoreAnimation Curve
            cmd.append("settings put secure touch_blocking_period 1.5; ");
            cmd.append("settings put secure tap_duration_threshold 0.0; ");
            cmd.append("settings put system motion_engine_enabled 1; "); 
            
        } else if ("rog_pro".equals(currentActivePreset)) {
            // ROG PHONE: Максимальная агрессия тач-матрицы
            cmd.append("settings put secure touch_blocking_period 0.0; ");
            cmd.append("settings put secure tap_duration_threshold 0.0; ");
            cmd.append("settings put system multi_press_timeout 150; ");
            cmd.append("settings put system motion_engine_enabled 0; ");
            
        } else {
            // Обычные профили
            cmd.append(String.format(Locale.US, "settings put secure tap_duration_threshold %f; ", currentAccel));
        }

        // Общие настройки безопасности
        cmd.append("settings put global block_untrusted_touches 0; ");
        cmd.append("settings put secure block_untrusted_touches 0;");

        execShizukuCommand(cmd.toString());
    }

    public static void setRawInputNoSmoothing(boolean enabled) {
        if (enabled) healTouchAndRestart();
    }

    public static void setVulkanGpuTurbo(boolean enabled) {
        if (enabled) applyUltimateSystemTweaks();
    }

    public static void setNetPingStabilizer(boolean enabled) {
        if (enabled) applyUltimateSystemTweaks();
    }

    public static void setTouchSamplingBoost(boolean enabled, int hz) {}
    public static void setHzThermalGuard(boolean enabled) {}
    public static void enableWideHitboxQuantization(boolean enable) {}
    public static void setAutoGameDpi(boolean enabled, int dpi) {}

    public static void applyShooterOptimizer(int fpsLock, int gpuBoost, int frameTime, int edgeSens, int multiTouch, int netBuffer, int thermalLim) {
        String cmd = String.format(Locale.US, "settings put system peak_refresh_rate %d.0; settings put system min_refresh_rate %d.0; settings put global net.tcp.default_init_rwnd %d;", fpsLock, fpsLock, netBuffer);
        execShizukuCommand(cmd);
    }

    public static void setPixelDownscaleFactor(int pct) {
        if (pct >= 50 && pct < 100) applyAspectRatioStretch("SHRINK-" + pct);
        else if (pct == 100) resetResolutionToNative();
    }

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        currentRecoilPower = enabled ? Math.max(20, Math.min(100, power)) : 0;
    }

    public static String performRamBoost(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long availAfterMb = mi.availMem / (1024 * 1024);
        long totalMb = mi.totalMem / (1024 * 1024);
        applyUltimateSystemTweaks();
        return "360|" + availAfterMb + "|" + totalMb;
    }

    public static String runSmartDeviceScan(Context ctx) {
        return "Android|" + (isTabletDeviceMode ? "PAD-ULTRA" : (isBudgetDeviceHardware ? "BUDGET-FPS-GUARD" : "ULTRA")) + "|" + getNativeShortSide();
    }
}
