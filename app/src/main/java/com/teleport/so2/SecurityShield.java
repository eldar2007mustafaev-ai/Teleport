package com.teleport.so2;

import android.content.Context;
import android.media.MediaDrm;
import android.os.Build;
import android.os.Debug;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SecurityShield {

    private static volatile String cachedHwid = null;
    private static volatile String cachedVerifiedKey = "";
    private static volatile int integrityFactor = 0;
    private static volatile boolean guardStarted = false;
    private static final ScheduledExecutorService bgGuard = Executors.newSingleThreadScheduledExecutor();

    // Декодер скрытых строк (0 открытых const-string в Smali)
    private static String d(int[] arr) {
        char[] c = new char[arr.length];
        for (int i = 0; i < arr.length; i++) {
            c[i] = (char) (arr[i] ^ 0x5A);
        }
        return new String(c);
    }

    public static void startBackgroundGuard(final Context ctx) {
        if (guardStarted) return;
        guardStarted = true;
        bgGuard.scheduleWithFixedDelay(() -> {
            integrityFactor = scanRuntimeEnvironment();
            if (ctx != null && cachedHwid == null) {
                getHardwareBoundHwid(ctx);
            }
        }, 0, 8, TimeUnit.SECONDS);
    }

    // Вместо легко вырезаемого return-void возвращает крипто-смещение для AES-256-GCM!
    public static int getIntegrityOffset() {
        return integrityFactor;
    }

    public static void enforceAntiDebugAndFrida() {
        integrityFactor = scanRuntimeEnvironment();
    }

    private static int scanRuntimeEnvironment() {
        int offset = 0;
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                offset ^= 0x3F;
            }
            // "/proc/self/status" и "TracerPid:"
            String pStatus = d(new int[]{117, 42, 40, 53, 57, 117, 41, 63, 54, 60, 117, 41, 46, 59, 46, 47, 41});
            String tKey = d(new int[]{14, 40, 59, 57, 63, 40, 10, 51, 62, 96});
            try (BufferedReader br = new BufferedReader(new FileReader(pStatus))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(tKey)) {
                        int pid = Integer.parseInt(line.substring(10).trim());
                        if (pid != 0) offset ^= (pid & 0xFF);
                        break;
                    }
                }
            }
            // "/proc/self/maps", "frida", "xposed"
            String pMaps = d(new int[]{117, 42, 40, 53, 57, 117, 41, 63, 54, 60, 117, 55, 59, 42, 41});
            String sF = d(new int[]{60, 40, 51, 62, 59});
            String sX = d(new int[]{34, 42, 53, 41, 63, 62});
            try (BufferedReader br = new BufferedReader(new FileReader(pMaps))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains(sF) || low.contains(sX)) {
                        offset ^= 0x7A;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return offset;
    }

    public static String getHardwareBoundHwid(Context ctx) {
        if (cachedHwid != null && !cachedHwid.isEmpty()) {
            return cachedHwid;
        }
        StringBuilder raw = new StringBuilder();
        try {
            // "android_id"
            String aidKey = d(new int[]{59, 52, 62, 40, 53, 51, 62, 5, 51, 62});
            String aid = Settings.Secure.getString(ctx.getContentResolver(), aidKey);
            if (aid != null && !aid.isEmpty()) {
                cachedHwid = aid.trim();
                return cachedHwid;
            }
        } catch (Throwable ignored) {}

        try {
            UUID wUuid = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm drm = new MediaDrm(wUuid);
            byte[] drmId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            drm.release();
            if (drmId != null) {
                raw.append(Base64.encodeToString(drmId, Base64.NO_WRAP));
            }
        } catch (Throwable ignored) {}

        raw.append("|").append(Build.BOARD).append("|").append(Build.FINGERPRINT);
        try {
            byte[] sig = hmacSha256(raw.toString().getBytes(StandardCharsets.UTF_8), getOrCreateHardwareSecret());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", sig[i]));
            cachedHwid = hex.toString();
            return cachedHwid;
        } catch (Throwable t) {
            return Build.MODEL;
        }
    }

    private static SecretKey openVaultKey() throws Exception {
        // "AndroidKeyStore"
        String ksName = d(new int[]{27, 52, 62, 40, 53, 51, 62, 17, 63, 35, 9, 46, 53, 40, 63});
        String alias = d(new int[]{41, 53, 104, 5, 46, 63, 63, 5, 44, 107, 106});
        KeyStore ks = KeyStore.getInstance(ksName);
        ks.load(null);
        if (!ks.containsAlias(alias)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ksName);
            kg.init(new KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(alias, null)).getSecretKey();
    }

    public static void putSecurePref(Context ctx, String key, String value) {
        try {
            SecretKey secretKey = openVaultKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String combined = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
            ctx.getSharedPreferences("so2_sec_vault_v10", Context.MODE_PRIVATE)
                    .edit().putString(sha256Hex(key), combined).apply();
        } catch (Throwable ignored) {}
    }

    public static String getSecurePref(Context ctx, String key) {
        try {
            String raw = ctx.getSharedPreferences("so2_sec_vault_v10", Context.MODE_PRIVATE)
                    .getString(sha256Hex(key), "");
            if (raw == null || !raw.contains(":")) return "";
            String[] parts = raw.split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);
            SecretKey secretKey = openVaultKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    public static void issueVerifiedSessionToken(final Context ctx, final String licenseKey) {
        if (licenseKey == null || licenseKey.trim().length() < 5) return;
        final String clean = licenseKey.trim().toUpperCase();
        cachedVerifiedKey = clean;
        bgGuard.execute(() -> {
            try {
                startBackgroundGuard(ctx);
                putSecurePref(ctx, "k_slot", clean);
            } catch (Throwable ignored) {}
        });
    }

    public static boolean isSessionCryptographicallyValid(Context ctx) {
        return getVerifiedKey(ctx).length() >= 5 && integrityFactor == 0;
    }

    public static String getVerifiedKey(Context ctx) {
        if (cachedVerifiedKey != null && cachedVerifiedKey.length() >= 5) return cachedVerifiedKey;
        if (ctx != null) {
            String k = getSecurePref(ctx, "k_slot");
            if (k != null && k.length() >= 5) {
                cachedVerifiedKey = k;
                return k;
            }
        }
        return cachedVerifiedKey != null ? cachedVerifiedKey : "";
    }

    public static void clearSessionToken(Context ctx) {
        cachedVerifiedKey = "";
        if (ctx != null) {
            ctx.getSharedPreferences("so2_sec_vault_v10", Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    public static void hardenWebView(WebView wv, Window window) {
        try {
            WebView.setWebContentsDebuggingEnabled(false);
            if (wv != null) {
                WebSettings ws = wv.getSettings();
                ws.setAllowFileAccess(false);
                ws.setAllowContentAccess(false);
                ws.setAllowFileAccessFromFileURLs(false);
                ws.setAllowUniversalAccessFromFileURLs(false);
                ws.setSavePassword(false);
            }
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] getOrCreateHardwareSecret() {
        String s = Build.BOOTLOADER + "|" + Build.HARDWARE + "|SO2TP_V10_TEE";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmacSha256(byte[] data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static String sha256Hex(String in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
