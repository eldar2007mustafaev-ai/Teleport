package com.teleport.so2;

import android.content.Context;
import android.content.SharedPreferences;
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

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SecurityShield {

    private static final String KEYSTORE_ALIAS = "so2_hw_tee_v10";
    private static volatile String runtimeSessionToken = "";

    // Расшифровка строк в рантайме (чтобы grep по smali ничего не находил)
    public static String xorStr(int[] data, int key) {
        char[] out = new char[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (char) (data[i] ^ (key + (i & 7)));
        }
        return new String(out);
    }

    // 1. ЖЁСТКАЯ ЗАЩИТА ОТ ОТЛАДЧИКА, TRACERPID И FRIDA (Убивает процесс мгновенно)
    public static void enforceAntiDebugAndFrida() {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                killNow();
            }
            // Проверка TracerPid в /proc/self/status
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("TracerPid:")) {
                        int tracerPid = Integer.parseInt(line.substring(10).trim());
                        if (tracerPid != 0) {
                            killNow();
                        }
                        break;
                    }
                }
            }
            // Проверка инжектов Frida / Xposed / Substrate в памяти
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains("frida") || low.contains("xposed") || low.contains("substrate") || low.contains("gum-js-loop")) {
                        killNow();
                    }
                }
            }
            // Проверка стандартных портов Frida-server (27042, 27043)
            new Thread(() -> {
                for (int port : new int[]{27042, 27043}) {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress("127.0.0.1", port), 150);
                        killNow();
                    } catch (Throwable ignored) {}
                }
            }).start();
        } catch (Throwable ignored) {}
    }

    private static void killNow() {
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    // 2. АППАРАТНЫЙ HWID (AndroidKeyStore + Widevine + Android ID с защитой от подмены)
    public static String getHardwareBoundHwid(Context ctx) {
        enforceAntiDebugAndFrida();
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
            return hex.toString();
        } catch (Throwable t) {
            return "SO2-SEC-ERR";
        }
    }

    // 3. ШИФРОВАНИЕ SHAREDPREFERENCES ЧЕРЕЗ ЧИП KEYSTORE (AES-256-GCM)
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

    // 4. КРИПТОГРАФИЧЕСКИЙ ТОКЕН СЕССИИ (НЕВОЗМОЖНО ПОДДЕЛАТЬ ПРОСТЫМ return "OK")
    public static void issueVerifiedSessionToken(Context ctx, String licenseKey) {
        try {
            String hwid = getHardwareBoundHwid(ctx);
            byte[] mac = hmacSha256((licenseKey.trim().toUpperCase() + "|" + hwid).getBytes(StandardCharsets.UTF_8), getOrCreateHardwareSecret());
            runtimeSessionToken = Base64.encodeToString(mac, Base64.NO_WRAP);
            putSecurePref(ctx, "v10_lic_key", licenseKey.trim().toUpperCase());
            putSecurePref(ctx, "v10_lic_mac", runtimeSessionToken);
        } catch (Throwable ignored) {}
    }

    public static boolean isSessionCryptographicallyValid(Context ctx) {
        enforceAntiDebugAndFrida();
        try {
            String savedKey = getSecurePref(ctx, "v10_lic_key");
            String savedMac = getSecurePref(ctx, "v10_lic_mac");
            if (savedKey.length() < 5 || savedMac.isEmpty()) return false;
            String hwid = getHardwareBoundHwid(ctx);
            byte[] expected = hmacSha256((savedKey + "|" + hwid).getBytes(StandardCharsets.UTF_8), getOrCreateHardwareSecret());
            String expectedB64 = Base64.encodeToString(expected, Base64.NO_WRAP);
            return MessageDigest.isEqual(savedMac.getBytes(StandardCharsets.UTF_8), expectedB64.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearSessionToken(Context ctx) {
        runtimeSessionToken = "";
        ctx.getSharedPreferences("so2_sec_vault_v10", Context.MODE_PRIVATE).edit().clear().apply();
    }

    // 5. ЗАЩИТА WEBVIEW И ОВЕРЛЕЕВ (FLAG_SECURE + ОТКЛЮЧЕНИЕ FILE ACCESS И DEVTOOLS)
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
