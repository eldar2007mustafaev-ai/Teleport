package com.teleport.so2;

import android.content.Context;
import android.media.MediaDrm;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
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
import java.net.InetSocketAddress;
import java.net.Socket;
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

    private static final String KEYSTORE_ALIAS = "so2_hw_tee_v10";
    private static volatile String cachedHwid = null;
    private static volatile boolean cachedValidSession = false;
    private static volatile String cachedVerifiedKey = "";
    private static volatile boolean guardStarted = false;
    private static final ScheduledExecutorService bgGuard = Executors.newSingleThreadScheduledExecutor();

    public static void startBackgroundGuard(final Context ctx) {
        if (guardStarted) return;
        guardStarted = true;
        bgGuard.scheduleWithFixedDelay(() -> {
            enforceAntiDebugAndFrida();
            if (ctx != null && cachedHwid == null) {
                getHardwareBoundHwid(ctx);
            }
        }, 0, 8, TimeUnit.SECONDS);
    }

    public static void enforceAntiDebugAndFrida() {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                killNow();
            }
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("TracerPid:")) {
                        int tracerPid = Integer.parseInt(line.substring(10).trim());
                        if (tracerPid != 0) killNow();
                        break;
                    }
                }
            }
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains("frida") || low.contains("xposed") || low.contains("gum-js-loop")) {
                        killNow();
                    }
                }
            }
            for (int port : new int[]{27042, 27043}) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", port), 80);
                    killNow();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void killNow() {
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    // Вычисляется 1 раз и кэшируется в RAM, чтобы не фризить WebView!
    public static String getHardwareBoundHwid(Context ctx) {
        if (cachedHwid != null && !cachedHwid.isEmpty()) {
            return cachedHwid;
        }
        StringBuilder raw = new StringBuilder();
        try {
            String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (aid != null) raw.append(aid);
        } catch (Throwable ignored) {}

        try {
            UUID widevineUuid = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm drm = new MediaDrm(widevineUuid);
            byte[] drmId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            drm.release();
            if (drmId != null) {
                raw.append(Base64.encodeToString(drmId, Base64.NO_WRAP));
            }
        } catch (Throwable ignored) {}

        raw.append("|").append(Build.BOARD).append("|").append(Build.FINGERPRINT);

        try {
            byte[] sig = hmacSha256(raw.toString().getBytes(StandardCharsets.UTF_8), getOrCreateHardwareSecret());
            StringBuilder hex = new StringBuilder("SO2-");
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02X", sig[i]));
            }
            cachedHwid = hex.toString();
            return cachedHwid;
        } catch (Throwable t) {
            return "SO2-SEC-ERR";
        }
    }

    private static SecretKey getOrCreateTeeAesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEYSTORE_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
    }

    public static void putSecurePref(Context ctx, String key, String value) {
        try {
            SecretKey secretKey = getOrCreateTeeAesKey();
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
            SecretKey secretKey = getOrCreateTeeAesKey();
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
        cachedValidSession = true;
        bgGuard.execute(() -> {
            try {
                startBackgroundGuard(ctx);
                String hwid = getHardwareBoundHwid(ctx);
                byte[] mac = hmacSha256((clean + "|" + hwid).getBytes(StandardCharsets.UTF_8), getOrCreateHardwareSecret());
                String token = Base64.encodeToString(mac, Base64.NO_WRAP);
                putSecurePref(ctx, "v10_lic_key", clean);
                putSecurePref(ctx, "v10_lic_mac", token);
            } catch (Throwable ignored) {}
        });
    }

    // Мгновенная проверка за 0 мс из RAM (никаких фризов при нажатии кнопок!)
    public static boolean isSessionCryptographicallyValid(Context ctx) {
        if (cachedValidSession && cachedVerifiedKey.length() >= 5) {
            return true;
        }
        if (ctx == null) return false;
        try {
            String savedKey = getSecurePref(ctx, "v10_lic_key");
            String savedMac = getSecurePref(ctx, "v10_lic_mac");
            if (savedKey.length() < 5 || savedMac.isEmpty()) return false;
            String hwid = getHardwareBoundHwid(ctx);
            byte[] expected = hmacSha256((savedKey + "|" + hwid).getBytes(StandardCharsets.UTF_8), getOrCreateHardwareSecret());
            String expectedB64 = Base64.encodeToString(expected, Base64.NO_WRAP);
            boolean ok = MessageDigest.isEqual(savedMac.getBytes(StandardCharsets.UTF_8), expectedB64.getBytes(StandardCharsets.UTF_8));
            if (ok) {
                cachedVerifiedKey = savedKey;
                cachedValidSession = true;
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getVerifiedKey(Context ctx) {
        if (cachedVerifiedKey != null && cachedVerifiedKey.length() >= 5) return cachedVerifiedKey;
        String k = getSecurePref(ctx, "v10_lic_key");
        if (k != null && k.length() >= 5) cachedVerifiedKey = k;
        return cachedVerifiedKey;
    }

    public static void clearSessionToken(Context ctx) {
        cachedValidSession = false;
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
        String s = Build.BOOTLOADER + "|" + Build.HARDWARE + "|SO2TP_V10_TEE_SALT";
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
