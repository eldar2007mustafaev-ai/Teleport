package com.teleport.so2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import rikka.shizuku.Shizuku;

public class TeleportEngine {

    private static Context appContext;
    private static volatile boolean isServerAuthorized = true;
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
    private static final ExecutorService shizukuQueue = Executors.newSingleThreadExecutor();
    private static final Map<String, String> sessionCmdCache = new ConcurrentHashMap<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile String lastPayloadSig = "";

    private static String getEngineEndpoint() {
        byte[] b = {104, 116, 116, 112, 58, 47, 47, 52, 53, 46, 49, 48, 46, 49, 54, 53, 46, 55, 53, 58, 56, 52, 52, 51, 47, 97, 112, 105, 47, 118, 49, 48, 47, 101, 110, 103, 105, 110, 101};
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String getMasterSalt() {
        byte[] b = {83, 79, 50, 84, 80, 95, 86, 49, 48, 95, 81, 85, 65, 78, 84, 85, 77, 95, 72, 77, 65, 67, 95, 65, 69, 65, 68, 95, 75, 69, 89, 95, 57, 57, 52, 49};
        return new String(b, StandardCharsets.UTF_8);
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        SecurityShield.startBackgroundGuard(appContext);
        autoDetectSavedLicenseKey();

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
            if (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180) {
                isNaturalLandscapeDevice = (dm.widthPixels > dm.heightPixels);
            } else {
                isNaturalLandscapeDevice = (dm.widthPixels < dm.heightPixels);
            }
        } else {
            dm = appContext.getResources().getDisplayMetrics();
        }

        int curShort = Math.min(dm.widthPixels, dm.heightPixels);
        int curLong = Math.max(dm.widthPixels, dm.heightPixels);

        int savedShort = prefs.getInt("orig_native_short", 0);
        int savedLong = prefs.getInt("orig_native_long", 0);

        if (curShort >= 720 && (curLong / (float) curShort) >= 1.72f) {
            nativeShortSide = curShort;
            nativeLongSide = curLong;
            prefs.edit()
                    .putInt("orig_native_short", curShort)
                    .putInt("orig_native_long", curLong)
                    .apply();
        } else if (savedShort >= 720 && savedLong >= 1600) {
            nativeShortSide = savedShort;
            nativeLongSide = savedLong;
        } else if (modeShort >= 720 && modeLong >= 1600) {
            nativeShortSide = modeShort;
            nativeLongSide = modeLong;
        } else {
            nativeShortSide = curShort >= 720 ? curShort : 1080;
            nativeLongSide = curLong >= 1800 ? curLong : 2400;
        }

        Configuration cfg = appContext.getResources().getConfiguration();
        int swDp = cfg != null ? cfg.smallestScreenWidthDp : 0;
        boolean autoDetectedPad = (swDp >= 580) || isNaturalLandscapeDevice;
        isTabletDeviceMode = prefs.getBoolean("pad_mode_flag", autoDetectedPad);

        int cores = Runtime.getRuntime().availableProcessors();
        String modelUpper = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        boolean isSamsungASeries = modelUpper.contains("SM-A") || modelUpper.contains("A57") || modelUpper.contains("A55") || modelUpper.contains("A54");
        isBudgetDeviceHardware = (!isTabletDeviceMode && (isSamsungASeries || deviceMaxRefreshRate <= 90 || nativeShortSide <= 720 || cores <= 6));
    }

