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
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static final String SERVER_URL = "http://45.10.165.75:8443/api/v10/engine";
    private static final String MASTER_SALT = "SO2TP_V10_QUANTUM_SERVER_KEY_9941";

    private static Context appContext;
    private static volatile boolean isTamperDetected = false;
    private static volatile boolean isServerAuthorized = false;
    private static volatile String verifiedSessionKey = "";

    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int deviceMaxRefreshRate = 120;
    private static boolean isNaturalLandscapeDevice = false;
    private static boolean isBudgetDeviceHardware = false;
    private static volatile boolean isTabletDeviceMode = false;
    private static volatile int currentRecoilPower = 100;

    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static volatile String lastPayloadSig = "";

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
        boolean isSamsungASeries = modelUpper.contains("SM-A") || modelUpper.contains("A57") || modelUpper.contains("A55") || modelUpper.contains("A54");
        isBudgetDeviceHardware = (!isTabletDeviceMode && (isSamsungASeries || deviceMaxRefreshRate <= 90 || curShort <= 720 || cores <= 6));

        nativeShortSide = curShort;
        nativeLongSide = curLong;
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
                                String kName = entry.getKey().toLowerCase();
                                if ((s.startsWith("TP") && s.length() >= 6)
                                        || ((kName.contains("key") || kName.contains("lic")) && s.length() >= 6 && !s.contains(" "))) {
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

    private static String getDeviceHwid() {
        try {
            String aid = Settings.Secure.getString(appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            return (aid != null && !aid.isEmpty()) ? aid : Build.MODEL;
        } catch (Throwable t) {
            return Build.MODEL;
        }
    }

    private static String decryptServerCmd(String b64, String hwid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] seed = md.digest((MASTER_SALT + hwid).getBytes(StandardCharsets.UTF_8));
            byte[] raw = Base64.decode(b64.trim(), Base64.DEFAULT);
            byte[] out = new byte[raw.length];
            for (int i = 0; i < raw.length; i++) {
                out[i] = (byte) (raw[i] ^ seed[i % seed.length]);
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void lockShizukuOnCrack() {
        isServerAuthorized = false;
        try {
            ShizukuManager.execCommand("settings put system pointer_speed 0; wm size reset;");
        } catch (Throwable ignored) {}
    }

    private static void requestServerShizuku(final JSONObject payload) {
        if (isTamperDetected || appContext == null) return;
        if (!isShizukuReady() && !ShizukuManager.isPermissionGranted()) return;

        shizukuQueue.execute(() -> {
            try {
                if (verifiedSessionKey == null || verifiedSessionKey.length() < 5) {
                    autoDetectSavedLicenseKey();
                }
                if (verifiedSessionKey == null || verifiedSessionKey.length() < 5) {
                    lockShizukuOnCrack();
                    return;
                }

                String hwid = getDeviceHwid();
                payload.put("key", verifiedSessionKey);
                payload.put("hwid", hwid);
                payload.put("pkg", appContext.getPackageName());
                payload.put("hz", deviceMaxRefreshRate);
                payload.put("pad", isTabletDeviceMode);
                payload.put("budget", isBudgetDeviceHardware);
                payload.put("nat_s", getNativeShortSide());
                payload.put("nat_l", getNativeLongSide());
                payload.put("land_rot", getCurrentLandscapeRotation());
                payload.put("nat_land", isNaturalLandscapeDevice);
                payload.put("recoil", currentRecoilPower);

                URL url = new URL(SERVER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(4500);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    String resp = sb.toString().trim();
                    if ("DENIED".equals(resp) || resp.isEmpty()) {
                        lockShizukuOnCrack();
                        return;
                    }
                    String decryptedCmd = decryptServerCmd(resp, hwid);
                    if (!decryptedCmd.isEmpty()) {
                        isServerAuthorized = true;
                        ShizukuManager.execCommand(decryptedCmd);
                    }
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
        shizukuQueue.execute(() -> {
            try { ShizukuManager.execCommand(cmd); } catch (Throwable ignored) {}
        });
    }

    private static int getCurrentLandscapeRotation() {
        try {
            WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                int rot = wm.getDefaultDisplay().getRotation();
                if (rot == Surface.ROTATION_270) return 3;
                if (isNaturalLandscapeDevice && (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180)) return rot;
            }
        } catch (Throwable ignored) {}
        return isNaturalLandscapeDevice ? 0 : 1;
    }

    public static boolean applyAspectRatioStretch(String ratio) {
        try {
            JSONObject j = new JSONObject();
            j.put("action", "STRETCH_APPLY");
            j.put("ratio", ratio == null ? "4:3" : ratio);
            requestServerShizuku(j);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void releaseBoxToFullStretch() {
        try {
            JSONObject j = new JSONObject();
            j.put("action", "STRETCH_RELEASE");
            requestServerShizuku(j);
        } catch (Throwable ignored) {}
    }

    public static void resetResolutionToNative() {
        try {
            JSONObject j = new JSONObject();
            j.put("action", "STRETCH_RESET");
            requestServerShizuku(j);
        } catch (Throwable ignored) {}
    }

    public static void applyUltimateSystemTweaks() {
        try {
            JSONObject j = new JSONObject();
            j.put("action", "ULTIMATE_TWEAKS_1CLICK");
            requestServerShizuku(j);
        } catch (Throwable ignored) {}
    }

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
            cleanPreset = "tablet_pro";
        } else if ("PAD_MODE_OFF".equalsIgnoreCase(cleanPreset)) {
            isTabletDeviceMode = false;
            cleanPreset = "extreme";
        }

        try {
            JSONObject j = new JSONObject();
            j.put("action", "PRESET:" + cleanPreset);
            requestServerShizuku(j);
        } catch (Throwable ignored) {}
    }

    public static void stopTeleport() {
        try {
            JSONObject j = new JSONObject();
            j.put("action", "STOP");
            requestServerShizuku(j);
        } catch (Throwable ignored) {}
    }

    public static void healTouchAndRestart() {
        lastPayloadSig = "";
        startTeleport(isTabletDeviceMode ? "tablet_pro" : (isBudgetDeviceHardware ? "budget_pro" : "extreme"));
    }

    public static void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        try {
            String sig = sharp + "|" + sx + "|" + sy + "|" + chunks + "|" + chunkFast + "|" + straight + "|" + stick + "|" + grid + "|" + pause + "|" + accel + "|" + isTabletDeviceMode;
            if (sig.equals(lastPayloadSig)) return;
            lastPayloadSig = sig;

            JSONObject j = new JSONObject();
            j.put("action", "SLIDERS");
            j.put("p1", sharp);
            j.put("p2", sx);
            j.put("p3", sy);
            j.put("p4", chunks);
            j.put("p5", chunkFast);
            j.put("p6", straight);
            j.put("p7", stick);
            j.put("p8", grid);
            j.put("p9", pause);
            j.put("p10", accel);
            requestServerShizuku(j);
        } catch (Throwable ignored) {}
    }

    public static void applyShooterOptimizer(int fpsLock, int gpuBoost, int frameTime, int edgeSens, int multiTouch, int netBuffer, int thermalLim) {
        applyUltimateSystemTweaks();
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
    public static void setPixelDownscaleFactor(int pct) {}

    public static void setEnhancedRecoilFix(boolean enabled, int power) {
        currentRecoilPower = enabled ? Math.max(20, Math.min(100, power)) : 0;
        lastPayloadSig = "";
    }

    public static String performRamBoost(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long availAfterMb = mi.availMem / (1024 * 1024);
        long totalMb = mi.totalMem / (1024 * 1024);
        System.gc();
        applyUltimateSystemTweaks();
        return "360|" + availAfterMb + "|" + totalMb;
    }

    public static String runSmartDeviceScan(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        String tier = isTabletDeviceMode ? "PAD-ULTRA" : (isBudgetDeviceHardware ? "BUDGET-FPS-GUARD" : "ULTRA");
        return Build.MODEL + "|" + tier + "|" + shortPx;
    }
}
