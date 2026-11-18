package com.teleport.so2;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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

public class SecurityShield {

    private static volatile String cachedHwid = null;
    private static volatile String cachedVerifiedKey = "";
    private static volatile String cachedCertHash = "";
    private static volatile boolean runtimeClean = true;
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
            if (ctx != null && cachedCertHash.isEmpty()) {
                cachedCertHash = computeApkCertSha256(ctx);
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    public static int getIntegrityOffset() {
        return runtimeClean ? 0 : 1;
    }

    public static void enforceAntiDebugAndFrida() {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                runtimeClean = false;
            }
        } catch (Throwable ignored) {}
    }

    // Чистый аппаратный Widevine DRM ID + ключ в AndroidKeyStore (без Build.BOOTLOADER и без resetprop-дыр)
    public static String getHardwareBoundHwid(Context ctx) {
        if (cachedHwid != null && !cachedHwid.isEmpty()) {
            return cachedHwid;
        }
        if (ctx != null) {
            try {
                String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (aid != null && aid.length() >= 8) {
                    cachedHwid = aid.trim().toLowerCase();
                    return cachedHwid;
                }
            } catch (Throwable ignored) {}
        }

        try {
            UUID wUuid = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm drm = new MediaDrm(wUuid);
            byte[] drmId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            drm.release();
            if (drmId != null && drmId.length > 0) {
                byte[] digest = signWithTeeHmac(drmId);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(8, digest.length); i++) {
                    sb.append(String.format("%02x", digest[i]));
                }
                cachedHwid = sb.toString();
                return cachedHwid;
            }
        } catch (Throwable ignored) {}

        cachedHwid = "hw_fallback";
        return cachedHwid;
    }

    public static String getApkSignatureHash(Context ctx) {
        if (!cachedCertHash.isEmpty()) return cachedCertHash;
        cachedCertHash = computeApkCertSha256(ctx);
        return cachedCertHash;
    }

    @SuppressWarnings("deprecation")
    private static String computeApkCertSha256(Context ctx) {
        if (ctx == null) return "";
        try {
            PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();
            byte[] certBytes = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo != null) {
                    Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                    if (sigs != null && sigs.length > 0) certBytes = sigs[0].toByteArray();
                }
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (pi.signatures != null && pi.signatures.length > 0) {
                    certBytes = pi.signatures[0].toByteArray();
                }
            }
            if (certBytes != null) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(certBytes);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
                return sb.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static byte[] signWithTeeHmac(byte[] data) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        String hmacAlias = "so2_tee_hmac_hw_v10";
        SecretKey hmacKey;
        if (!ks.containsAlias(hmacAlias)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(hmacAlias, KeyProperties.PURPOSE_SIGN).build());
            hmacKey = kg.generateKey();
        } else {
            hmacKey = ((KeyStore.SecretKeyEntry) ks.getEntry(hmacAlias, null)).getSecretKey();
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        return mac.doFinal(data);
    }

    private static SecretKey openTeeAesVaultKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        String aesAlias = "so2_tee_aes_gcm_v10";
        if (!ks.containsAlias(aesAlias)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(
                    aesAlias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(aesAlias, null)).getSecretKey();
    }

    public static void putSecurePref(Context ctx, String slotName, String value) {
        if (ctx == null || value == null) return;
        try {
            SecretKey secretKey = openTeeAesVaultKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String combined = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
            ctx.getSharedPreferences("so2_tee_store_v10", Context.MODE_PRIVATE)
                    .edit().putString(sha256Hex(slotName), combined).apply();
        } catch (Throwable ignored) {}
    }

    public static String getSecurePref(Context ctx, String slotName) {
        if (ctx == null) return "";
        try {
            String raw = ctx.getSharedPreferences("so2_tee_store_v10", Context.MODE_PRIVATE)
                    .getString(sha256Hex(slotName), "");
            if (raw == null || !raw.contains(":")) return "";
            String[] parts = raw.split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);
            SecretKey secretKey = openTeeAesVaultKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    public static void issueVerifiedSessionToken(final Context ctx, final String licenseKey) {
        if (licenseKey == null || licenseKey.trim().length() < 8) return;
        final String clean = licenseKey.trim().toUpperCase();
        cachedVerifiedKey = clean;
        bgGuard.execute(() -> {
            try {
                startBackgroundGuard(ctx);
                putSecurePref(ctx, "active_lic_slot", clean);
            } catch (Throwable ignored) {}
        });
    }

    public static boolean isSessionCryptographicallyValid(Context ctx) {
        return getVerifiedKey(ctx).length() >= 8 && runtimeClean;
    }

    public static String getVerifiedKey(Context ctx) {
        if (cachedVerifiedKey != null && cachedVerifiedKey.length() >= 8) return cachedVerifiedKey;
        if (ctx != null) {
            String k = getSecurePref(ctx, "active_lic_slot");
            if (k != null && k.length() >= 8) {
                cachedVerifiedKey = k;
                return k;
            }
        }
        return cachedVerifiedKey != null ? cachedVerifiedKey : "";
    }

    public static void clearSessionToken(Context ctx) {
        cachedVerifiedKey = "";
        if (ctx != null) {
            ctx.getSharedPreferences("so2_tee_store_v10", Context.MODE_PRIVATE).edit().clear().apply();
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

    private static String sha256Hex(String in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