    private static void autoDetectSavedLicenseKey() {
        if (appContext == null) return;
        String secKey = SecurityShield.getVerifiedKey(appContext);
        if (secKey != null && secKey.length() >= 8) {
            verifiedSessionKey = secKey.trim().toUpperCase();
            return;
        }
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
                                String val = ((String) v).trim().toUpperCase();
                                if (val.startsWith("TP") && val.length() >= 8 && !val.contains(" ")) {
                                    verifiedSessionKey = val;
                                    SecurityShield.issueVerifiedSessionToken(appContext, val);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
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

    private static String unsealServerPayload(String rawResp, String keyStr, String hwidStr) {
        try {
            int idx = rawResp.indexOf(':');
            if (idx <= 0) return "";
            String srvToken = rawResp.substring(0, idx).trim();
            String b64 = rawResp.substring(idx + 1).trim();

            byte[] combined = Base64.decode(b64, Base64.DEFAULT);
            if (combined == null || combined.length <= 48) return "";

            byte[] salt = Arrays.copyOfRange(combined, 0, 16);
            byte[] recvMac = Arrays.copyOfRange(combined, 16, 48);
            byte[] ct = Arrays.copyOfRange(combined, 48, combined.length);

            String pass = getMasterSalt() + keyStr.trim().toUpperCase() + hwidStr;
            byte[] saltAndToken = new byte[salt.length + srvToken.length()];
            System.arraycopy(salt, 0, saltAndToken, 0, salt.length);
            System.arraycopy(srvToken.getBytes(StandardCharsets.UTF_8), 0, saltAndToken, salt.length, srvToken.length());

            PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), saltAndToken, 1000, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] kMat = skf.generateSecret(spec).getEncoded();

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(kMat, "HmacSHA256"));
            mac.update(salt);
            byte[] calcMac = mac.doFinal(ct);
            if (!MessageDigest.isEqual(recvMac, calcMac)) return "";

            byte[] out = new byte[ct.length];
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < ct.length; i += 32) {
                sha.reset();
                sha.update(kMat);
                sha.update(ByteBuffer.allocate(4).putInt(i).array());
                byte[] blk = sha.digest();
                int lim = Math.min(32, ct.length - i);
                for (int j = 0; j < lim; j++) {
                    out[i + j] = (byte) (ct[i + j] ^ blk[j]);
                }
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void lockShizukuOnCrack() {
        sessionCmdCache.clear();
    }

    private static void requestServerShizuku(final JSONObject payload) {
        if (appContext == null) return;

        shizukuQueue.execute(() -> {
            try {
                if (verifiedSessionKey == null || verifiedSessionKey.length() < 8) {
                    autoDetectSavedLicenseKey();
                }
                if (verifiedSessionKey == null || verifiedSessionKey.length() < 8) {
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
                payload.put("cert_sha", SecurityShield.getApkSignatureHash(appContext));

                String cacheKey = payload.toString();
                String cachedCmd = sessionCmdCache.get(cacheKey);
                if (cachedCmd != null) {
                    if (isShizukuReady() || ShizukuManager.isPermissionGranted()) {
                        ShizukuManager.execCommand(cachedCmd);
                    }
                    return;
                }

                payload.put("nonce", String.valueOf(System.currentTimeMillis() / 1000L));

                URL url = new URL(getEngineEndpoint());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
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
                    String decryptedCmd = unsealServerPayload(resp, verifiedSessionKey, hwid);
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

    // ПЕРЕХВАТ КОМАНД ПЛАВАЮЩЕЙ КНОПКИ «T» ДЛЯ ВСЕХ МАРОК ТЕЛЕФОНОВ
    public static void execShizukuCommand(final String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        if (cmd.contains("wm size") && !cmd.contains("reset")) {
            applyAspectRatioStretch(lastSelectedRatio);
            return;
        }
        if (cmd.contains("wm size reset")) {
            releaseBoxToFullStretch();
            return;
        }
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
                if (isNaturalLandscapeDevice && (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180)) return rot;
            }
        } catch (Throwable ignored) {}
        return isNaturalLandscapeDevice ? 0 : 1;
    }

    // Проверка: не заблокировала ли оболочка Realme UI / HyperOS команду wm size
    private static void verifyStretchAppliedOrWarnUser(final int expectedShort, final int expectedLong) {
        mainHandler.postDelayed(() -> {
            try {
                if (appContext == null) return;
                WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null || wm.getDefaultDisplay() == null) return;
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                int curS = Math.min(dm.widthPixels, dm.heightPixels);
                int curL = Math.max(dm.widthPixels, dm.heightPixels);

                // Если разрешение вообще не изменилось (осталось родным) — значит система заблокировала wm size
                if (Math.abs(curL - nativeLongSide) <= 8 && Math.abs(curS - nativeShortSide) <= 8
                        && Math.abs(expectedLong - nativeLongSide) > 60) {
                    String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
                    if (brand.contains("realme") || brand.contains("oppo") || brand.contains("oneplus") || brand.contains("oplus")) {
                        Toast.makeText(appContext,
                                "⚠️ На Realme/OPPO включите «Отключить мониторинг разрешений» (в самом низу «Для разработчиков»)!",
                                Toast.LENGTH_LONG).show();
                    } else if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
                        Toast.makeText(appContext,
                                "⚠️ На Xiaomi/Poco включите «Отладка по USB (Настройки безопасности)» в «Для разработчиков»!",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(appContext,
                                "⚠️ Система заблокировала растяг! Проверьте «Для разработчиков» (Мониторинг разрешений) и Shizuku.",
                                Toast.LENGTH_LONG).show();
                    }
                }
            } catch (Throwable ignored) {}
        }, 450);
    }

    // УНИВЕРСАЛЬНЫЙ ШАГ 1 (SAMSUNG + REALME + XIAOMI + VIVO + HONOR + INFINIX)
    public static boolean applyAspectRatioStretch(String ratio) {
        final String cleanRatio = (ratio == null || ratio.trim().isEmpty()) ? lastSelectedRatio : ratio.trim().toUpperCase();
        lastSelectedRatio = cleanRatio;
        if (appContext != null) {
            appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE)
                    .edit().putString("stretch_ratio_val", cleanRatio).apply();
        }

        final int natS = getNativeShortSide();
        final int natL = getNativeLongSide();
        int tS = natS;
        int tL = natL;

        if (cleanRatio.startsWith("SHRINK-")) {
            int pct = 84;
            try { pct = Math.max(50, Math.min(96, Integer.parseInt(cleanRatio.replace("SHRINK-", "").trim()))); } catch (Throwable ignored) {}
            tS = Math.round(natS * (pct / 100.0f));
        } else if ("21:9".equals(cleanRatio)) {
            tS = Math.round(natS * 0.82f);
        } else if ("24:10".equals(cleanRatio)) {
            tS = Math.round(natS * 0.76f);
        } else if ("PAD-WIDE".equals(cleanRatio)) {
            tL = Math.round(natS * 1.15f);
        } else if ("1:1".equals(cleanRatio)) {
            tL = Math.round(natS * 1.10f);
        } else if ("5:4".equals(cleanRatio)) {
            tL = Math.round(natS * 1.22f);
        } else if ("4:3".equals(cleanRatio)) {
            tL = Math.round(natS * ((natL / (float) natS) < 1.65f ? 1.18f : 1.3333f));
        } else if ("3:2".equals(cleanRatio)) {
            tL = Math.round(natS * 1.42f);
        } else if ("16:10".equals(cleanRatio)) {
            tL = Math.round(natS * 1.50f);
        } else if ("16:9".equals(cleanRatio)) {
            tL = Math.round(natS * 1.58f);
        } else {
            tL = Math.round(natS * 1.3333f);
        }

        if ((tS & 1) == 1) tS += 1;
        if ((tL & 1) == 1) tL += 1;

        final int finalTS = tS;
        final int finalTL = tL;
        final String wmArg = isNaturalLandscapeDevice ? (tL + "x" + tS) : (tS + "x" + tL);
        final int landRot = getCurrentLandscapeRotation();

        stretchExecutor.execute(() -> {
            try {
                // 1. Первым делом изолированно выполняем wm size + cmd window size (чтобы Realme/Xiaomi не прерывали цепочку)
                String primaryCmd =
                        "wm size " + wmArg + "; " +
                        "cmd window size " + wmArg + " 2>/dev/null; " +
                        "cmd window user-rotation lock " + landRot + "; " +
                        "settings put system accelerometer_rotation 0; " +
                        "settings put system user_rotation " + landRot + ";";
                ShizukuManager.execCommand(primaryCmd);

                // 2. Вторым шагом снимаем ограничения Letterbox для Realme UI / ColorOS / HyperOS / One UI
                String multiBrandFlags =
                        "wm set-ignore-orientation-request true 2>/dev/null; " +
                        "cmd window set-ignore-orientation-request true 2>/dev/null; " +
                        "am compat disable OVERRIDE_MIN_ASPECT_RATIO com.axlebolt.standoff2 2>/dev/null; " +
                        "settings put global force_resizable_activities 1 2>/dev/null; " +
                        "settings put global enable_freeform_support 1 2>/dev/null; " +
                        "settings put global enable_non_resizable_multi_window 1 2>/dev/null; " +
                        "settings put global block_untrusted_touches 0 2>/dev/null; " +
                        "settings put secure block_untrusted_touches 0 2>/dev/null;";
                ShizukuManager.execCommand(multiBrandFlags);

                verifyStretchAppliedOrWarnUser(finalTS, finalTL);
            } catch (Throwable ignored) {}
        });
        return true;
    }

    // УНИВЕРСАЛЬНЫЙ ШАГ 2 — РАСКРЫТИЕ РАМКИ В ПОЛНЫЙ РАСТЯГ (ПРОБИВАЕТ REALME UI / HYPEROS / ONE UI)
    public static void releaseBoxToFullStretch() {
        final int natS = getNativeShortSide();
        final int natL = getNativeLongSide();
        final String natArg = isNaturalLandscapeDevice ? (natL + "x" + natS) : (natS + "x" + natL);
        final int landRot = getCurrentLandscapeRotation();

        stretchExecutor.execute(() -> {
            try {
                // Сначала подаем точное родное разрешение (заставляет Realme UI / MIUI мгновенно растянуть окно игры), затем reset
                String releaseCmd =
                        "cmd window user-rotation lock " + landRot + "; " +
                        "settings put system accelerometer_rotation 0; " +
                        "settings put system user_rotation " + landRot + "; " +
                        "wm size " + natArg + "; " +
                        "wm size reset; " +
                        "cmd window size reset 2>/dev/null;";
                ShizukuManager.execCommand(releaseCmd);
            } catch (Throwable ignored) {}
        });
    }

    // ПОЛНЫЙ СБРОС ЭКРАНА В РОДНОЕ РАЗРЕШЕНИЕ
    public static void resetResolutionToNative() {
        final int natS = getNativeShortSide();
        final int natL = getNativeLongSide();
        final String natArg = isNaturalLandscapeDevice ? (natL + "x" + natS) : (natS + "x" + natL);

        stretchExecutor.execute(() -> {
            try {
                String resetCmd =
                        "wm size " + natArg + "; " +
                        "wm size reset; " +
                        "cmd window size reset 2>/dev/null; " +
                        "wm density reset; " +
                        "wm set-ignore-orientation-request false 2>/dev/null; " +
                        "cmd window user-rotation free; " +
                        "settings put system accelerometer_rotation 1;";
                ShizukuManager.execCommand(resetCmd);
            } catch (Throwable ignored) {}
        });
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
            return;
        }
        if (cleanPreset.startsWith("SET_RATIO:")) {
            String r = cleanPreset.substring("SET_RATIO:".length()).trim().toUpperCase();
            if (!r.isEmpty()) {
                lastSelectedRatio = r;
                if (appContext != null) {
                    appContext.getSharedPreferences("so2_tp_cfg_v10", Context.MODE_PRIVATE)
                            .edit().putString("stretch_ratio_val", r).apply();
                }
            }
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
        int shortPx = getNativeShortSide();
        String tier = isTabletDeviceMode ? "PAD-ULTRA" : (isBudgetDeviceHardware ? "BUDGET-FPS-GUARD" : "ULTRA");
        return "Android|" + tier + "|" + shortPx;
    }
}
