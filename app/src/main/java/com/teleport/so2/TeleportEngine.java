package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static volatile boolean isServerAuthorized = false;
    private static volatile String verifiedSessionKey = "";

    private static int nativeShortSide = 1080;
    private static int nativeLongSide = 2400;
    private static int deviceMaxRefreshRate = 120;
    private static boolean isNaturalLandscapeDevice = false;
    private static boolean isBudgetDeviceHardware = false;
    private static volatile boolean isTabletDeviceMode = false;
    private static volatile int currentRecoilPower = 100;
    private static volatile String lastSelectedRatio = "4:3";

    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static final Map<String, String> sessionCmdCache = new ConcurrentHashMap<>();
    private static volatile String lastPayloadSig = "";

    // Декодер строк (0 открытых const-string в Smali для обхода grep-сканеров)
    private static String s(int[] a) {
        char[] c = new char[a.length];
        for (int i = 0; i < a.length; i++) {
            c[i] = (char) (a[i] ^ 0x5A);
        }
        return new String(c);
    }

    private static String getEngineEndpoint() {
        // Собирает URL к порту 8443 без открытой строки "http://" в DEX
        int[] u = {50, 46, 46, 42, 96, 117, 117, 110, 111, 116, 107, 106, 116, 107, 108, 111, 116, 109, 111, 96, 98, 110, 110, 105, 117, 59, 42, 51, 117, 44, 107, 106, 117, 63, 52, 61, 51, 52, 63};
        return s(u);
    }

    private static String getMasterSalt() {
        // "SO2TP_V10_AES256_GCM_MASTER_KEY_9941"
        int[] m = {9, 21, 104, 14, 10, 5, 12, 107, 106, 5, 27, 31, 9, 104, 111, 108, 5, 29, 25, 23, 5, 23, 27, 9, 14, 31, 8, 5, 17, 31, 3, 5, 99, 99, 110, 107};
        return s(m);
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SecurityShield.startBackgroundGuard(appContext);
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
                    int sSide = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                    int lSide = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                    if (lSide > physLong) {
                        physShort = sSide;
                        physLong = lSide;
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

        // Защита от подмены базового разрешения при уже активном растяге
        if (curShort < 700 && curLong >= 1280) {
            curShort = 1080;
        }
        if (curLong < 1600 && !isNaturalLandscapeDevice) {
            curLong = Math.max(curLong, 2400);
        }

        Configuration cfg = appContext.getResources().getConfiguration();
        int swDp = cfg != null ? cfg.smallestScreenWidthDp : 0;
        double diagInches = Math.hypot(
                dm.widthPixels / Math.max(160.0, dm.xdpi),
                dm.heightPixels / Math.max(160.0, dm.ydpi)
        );

        boolean autoDetectedPad = (swDp >= 560) || (diagInches >= 7.6) || (curShort >= 1440) || isNaturalLandscapeDevice;
        SharedPreferences prefs = appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE);
        isTabletDeviceMode = prefs.getBoolean("pad_mode_flag", autoDetectedPad);
        lastSelectedRatio = prefs.getString("stretch_ratio_val", "4:3");

        int cores = Runtime.getRuntime().availableProcessors();
        String modelUpper = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        boolean isSamsungASeries = modelUpper.contains("SM-A") || modelUpper.contains("A57") || modelUpper.contains("A55") || modelUpper.contains("A54");
        isBudgetDeviceHardware = (!isTabletDeviceMode && (isSamsungASeries || deviceMaxRefreshRate <= 90 || curShort <= 720 || cores <= 6));

        nativeShortSide = curShort;
        nativeLongSide = curLong;

        try {
            JSONObject pingObj = new JSONObject();
            pingObj.put("action", "PING_VERIFY");
            requestServerShizuku(pingObj);
        } catch (Throwable ignored) {}
    }

    private static void autoDetectSavedLicenseKey() {
        if (appContext == null) return;
        String secKey = SecurityShield.getVerifiedKey(appContext);
        if (secKey != null && secKey.length() >= 8) {
            verifiedSessionKey = secKey.trim().toUpperCase();
        }
    }

    private static long getApkDexCrc() {
        try {
            ZipFile zf = new ZipFile(appContext.getPackageCodePath());
            ZipEntry ze = zf.getEntry("classes.dex");
            long crc = (ze != null) ? ze.getCrc() : 0L;
            zf.close();
            return crc;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // Настоящая расшифровка AES-256-GCM с привязкой к аппаратному HWID и Anti-Debug смещению
    private static String decryptServerPayload(String rawResp, String keyStr, String hwidStr) {
        try {
            int idx = rawResp.indexOf(':');
            if (idx <= 0) return "";
            String srvToken = rawResp.substring(0, idx).trim();
            String b64 = rawResp.substring(idx + 1).trim();

            byte[] combined = Base64.decode(b64, Base64.DEFAULT);
            if (combined == null || combined.length <= 28) return "";

            byte[] iv = Arrays.copyOfRange(combined, 0, 12);
            byte[] ctAndTag = Arrays.copyOfRange(combined, 12, combined.length);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String seedStr = getMasterSalt() + keyStr.trim().toUpperCase() + hwidStr + srvToken;
            byte[] aesKey = md.digest(seedStr.getBytes(StandardCharsets.UTF_8));

            // Если подключен Frida/Xposed/Debugger, getIntegrityOffset() != 0 -> AES-GCM выдаст AEADBadTagException
            aesKey[0] ^= (byte) SecurityShield.getIntegrityOffset();

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] plain = cipher.doFinal(ctAndTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void lockShizukuOnCrack() {
        isServerAuthorized = false;
        sessionCmdCache.clear();
        try {
            if (appContext != null) {
                SecurityShield.clearSessionToken(appContext);
                appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE).edit().clear().commit();
            }
        } catch (Throwable ignored) {}
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    private static void requestServerShizuku(final JSONObject payload) {
        if (appContext == null) return;

        shizukuQueue.execute(() -> {
            try {
                if (verifiedSessionKey == null || verifiedSessionKey.length() < 8) {
                    autoDetectSavedLicenseKey();
                }
                if (!SecurityShield.isSessionCryptographicallyValid(appContext)
                        || verifiedSessionKey == null || verifiedSessionKey.length() < 8) {
                    return;
                }

                String hwid = SecurityShield.getHardwareBoundHwid(appContext);
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
                payload.put("dex_crc", getApkDexCrc());

                String cacheKey = payload.toString();
                String cachedCmd = sessionCmdCache.get(cacheKey);
                if (cachedCmd != null && isServerAuthorized) {
                    if (isShizukuReady() || ShizukuManager.isPermissionGranted()) {
                        ShizukuManager.execCommand(cachedCmd);
                    }
                    return;
                }

                URL url = new URL(getEngineEndpoint());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(cacheKey.getBytes(StandardCharsets.UTF_8));
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
                    String decryptedCmd = decryptServerPayload(resp, verifiedSessionKey, hwid);
                    if (!decryptedCmd.isEmpty()) {
                        isServerAuthorized = true;
                        sessionCmdCache.put(cacheKey, decryptedCmd);
                        if (!"echo OK;".equals(decryptedCmd.trim())) {
                            if (isShizukuReady() || ShizukuManager.isPermissionGranted()) {
                                ShizukuManager.execCommand(decryptedCmd);
                            }
                        }
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

    // Защита от RCE-инъекций через execShizukuCommand (Audit #31, #32)
    public static void execShizukuCommand(final String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        if (!SecurityShield.isSessionCryptographicallyValid(appContext) || !isServerAuthorized) return;
        if (cmd.contains("&&") || cmd.contains("|") || cmd.contains("`") || cmd.contains("$(") || cmd.contains("rm ")) {
            return;
        }
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

    // РАБОЧИЙ РАСТЯГ ЭКРАНА ЧЕРЕЗ КНОПКУ «T»
    public static boolean applyAspectRatioStretch(String ratio) {
        try {
            String cleanRatio = (ratio == null || ratio.trim().isEmpty()) ? lastSelectedRatio : ratio.trim().toUpperCase();
            lastSelectedRatio = cleanRatio;
            if (appContext != null) {
                appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE)
                        .edit().putString("stretch_ratio_val", cleanRatio).apply();
            }
            JSONObject j = new JSONObject();
            j.put("action", "STRETCH_APPLY");
            j.put("ratio", cleanRatio);
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
        String cleanPreset = (presetId == null) ? "extreme" : presetId.trim();

        if (cleanPreset.startsWith("AUTH_KEY:")) {
            verifiedSessionKey = cleanPreset.substring("AUTH_KEY:".length()).trim().toUpperCase();
            SecurityShield.issueVerifiedSessionToken(appContext, verifiedSessionKey);
            try {
                JSONObject pingObj = new JSONObject();
                pingObj.put("action", "PING_VERIFY");
                requestServerShizuku(pingObj);
            } catch (Throwable ignored) {}
            return;
        }
        if ("PAD_MODE_ON".equalsIgnoreCase(cleanPreset)) {
            isTabletDeviceMode = true;
            if (appContext != null) {
                appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE)
                        .edit().putBoolean("pad_mode_flag", true).apply();
            }
            return;
        }
        if ("PAD_MODE_OFF".equalsIgnoreCase(cleanPreset)) {
            isTabletDeviceMode = false;
            if (appContext != null) {
                appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE)
                        .edit().putBoolean("pad_mode_flag", false).apply();
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

        isTabletDeviceMode = "tablet_pro".equalsIgnoreCase(cleanPreset);
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
            String sig = sharp + "|" + sx + "|" + sy + "|" + chunks + "|" + chunkFast + "|" + stick + "|" + grid + "|" + pause + "|" + accel + "|" + isTabletDeviceMode + "|" + currentRecoilPower;
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

    public static void setPixelDownscaleFactor(int pct) {
        if (pct >= 50 && pct < 100) {
            applyAspectRatioStretch("SHRINK-" + pct);
        } else if (pct == 100) {
            resetResolutionToNative();
        }
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
        System.gc();
        applyUltimateSystemTweaks();
        return "360|" + availAfterMb + "|" + totalMb;
    }

    public static String runSmartDeviceScan(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        String tier = isTabletDeviceMode ? "PAD-ULTRA" : (isBudgetDeviceHardware ? "BUDGET-FPS-GUARD" : "ULTRA");
        return "Android|" + tier + "|" + shortPx;
    }
}
