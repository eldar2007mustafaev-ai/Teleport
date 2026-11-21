package com.teleport.so2;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaDrm;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class AndroidBridge {

    private static final String PREF_FIRST_LOGIN_DONE = "v420_manual_login_done";
    private static final String KEYSTORE_ALIAS_STRONGBOX = "SO2Teleport_StrongBox_AES_v11";
    private static final String KEYSTORE_ALIAS_TEE = "SO2Teleport_TEE_AES_v11";
    private static final String VAULT_PREF_NAME = "SO2Teleport_SecureVault_V11";
    private static final String VAULT_ENC_KEY = "enc_saved_license_v11";
    private static final String VAULT_FIXED_AAD = "SO2Teleport_Vault_AAD_V11";

    private static volatile String sCachedDrmHwid = null;
    private static boolean isNativeLoaded = false;
    static {
        try {
            System.loadLibrary("so2");
            isNativeLoaded = true;
        } catch (Throwable ignored) {
            isNativeLoaded = false;
        }
    }

    private static native String nativeComputeHwid(String drmHex, String androidId, String boardInfo);
    private static native boolean nativeIsHtmlTampered(byte[] htmlBytes);
    private static native boolean nativeIsBlacklisted(String candidate);
    private static native void nativeSetSessionVerified(String cleanKey, boolean verified);
    private static native boolean nativeIsSessionVerified();
    private static native void nativeForceTerminate();

    private final Context context;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final SharedPreferences vaultPrefs;
    private final SharedPreferences legacyPrefs;
    private final SharedPreferences profilePrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();

    private WindowManager windowManager;
    private TextView floatingStretchBtn;
    private FrameLayout trashContainer;
    private BridgeTrashView trashPillView;
    private WindowManager.LayoutParams stretchParams;
    private WindowManager.LayoutParams trashParams;
    private boolean isStretchOverlayShown = false;
    private boolean isTrashAttached = false;
    private boolean isBoxBarsActive = false;
    private String selectedStretchRatio = "4:3";

    private CrosshairOverlayView crosshairView;
    private WindowManager.LayoutParams crosshairParams;
    private boolean isCrosshairShown = false;
    private boolean stealthOverlayFlag = false;

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.prefs = context.getSharedPreferences("SO2TeleportPrefs_V410", Context.MODE_PRIVATE);
        this.vaultPrefs = context.getSharedPreferences(VAULT_PREF_NAME, Context.MODE_PRIVATE);
        this.legacyPrefs = context.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        this.profilePrefs = context.getSharedPreferences("so2_teleport_profile_prefs", Context.MODE_PRIVATE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        migrateExistingKeysAcrossStores();

        String restoredKey = readEncryptedKeyFromVault();
        if (!restoredKey.isEmpty() && !isBlacklistedArtifact(restoredKey)) {
            prefs.edit().putBoolean(PREF_FIRST_LOGIN_DONE, true).apply();
            safeNativeSetSession(restoredKey, true);
            ApiManager.setSessionVerified(true);
            SecurityShield.issueVerifiedSessionToken(context, restoredKey);
        }

        TeleportEngine.init(context);
        TeleportEngine.requestShizukuPermissionIfNeeded();
        startLicenseWatchdog();
    }

    // МЕТОД dpToPx ВОЗВРАЩЕН В КЛАСС
    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private boolean isValidOfficialBotKeyFormat(String rawKey) {
        String u = ApiManager.normalizeKeyInput(rawKey);
        if (u.length() < 8 || u.length() > 42 || isBlacklistedArtifact(u)) return false;
        String baseKey = u.split("_")[0];
        String[] parts = baseKey.split("-");
        if (parts.length < 2 || parts.length > 3) return false;
        String prefix = parts[0];
        boolean validPrefix = prefix.startsWith("TP") || "TRIAL".equals(prefix) || "UNLIM".equals(prefix);
        if (!validPrefix) return false;
        if (parts.length == 3) {
            if (parts[1].length() < 2 || parts[1].length() > 10) return false;
            if (parts[2].length() < 4) return false;
        }
        return true;
    }

    private void migrateExistingKeysAcrossStores() {
        String currentVault = readEncryptedKeyFromVault();
        if (!currentVault.isEmpty() && !isBlacklistedArtifact(currentVault)) return;

        String[] candidates = new String[]{
                prefs.getString("saved_license_key", ""),
                profilePrefs.getString("activated_key", ""),
                legacyPrefs.getString("license_key", ""),
                SecurityShield.getVerifiedKey(context)
        };
        for (String cand : candidates) {
            String clean = ApiManager.normalizeKeyInput(cand);
            if (clean.length() >= 5 && !isBlacklistedArtifact(clean)) {
                saveEncryptedKeyToVault(clean);
                prefs.edit().remove("saved_license_key").putBoolean(PREF_FIRST_LOGIN_DONE, true).apply();
                safeNativeSetSession(clean, true);
                break;
            }
        }
    }

    private void safeNativeSetSession(String key, boolean ok) {
        if (isNativeLoaded) {
            try { nativeSetSessionVerified(key == null ? "" : key, ok); } catch (Throwable ignored) {}
        }
    }

    private boolean safeNativeIsSessionOk() {
        if (isNativeLoaded) {
            try { if (nativeIsSessionVerified()) return true; } catch (Throwable ignored) {}
        }
        return ApiManager.isSessionVerified() || !getSavedKey().isEmpty();
    }

    private void executeNativeKillOnTamper() {
        if (isNativeLoaded) {
            try { nativeForceTerminate(); } catch (Throwable ignored) {}
        }
        try { Process.killProcess(Process.myPid()); } catch (Throwable ignored) {}
        try { Runtime.getRuntime().halt(1); } catch (Throwable ignored) {}
    }

    private SecretKey getOrCreateKeystoreSecret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        if (ks.containsAlias(KEYSTORE_ALIAS_STRONGBOX)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS_STRONGBOX, null);
            if (entry != null) return entry.getSecretKey();
        }
        if (ks.containsAlias(KEYSTORE_ALIAS_TEE)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS_TEE, null);
            if (entry != null) return entry.getSecretKey();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                KeyGenerator kgStrong = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                KeyGenParameterSpec strongSpec = new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS_STRONGBOX,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setIsStrongBoxBacked(true).build();
                kgStrong.init(strongSpec);
                return kgStrong.generateKey();
            } catch (Throwable ignored) {}
        }

        KeyGenerator kgTee = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec teeSpec = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS_TEE,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build();
        kgTee.init(teeSpec);
        return kgTee.generateKey();
    }

    private void saveEncryptedKeyToVault(String cleanKey) {
        String norm = ApiManager.normalizeKeyInput(cleanKey);
        if (norm.isEmpty()) {
            vaultPrefs.edit().remove(VAULT_ENC_KEY).remove(VAULT_ENC_KEY + "_fb").apply();
            safeNativeSetSession("", false);
            return;
        }
        String fbB64 = Base64.encodeToString(norm.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        vaultPrefs.edit().putString(VAULT_ENC_KEY + "_fb", fbB64).apply();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreSecret());
            byte[] iv = cipher.getIV();
            cipher.updateAAD(VAULT_FIXED_AAD.getBytes(StandardCharsets.UTF_8));
            byte[] enc = cipher.doFinal(norm.getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
            vaultPrefs.edit().putString(VAULT_ENC_KEY, blob).apply();
        } catch (Throwable ignored) {}
    }

    private String readEncryptedKeyFromVault() {
        try {
            String blob = vaultPrefs.getString(VAULT_ENC_KEY, "");
            if (blob != null && blob.contains(":")) {
                String[] parts = blob.split(":", 2);
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreSecret(), new GCMParameterSpec(128, iv));
                cipher.updateAAD(VAULT_FIXED_AAD.getBytes(StandardCharsets.UTF_8));
                String dec = ApiManager.normalizeKeyInput(new String(cipher.doFinal(enc), StandardCharsets.UTF_8));
                if (!isBlacklistedArtifact(dec)) return dec;
            }
        } catch (Throwable ignored) {}

        try {
            String fb = vaultPrefs.getString(VAULT_ENC_KEY + "_fb", "");
            if (fb != null && !fb.isEmpty()) {
                String dec = ApiManager.normalizeKeyInput(new String(Base64.decode(fb, Base64.NO_WRAP), StandardCharsets.UTF_8));
                if (!isBlacklistedArtifact(dec)) return dec;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private boolean isBlacklistedArtifact(String val) {
        if (val == null || val.trim().isEmpty()) return true;
        if (isNativeLoaded) {
            try { if (nativeIsBlacklisted(val)) return true; } catch (Throwable ignored) {}
        }
        String u = val.trim().toUpperCase(Locale.US);
        return u.contains("BA465E1492CBB45A") || u.contains("HW9900AABB1122CC") || u.contains("SO2-HWID-V10") || u.contains("SO2TELEPORT-LOCAL-KEY") || u.contains("BOHDANIUK") || u.contains("CRACK") || u.contains("FREE_KEY") || u.contains("I6RUSOKAI") || u.contains("6RUSOK") || u.contains("TP99999") || u.contains("00000002029292992");
    }

    private boolean isFridaOrXposedActive() {
        try { Class.forName("de.robv.android.xposed.XposedBridge"); return true; } catch (Throwable ignored) {}
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String l = line.toLowerCase(Locale.US);
                if (l.contains("frida-agent") || l.contains("gum-js-loop") || l.contains("xposedbridge")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isClientTampered() {
        if (isFridaOrXposedActive()) return true;
        try {
            InputStream is = context.getAssets().open("index.html");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            is.close();
            byte[] htmlBytes = bos.toByteArray();

            if (isNativeLoaded) {
                try { if (nativeIsHtmlTampered(htmlBytes)) return true; } catch (Throwable ignored) {}
            }

            String html = new String(htmlBytes, StandardCharsets.UTF_8);
            String lower = html.toLowerCase(Locale.US);
            if (lower.contains("bohdaniuk") || lower.contains("локальная активация") || lower.contains("cracked") || lower.contains("so2teleport-local-key") || lower.contains("ba465e1492cbb45a") || !html.contains("SO2TELEPORT_QUANTUM_V9_SEAL") || !html.contains("pollLicenseExpiration")) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (Exception e) { return "hw7744aabb1122cc"; }
    }

    private String getRawDrmHex() {
        String savedDrm = prefs.getString("cached_raw_drm_hex_v11", "");
        if (savedDrm != null && savedDrm.length() >= 8) return savedDrm;
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

        String res = drmHex.toString();
        if (res.length() >= 8) prefs.edit().putString("cached_raw_drm_hex_v11", res).apply();
        return res;
    }

    private String getImmutableDrmId() {
        if (sCachedDrmHwid != null && sCachedDrmHwid.length() >= 16) return sCachedDrmHwid;
        String cachedPref = prefs.getString("cached_immutable_drm_v11", "");
        if (cachedPref != null && cachedPref.length() >= 16 && !isBlacklistedArtifact(cachedPref)) {
            sCachedDrmHwid = cachedPref;
            return cachedPref;
        }

        String drmHex = getRawDrmHex();
        String boardInfo = Build.BOARD + "|" + Build.BRAND + "|" + Build.DEVICE + "|" + Build.HARDWARE + "|" + Build.MODEL;
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
        } catch (Throwable ignored) {}

        if (isNativeLoaded) {
            try {
                String natHwid = nativeComputeHwid(drmHex, androidId, boardInfo);
                if (natHwid != null && natHwid.length() >= 16 && !isBlacklistedArtifact(natHwid)) {
                    sCachedDrmHwid = natHwid;
                    if (!drmHex.isEmpty()) prefs.edit().putString("cached_immutable_drm_v11", natHwid).apply();
                    return natHwid;
                }
            } catch (Throwable ignored) {}
        }

        String fallbackHwid = sha256Hex16(drmHex + "|" + boardInfo);
        sCachedDrmHwid = fallbackHwid;
        if (!drmHex.isEmpty()) prefs.edit().putString("cached_immutable_drm_v11", fallbackHwid).apply();
        return fallbackHwid;
    }

    private String getAllHwidCandidatesCsv(String cleanKey) {
        Set<String> candidates = new LinkedHashSet<>();
        if (cleanKey != null && !cleanKey.isEmpty()) {
            String keySpecific = prefs.getString("bound_hwid_" + cleanKey, "");
            if (keySpecific != null && !keySpecific.isEmpty() && !isBlacklistedArtifact(keySpecific)) candidates.add(keySpecific.trim().toLowerCase(Locale.US));
        }
        String globalBound = prefs.getString("bound_working_hwid", "");
        if (globalBound != null && !globalBound.isEmpty() && !isBlacklistedArtifact(globalBound)) candidates.add(globalBound.trim().toLowerCase(Locale.US));

        String drmHwid = getImmutableDrmId().toLowerCase(Locale.US);
        candidates.add(drmHwid);

        String androidId = "";
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId != null) androidId = androidId.trim().toLowerCase(Locale.US);
            else androidId = "";
        } catch (Throwable ignored) {}

        if (!androidId.isEmpty()) {
            candidates.add(androidId);
            candidates.add(drmHwid + "|" + androidId);
        }

        StringBuilder sb = new StringBuilder();
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && !isBlacklistedArtifact(c)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(c);
            }
        }
        return sb.length() > 0 ? sb.toString() : drmHwid;
    }

    private void recordWorkingHwid(String cleanKey, JSONObject res) {
        String matched = (res != null) ? res.optString("matched_hwid", "") : "";
        if (matched == null || matched.isEmpty()) matched = getImmutableDrmId();
        prefs.edit().putString("bound_working_hwid", matched).putString("bound_hwid_" + cleanKey, matched).apply();
        SecurityShield.setVerifiedHwid(context, matched);
    }

    @JavascriptInterface
    public String getDeviceHwid() { return getImmutableDrmId(); }

    @JavascriptInterface
    public String getDisplayHwid() { return getImmutableDrmId(); }

    @JavascriptInterface
    public String getHardwareId() { return getImmutableDrmId(); }

    private long getDefaultDurationMsForKey(String cleanKey) {
        if (cleanKey == null || isBlacklistedArtifact(cleanKey)) return 0L;
        String u = cleanKey.toUpperCase(Locale.US);
        if (u.startsWith("TPLIFE") || u.contains("LIFE") || u.startsWith("TP999D")) return 3650L * 24L * 3600L * 1000L;
        if (u.startsWith("TP-1HR") || u.contains("1HR") || u.contains("-1H-")) return 3600L * 1000L;
        if (u.startsWith("UNLIM")) return 3L * 3600L * 1000L;
        if (u.startsWith("TP-6HR") || u.contains("6HR") || u.startsWith("TRIAL") || u.startsWith("TP-")) return 6L * 3600L * 1000L;
        if (u.startsWith("TP1D") || u.contains("-1D-") || u.contains("24H")) return 24L * 3600L * 1000L;
        if (u.startsWith("TP7D") || u.contains("-7D-")) return 7L * 24L * 3600L * 1000L;
        return 30L * 24L * 3600L * 1000L;
    }

    private long getOrInitKeyDeadlineMs(String cleanKey, JSONObject serverRes) {
        if (cleanKey == null || cleanKey.isEmpty() || isBlacklistedArtifact(cleanKey)) return 0L;
        String deadlinePrefKey = "key_deadline_ms_" + cleanKey;
        long now = System.currentTimeMillis();

        if (serverRes != null) {
            long remSec = serverRes.optLong("remaining_sec", serverRes.optLong("seconds_left", -1L));
            if (remSec > 0) {
                long newDeadline = now + (remSec * 1000L);
                prefs.edit().putLong(deadlinePrefKey, newDeadline).apply();
                return newDeadline;
            }
            long expUnix = serverRes.optLong("expires_at", -1L);
            if (expUnix > 1700000000L) {
                long newDeadline = (expUnix < 100000000000L) ? (expUnix * 1000L) : expUnix;
                prefs.edit().putLong(deadlinePrefKey, newDeadline).apply();
                return newDeadline;
            }
        }

        long savedDeadline = prefs.getLong(deadlinePrefKey, 0L);
        if (savedDeadline > 0L) return savedDeadline;

        long computedDeadline = now + getDefaultDurationMsForKey(cleanKey);
        prefs.edit().putLong(deadlinePrefKey, computedDeadline).apply();
        return computedDeadline;
    }

    private String buildLiveSubscriptionLabel(JSONObject serverRes, String cleanKey) {
        if (cleanKey == null || cleanKey.isEmpty() || isBlacklistedArtifact(cleanKey)) return "EXPIRED";
        long deadlineMs = getOrInitKeyDeadlineMs(cleanKey, serverRes);
        long diffMs = deadlineMs - System.currentTimeMillis();
        if (diffMs <= 0L) return "EXPIRED";

        long totalMinutes = diffMs / (60L * 1000L);
        long days = totalMinutes / (60L * 24L);
        long hours = (totalMinutes % (60L * 24L)) / 60L;
        if (days >= 1800) return "АКТИВНА (НАВСЕГДА)";
        if (days > 0) return "АКТИВНА (" + days + " дн. " + hours + " ч.)";
        return "АКТИВНА (" + hours + " ч.)";
    }

    private void startLicenseWatchdog() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isClientTampered()) {
                    forceKickExpiredUser("Обнаружена модификация клиента!");
                    executeNativeKillOnTamper();
                    return;
                }
                final String savedKey = getSavedKey();
                if (!savedKey.isEmpty() && prefs.getBoolean(PREF_FIRST_LOGIN_DONE, false)) {
                    networkExecutor.execute(() -> {
                        try {
                            String hwidCsv = getAllHwidCandidatesCsv(savedKey);
                            String raw = ApiManager.verifyLicense(savedKey, hwidCsv);
                            JSONObject res = new JSONObject(raw);
                            if ("banned".equalsIgnoreCase(res.optString("status", ""))) {
                                forceKickExpiredUser(res.optString("message", "Ключ заблокирован!"));
                            } else {
                                recordWorkingHwid(savedKey, res);
                                getOrInitKeyDeadlineMs(savedKey, res);
                                prefs.edit().putLong("last_server_ok_ts", System.currentTimeMillis()).apply();
                                safeNativeSetSession(savedKey, true);
                                ApiManager.setSessionVerified(true);
                            }
                        } catch (Exception ignored) {}
                    });
                }
                mainHandler.postDelayed(this, 30000);
            }
        }, 30000);
    }

    private void removeOverlayViewSafe(View v) {
        if (v == null) return;
        try { v.clearAnimation(); v.setVisibility(View.GONE); } catch (Throwable ignored) {}
        if (windowManager == null) return;
        try { windowManager.removeViewImmediate(v); return; } catch (Throwable ignored) {}
        try { windowManager.removeView(v); } catch (Throwable ignored) {}
    }

    private void detachTrashOverlaySafe() {
        if (trashContainer != null) removeOverlayViewSafe(trashContainer);
        isTrashAttached = false;
    }

    private void forceKickExpiredUser(String reasonToast) {
        saveEncryptedKeyToVault("");
        safeNativeSetSession("", false);
        SecurityShield.clearSessionToken(context);
        prefs.edit().remove("saved_license_key").putBoolean(PREF_FIRST_LOGIN_DONE, false).apply();
        legacyPrefs.edit().remove("license_key").apply();
        profilePrefs.edit().remove("activated_key").apply();
        ApiManager.invalidateSession();
        TeleportEngine.stopTeleport();

        mainHandler.post(() -> {
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) {
                removeOverlayViewSafe(floatingStretchBtn);
                floatingStretchBtn = null;
            }
            isStretchOverlayShown = false;
            if (isCrosshairShown && crosshairView != null) {
                removeOverlayViewSafe(crosshairView);
                crosshairView = null;
                isCrosshairShown = false;
            }
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).toggleFloatingButton(false);
                ((MainActivity) activity).updateCrosshair("off");
            }
            if (reasonToast != null && !reasonToast.isEmpty()) Toast.makeText(context, reasonToast, Toast.LENGTH_LONG).show();
            if (activity instanceof MainActivity) {
                String safeMsg = (reasonToast == null ? "Введите лицензионный ключ" : reasonToast).replace("'", "").replace("\"", "");
                ((MainActivity) activity).evalJsFromNotification("try { if(typeof forceShowKickScreen==='function') forceShowKickScreen('" + safeMsg + "'); } catch(e){}");
            }
        });
    }

    @JavascriptInterface
    public boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        mainHandler.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                try {
                    Toast.makeText(context, "Выдайте разрешение «Поверх других окон» для кнопки «T»!", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(fallback);
                }
            } else {
                Toast.makeText(context, "Разрешение «Поверх других окон» активно!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public String activateStretchFloatingButton(String ratio) {
        if (getSavedKey().isEmpty() || isClientTampered()) return "NEED_AUTH";
        if (ratio != null && !ratio.trim().isEmpty()) this.selectedStretchRatio = ratio.trim().toUpperCase(Locale.US);
        prefs.edit().putString("saved_stretch_ratio", this.selectedStretchRatio).apply();
        legacyPrefs.edit().putString("stretch_ratio", this.selectedStretchRatio).putBoolean("float_btn_enabled", true).apply();
        TeleportEngine.startTeleport("SET_RATIO:" + this.selectedStretchRatio);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission();
            return "NEED_PERMISSION";
        }

        TeleportEngine.requestShizukuPermissionIfNeeded();
        TeleportEngine.execShizukuCommand("settings put global block_untrusted_touches 0; settings put secure block_untrusted_touches 0; device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0;");

        mainHandler.post(() -> {
            try {
                if (activity instanceof MainActivity) ((MainActivity) activity).toggleFloatingButton(false);
                detachTrashOverlaySafe();
                if (floatingStretchBtn != null) {
                    removeOverlayViewSafe(floatingStretchBtn);
                    floatingStretchBtn = null;
                }
                isStretchOverlayShown = false;

                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                trashContainer = new FrameLayout(context);
                trashPillView = new BridgeTrashView(context);
                FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(dpToPx(276), dpToPx(58), Gravity.CENTER);
                trashContainer.addView(trashPillView, pillLp);

                int trashFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) trashFlags |= WindowManager.LayoutParams.FLAG_SECURE;

                trashParams = new WindowManager.LayoutParams(dpToPx(300), dpToPx(84), overlayType, trashFlags, PixelFormat.TRANSLUCENT);
                trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                trashParams.y = dpToPx(22);
                trashParams.alpha = 0.95f;

                floatingStretchBtn = new TextView(context);
                floatingStretchBtn.setText("T\n" + selectedStretchRatio.replace("SHRINK-", ""));
                floatingStretchBtn.setTextColor(Color.WHITE);
                floatingStretchBtn.setTextSize(11.5f);
                floatingStretchBtn.setGravity(Gravity.CENTER);
                floatingStretchBtn.setIncludeFontPadding(false);

                int sizePx = dpToPx(54);
                int btnFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) btnFlags |= WindowManager.LayoutParams.FLAG_SECURE;

                stretchParams = new WindowManager.LayoutParams(sizePx, sizePx, overlayType, btnFlags, PixelFormat.TRANSLUCENT);
                stretchParams.gravity = Gravity.TOP | Gravity.START;
                stretchParams.x = dpToPx(20);
                stretchParams.y = dpToPx(140);
                isBoxBarsActive = false;
                updateFloatingBtnAppearance();

                floatingStretchBtn.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX, initialY;
                    private float initialTouchX, initialTouchY;
                    private boolean isDragging = false;
                    private boolean wasInTrashZone = false;
                    private final int touchSlop = dpToPx(8);

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                initialX = stretchParams.x; initialY = stretchParams.y;
                                initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                                isDragging = false; wasInTrashZone = false;
                                v.setScaleX(0.92f); v.setScaleY(0.92f);
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                int dx = Math.round(event.getRawX() - initialTouchX);
                                int dy = Math.round(event.getRawY() - initialTouchY);
                                if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                                    isDragging = true;
                                    if (!isTrashAttached && trashContainer != null && trashParams != null && windowManager != null) {
                                        if (trashPillView != null) trashPillView.setHovered(false);
                                        trashContainer.setVisibility(View.VISIBLE);
                                        trashContainer.setAlpha(0f);
                                        windowManager.addView(trashContainer, trashParams);
                                        trashContainer.animate().alpha(0.95f).setDuration(110).start();
                                        isTrashAttached = true;
                                    }
                                }
                                if (isDragging && windowManager != null && floatingStretchBtn != null) {
                                    stretchParams.x = initialX + dx; stretchParams.y = initialY + dy;
                                    try { windowManager.updateViewLayout(floatingStretchBtn, stretchParams); } catch (Exception ignored) {}
                                    boolean inTrash = isTouchOverTrashZone(event.getRawX(), event.getRawY());
                                    if (trashPillView != null) trashPillView.setHovered(inTrash);
                                    floatingStretchBtn.setAlpha(inTrash ? 0.62f : 1.0f);
                                    if (inTrash && !wasInTrashZone) try { floatingStretchBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) {}
                                    wasInTrashZone = inTrash;
                                }
                                return true;
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                v.setScaleX(1.0f); v.setScaleY(1.0f); v.setAlpha(1.0f);
                                if (isDragging) {
                                    boolean droppedInTrash = wasInTrashZone || isTouchOverTrashZone(event.getRawX(), event.getRawY());
                                    if (trashPillView != null) trashPillView.setHovered(false);
                                    detachTrashOverlaySafe();
                                    isDragging = false; wasInTrashZone = false;
                                    if (droppedInTrash) removeFloatingTpButtonOnly();
                                    return true;
                                }
                                detachTrashOverlaySafe();
                                toggleTwoStepStretchAction();
                                return true;
                        }
                        return false;
                    }
                });

                windowManager.addView(floatingStretchBtn, stretchParams);
                isStretchOverlayShown = true;
                Toast.makeText(context, "Кнопка «T» (" + selectedStretchRatio + ") запущена!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "Ошибка кнопки T: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        return "OK";
    }

    private boolean isTouchOverTrashZone(float rawX, float rawY) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            if (windowManager != null && windowManager.getDefaultDisplay() != null) windowManager.getDefaultDisplay().getRealMetrics(dm);
            else dm = context.getResources().getDisplayMetrics();

            int sw = dm.widthPixels;
            int sh = dm.heightPixels;
            int btnSize = dpToPx(54);

            if (trashPillView != null && isTrashAttached && trashPillView.getWidth() > 0) {
                int[] tLoc = new int[2];
                trashPillView.getLocationOnScreen(tLoc);
                int padX = dpToPx(55); int padY = dpToPx(55);
                if (rawX >= tLoc[0] - padX && rawX <= tLoc[0] + trashPillView.getWidth() + padX && rawY >= tLoc[1] - padY && rawY <= tLoc[1] + trashPillView.getHeight() + padY) return true;
                if (floatingStretchBtn != null) {
                    int[] bLoc = new int[2];
                    floatingStretchBtn.getLocationOnScreen(bLoc);
                    float bCx = bLoc[0] + floatingStretchBtn.getWidth() / 2f;
                    float bCy = bLoc[1] + floatingStretchBtn.getHeight() / 2f;
                    if (bCx >= tLoc[0] - padX && bCx <= tLoc[0] + trashPillView.getWidth() + padX && bCy >= tLoc[1] - padY && bCy <= tLoc[1] + trashPillView.getHeight() + padY) return true;
                }
            }
            boolean yNearBottom = rawY >= (sh - dpToPx(140)) || (stretchParams != null && (stretchParams.y + btnSize) >= (sh - dpToPx(140))) || (stretchParams != null && stretchParams.y >= (sh * 0.68f));
            boolean xNearCenter = Math.abs(rawX - (sw / 2f)) <= (sw * 0.38f) || (stretchParams != null && Math.abs((stretchParams.x + btnSize / 2f) - (sw / 2f)) <= (sw * 0.38f));
            return yNearBottom && xNearCenter;
        } catch (Throwable t) { return false; }
    }

    private void toggleTwoStepStretchAction() {
        if (getSavedKey().isEmpty()) { forceKickExpiredUser("Ключ не активен!"); return; }
        if (!TeleportEngine.isShizukuReady() && !ShizukuManager.isPermissionGranted()) {
            Toast.makeText(context, "Включите Shizuku! Помощь: @talent_dare", Toast.LENGTH_LONG).show();
            TeleportEngine.requestShizukuPermissionIfNeeded();
            return;
        }

        if (!isBoxBarsActive) {
            if (TeleportEngine.applyAspectRatioStretch(selectedStretchRatio)) {
                isBoxBarsActive = true;
                Toast.makeText(context, "Шаг 1 (" + selectedStretchRatio + ") включен! Зайди в игру и нажми «T» снова", Toast.LENGTH_SHORT).show();
            }
        } else {
            TeleportEngine.releaseBoxToFullStretch();
            isBoxBarsActive = false;
            Toast.makeText(context, "Шаг 2: Растяг применён! Перетяни кнопку «T» вниз в ведро", Toast.LENGTH_SHORT).show();
        }
        updateFloatingBtnAppearance();
    }

    private void removeFloatingTpButtonOnly() {
        mainHandler.post(() -> {
            if (isBoxBarsActive) { TeleportEngine.releaseBoxToFullStretch(); isBoxBarsActive = false; }
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) { removeOverlayViewSafe(floatingStretchBtn); floatingStretchBtn = null; }
            trashContainer = null; trashPillView = null; isStretchOverlayShown = false;
            legacyPrefs.edit().putBoolean("float_btn_enabled", false).apply();
            Toast.makeText(context, "Кнопка «T» убрана с экрана, приятной катки!", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateFloatingBtnAppearance() {
        if (floatingStretchBtn == null) return;
        String shortLabel = selectedStretchRatio.replace("SHRINK-", "");
        GradientDrawable glassLens = new GradientDrawable();
        glassLens.setShape(GradientDrawable.OVAL);
        glassLens.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        glassLens.setOrientation(GradientDrawable.Orientation.TL_BR);

        if (isBoxBarsActive) {
            glassLens.setColors(new int[]{Color.parseColor("#CC30D158"), Color.parseColor("#99248A3D"), Color.parseColor("#CC1C1C1E")});
            glassLens.setStroke(dpToPx(2), Color.parseColor("#EEFFFFFF"));
            floatingStretchBtn.setText("2\n" + shortLabel);
        } else {
            glassLens.setColors(new int[]{Color.parseColor("#CC2C2C2E"), Color.parseColor("#CC1C1C1E"), Color.parseColor("#EE000000")});
            glassLens.setStroke(dpToPx(2), Color.parseColor("#EE0A84FF"));
            floatingStretchBtn.setText("T\n" + shortLabel);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) floatingStretchBtn.setElevation(dpToPx(10));
        floatingStretchBtn.setShadowLayer(8f, 0f, 2f, Color.parseColor("#CC000000"));
        floatingStretchBtn.setBackground(glassLens);
    }

    @JavascriptInterface
    public void applyStretchDirectlyNow(String ratio) { activateStretchFloatingButton(ratio); }

    @JavascriptInterface
    public void applyDirectStretchRatio(String ratio) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.applyAspectRatioStretch(ratio);
    }

    @JavascriptInterface
    public void resetResolution() {
        legacyPrefs.edit().putBoolean("float_btn_enabled", false).apply();
        mainHandler.post(() -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).toggleFloatingButton(false);
            TeleportEngine.resetResolutionToNative();
            isBoxBarsActive = false;
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) { removeOverlayViewSafe(floatingStretchBtn); floatingStretchBtn = null; }
            trashContainer = null; trashPillView = null; isStretchOverlayShown = false;
            Toast.makeText(context, "Кнопка «T» убрана, родной экран восстановлен", Toast.LENGTH_SHORT).show();
        });
    }

    @JavascriptInterface
    public boolean saveFullConfigJson(String jsonConfig) {
        if (jsonConfig == null || jsonConfig.trim().isEmpty()) return false;
        prefs.edit().putString("full_user_config_v410", jsonConfig).apply();
        return true;
    }

    @JavascriptInterface
    public String loadFullConfigJson() { return prefs.getString("full_user_config_v410", ""); }

    @JavascriptInterface
    public String setWideHitboxMode(boolean enabled, String ratio) {
        if (getSavedKey().isEmpty() || isClientTampered()) return "NEED_AUTH";
        String targetRatio = (ratio != null && !ratio.isEmpty()) ? ratio : "4:3";
        prefs.edit().putBoolean("wide_hitbox_mode", enabled).apply();
        if (enabled) {
            TeleportEngine.setTouchSamplingBoost(true, 360);
            TeleportEngine.setEnhancedRecoilFix(true, 100);
            TeleportEngine.enableWideHitboxQuantization(true);
            activateStretchFloatingButton(targetRatio);
            return "OK|" + targetRatio;
        } else {
            TeleportEngine.enableWideHitboxQuantization(false);
            resetResolution();
            return "OFF";
        }
    }

    @JavascriptInterface
    public boolean isShizukuReady() { return TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted(); }

    @JavascriptInterface
    public boolean isShizukuActive() { return TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted(); }

    @JavascriptInterface
    public void requestShizukuPermission() { TeleportEngine.requestShizukuPermissionIfNeeded(); }

    @JavascriptInterface
    public void healTouch() {
        TeleportEngine.requestShizukuPermissionIfNeeded();
        TeleportEngine.healTouchAndRestart();
    }

    @JavascriptInterface
    public void startTeleport(String presetId) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.startTeleport(presetId);
    }

    @JavascriptInterface
    public void stopTeleport() { TeleportEngine.stopTeleport(); }

    @JavascriptInterface
    public void updateParameters(double sharp, double sx, double sy, int chunks, int chunkFast, double straight, int stick, int grid, int pause, double accel) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.updateParameters(sharp, sx, sy, chunks, chunkFast, straight, stick, grid, pause, accel);
    }

    @JavascriptInterface
    public void setRawInputNoSmoothing(boolean enabled) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.setRawInputNoSmoothing(enabled);
    }

    @JavascriptInterface
    public void setVulkanGpuTurbo(boolean enabled) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.setVulkanGpuTurbo(enabled);
    }

    @JavascriptInterface
    public void setNetPingStabilizer(boolean enabled) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.setNetPingStabilizer(enabled);
    }

    @JavascriptInterface
    public void setTouchSamplingBoost(boolean enabled, int hz) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.setTouchSamplingBoost(enabled, hz);
    }

    @JavascriptInterface
    public String performRamBoost() { return TeleportEngine.performRamBoost(context); }

    @JavascriptInterface
    public void setHzThermalGuard(boolean enabled) { TeleportEngine.setHzThermalGuard(enabled); }

    @JavascriptInterface
    public void applyShooterOptimizer(int fpsLock, int gpuBoost, int frameTime, int edgeSens, int multiTouch, int netBuffer, int thermalLim) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.applyShooterOptimizer(fpsLock, gpuBoost, frameTime, edgeSens, multiTouch, netBuffer, thermalLim);
    }

    @JavascriptInterface
    public void setEnhancedRecoilFix(boolean enabled, int power) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.setEnhancedRecoilFix(enabled, power);
    }

    @JavascriptInterface
    public void setAutoGameDpi(boolean enabled, int dpi) {}

    @JavascriptInterface
    public void setPixelDownscaleFactor(int pct) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        TeleportEngine.setPixelDownscaleFactor(pct);
    }

    @JavascriptInterface
    public String runSmartDeviceScan() { return TeleportEngine.runSmartDeviceScan(context); }

    @JavascriptInterface
    public void applyUltraProTouchMatrix(int modeLevel) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        ProfileManager.addTeleportCountToTotal(context, 1);
        TeleportEngine.startTeleport("extreme");
    }

    @JavascriptInterface
    public String uploadCloudConfig(String title, String presetJson) {
        String key = getSavedKey();
        if (key.isEmpty() || isClientTampered()) return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        try {
            Callable<String> task = () -> ApiManager.uploadCloudPreset(key, getAllHwidCandidatesCsv(key), title, presetJson);
            Future<String> future = networkExecutor.submit(task);
            return future.get(5500, TimeUnit.MILLISECONDS);
        } catch (Exception e) { return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка создания кода\"}"; }
    }

    @JavascriptInterface
    public String downloadCloudConfig(String shareCode) {
        String key = getSavedKey();
        if (key.isEmpty() || isClientTampered()) return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        try {
            Callable<String> task = () -> ApiManager.downloadCloudPresetByCode(key, getAllHwidCandidatesCsv(key), shareCode);
            Future<String> future = networkExecutor.submit(task);
            return future.get(5500, TimeUnit.MILLISECONDS);
        } catch (Exception e) { return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка загрузки конфига\"}"; }
    }

    @JavascriptInterface
    public void updateCrosshair(String type) {
        final String cleanType = (type == null) ? "off" : type.trim();
        if (getSavedKey().isEmpty() && !"off".equalsIgnoreCase(cleanType)) return;

        mainHandler.post(() -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).updateCrosshair("off");
            if ("off".equalsIgnoreCase(cleanType) || "none".equalsIgnoreCase(cleanType) || cleanType.isEmpty()) {
                if (crosshairView != null) { crosshairView.setCrosshairType("off"); removeOverlayViewSafe(crosshairView); }
                crosshairView = null; isCrosshairShown = false;
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                requestOverlayPermission();
                return;
            }
            if (!isCrosshairShown || crosshairView == null) {
                crosshairView = new CrosshairOverlayView(context);
                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) flags |= WindowManager.LayoutParams.FLAG_SECURE;
                crosshairParams = new WindowManager.LayoutParams(dpToPx(48), dpToPx(48), overlayType, flags, PixelFormat.TRANSLUCENT);
                crosshairParams.gravity = Gravity.CENTER; crosshairParams.x = 0; crosshairParams.y = 0; crosshairParams.alpha = 0.78f;
                try { windowManager.addView(crosshairView, crosshairParams); isCrosshairShown = true; } catch (Exception ignored) {}
            }
            if (crosshairView != null) { crosshairView.setVisibility(View.VISIBLE); crosshairView.setCrosshairType(cleanType); }
        });
    }

    @JavascriptInterface
    public void setCrosshairOffset(int offsetXDp, int offsetYDp) {
        mainHandler.post(() -> {
            if (isCrosshairShown && crosshairView != null && crosshairParams != null && windowManager != null) {
                crosshairParams.x = 0; crosshairParams.y = 0;
                try { windowManager.updateViewLayout(crosshairView, crosshairParams); } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void updateCrosshairStyle(int scalePct, int opacityPct, String hexColor) {
        mainHandler.post(() -> { if (crosshairView != null) crosshairView.setStyle(scalePct, opacityPct, hexColor); });
    }

    @JavascriptInterface
    public void updateCrosshairAdvanced(String type, String hexColor, int opacityPct, int lengthDp, int thicknessDp, int gapDp, int dotSizeDp, boolean showOutline) {
        if ("off".equalsIgnoreCase(type) || type == null || type.trim().isEmpty() || opacityPct <= 0) { updateCrosshair("off"); return; }
        if (getSavedKey().isEmpty()) return;
        updateCrosshair(type);
        mainHandler.post(() -> {
            if (crosshairView != null && isCrosshairShown) {
                crosshairView.setVisibility(View.VISIBLE);
                crosshairView.setAdvancedConfig(type, hexColor, opacityPct, lengthDp, thicknessDp, gapDp, dotSizeDp, showOutline);
            }
        });
    }

    @JavascriptInterface
    public void updateOverlayStealthFlag(boolean stealth) {
        this.stealthOverlayFlag = stealth;
        legacyPrefs.edit().putBoolean("stealth_overlays", stealth).apply();
        mainHandler.post(() -> {
            try {
                if (isStretchOverlayShown && floatingStretchBtn != null && stretchParams != null) {
                    if (stealth) stretchParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                    else stretchParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    windowManager.updateViewLayout(floatingStretchBtn, stretchParams);
                }
                if (isCrosshairShown && crosshairView != null && crosshairParams != null) {
                    if (stealth) crosshairParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                    else crosshairParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    windowManager.updateViewLayout(crosshairView, crosshairParams);
                }
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void setStealthOverlayMode(boolean stealth) { updateOverlayStealthFlag(stealth); }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        return Build.MODEL + "|" + TeleportEngine.getNativeShortSide() + "x" + TeleportEngine.getNativeLongSide() + " (120 Гц)";
    }

    @JavascriptInterface
    public String getSavedKey() {
        String vaultKey = readEncryptedKeyFromVault();
        if (vaultKey.isEmpty() || isBlacklistedArtifact(vaultKey)) {
            migrateExistingKeysAcrossStores();
            vaultKey = readEncryptedKeyFromVault();
        }
        if (vaultKey.isEmpty() || isBlacklistedArtifact(vaultKey)) return "";
        safeNativeSetSession(vaultKey, true);
        return vaultKey;
    }

    @JavascriptInterface
    public String getSavedNickname() {
        String nick = prefs.getString("saved_player_nick", "");
        if (nick == null || nick.trim().isEmpty()) nick = profilePrefs.getString("nickname", "");
        if (nick == null || nick.trim().isEmpty()) nick = legacyPrefs.getString("nickname", "");
        return nick == null ? "" : nick.trim();
    }

    @JavascriptInterface
    public void savePlayerNickname(String nick) {
        if (nick != null && !nick.trim().isEmpty()) {
            prefs.edit().putString("saved_player_nick", nick.trim()).apply();
            legacyPrefs.edit().putString("nickname", nick.trim()).apply();
            profilePrefs.edit().putString("nickname", nick.trim()).apply();
        }
    }

    private void markKeyActivated(String nick, String cleanKey, JSONObject serverRes) {
        ApiManager.setSessionVerified(true);
        safeNativeSetSession(cleanKey, true);
        saveEncryptedKeyToVault(cleanKey);
        recordWorkingHwid(cleanKey, serverRes);
        getOrInitKeyDeadlineMs(cleanKey, serverRes);
        prefs.edit().remove("saved_license_key").putString("owned_token_" + cleanKey, "OWNED_" + cleanKey).putBoolean(PREF_FIRST_LOGIN_DONE, true).putLong("last_server_ok_ts", System.currentTimeMillis()).apply();
        legacyPrefs.edit().putString("license_key", cleanKey).apply();
        ProfileManager.saveActivatedProfile(context, nick, cleanKey);
    }

    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (isClientTampered()) {
            forceKickExpiredUser("Обнаружена модификация файлов приложения!");
            executeNativeKillOnTamper();
            return "ERROR|Обнаружена модификация файлов приложения!";
        }
        final String cleanKey = ApiManager.normalizeKeyInput(key);
        if (cleanKey.length() < 5 || isBlacklistedArtifact(cleanKey)) return "ERROR|Введите лицензионный ключ из Telegram-бота";
        final String hwidCsv = getAllHwidCandidatesCsv(cleanKey);
        final String nick = getSavedNickname().isEmpty() ? "dare" : getSavedNickname();

        try {
            Callable<String> task = () -> ApiManager.activateLicense(cleanKey, hwidCsv, nick);
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(8500, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            if ("offline".equalsIgnoreCase(status)) {
                if (cleanKey.equalsIgnoreCase(getSavedKey()) || isValidOfficialBotKeyFormat(cleanKey)) {
                    markKeyActivated(nick, cleanKey, null);
                    SecurityShield.issueVerifiedSessionToken(context, cleanKey);
                    String lbl = buildLiveSubscriptionLabel(null, cleanKey);
                    return "OK|" + ("EXPIRED".equals(lbl) ? "АКТИВНА" : lbl);
                }
                return "ERROR|Сервер временно недоступен, проверьте интернет";
            }

            boolean explicitlyBanned = "banned".equalsIgnoreCase(status) || "blocked".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status) || "device_mismatch".equalsIgnoreCase(status) || res.optBoolean("blocked", false);
            boolean ok = (res.optBoolean("ok", false) || "ok".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) && !explicitlyBanned;

            if (ok) {
                markKeyActivated(nick, cleanKey, res);
                SecurityShield.issueVerifiedSessionToken(context, cleanKey);
                String liveLabel = buildLiveSubscriptionLabel(res, cleanKey);
                if ("EXPIRED".equals(liveLabel)) liveLabel = "АКТИВНА";
                return "OK|" + liveLabel;
            }
            return "ERROR|" + res.optString("message", "Ключ не найден или истёк!");
        } catch (Exception e) {
            if (cleanKey.equalsIgnoreCase(getSavedKey()) || isValidOfficialBotKeyFormat(cleanKey)) {
                markKeyActivated(nick, cleanKey, null);
                SecurityShield.issueVerifiedSessionToken(context, cleanKey);
                String lbl = buildLiveSubscriptionLabel(null, cleanKey);
                return "OK|" + ("EXPIRED".equals(lbl) ? "АКТИВНА" : lbl);
            }
            return "ERROR|Неверный формат ключа. Получите ключ в боте @SO2TeleportShop_bot";
        }
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        if (isClientTampered()) {
            forceKickExpiredUser("Обнаружена модификация приложения!");
            executeNativeKillOnTamper();
            return "BANNED|Модификация файлов приложения";
        }

        final String cleanKey = (key != null && !key.trim().isEmpty()) ? ApiManager.normalizeKeyInput(key) : getSavedKey();
        if (cleanKey.length() < 5 || isBlacklistedArtifact(cleanKey)) return "EXPIRED|Введите ключ";

        long lastOkTs = prefs.getLong("last_server_ok_ts", 0L);
        if (System.currentTimeMillis() - lastOkTs < 25000L && cleanKey.equalsIgnoreCase(getSavedKey())) {
            String cachedLabel = buildLiveSubscriptionLabel(null, cleanKey);
            if (!"EXPIRED".equals(cachedLabel)) return "OK|" + cachedLabel;
        }

        try {
            final String hwidCsv = getAllHwidCandidatesCsv(cleanKey);
            Callable<String> task = () -> ApiManager.verifyLicense(cleanKey, hwidCsv);
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(6500, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            if ("offline".equalsIgnoreCase(status)) {
                String fallbackLabel = buildLiveSubscriptionLabel(null, cleanKey);
                return "OK|" + ("EXPIRED".equals(fallbackLabel) ? "АКТИВНА" : fallbackLabel);
            }

            if ("banned".equalsIgnoreCase(status) || "blocked".equalsIgnoreCase(status)) {
                String msg = res.optString("message", "Ключ заблокирован!");
                forceKickExpiredUser(msg);
                return "BANNED|" + msg;
            }

            boolean ok = res.optBoolean("ok", false) || "ok".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status);
            if (ok) {
                String nick = getSavedNickname().isEmpty() ? "dare" : getSavedNickname();
                markKeyActivated(nick, cleanKey, res);
                SecurityShield.issueVerifiedSessionToken(context, cleanKey);
                String label = buildLiveSubscriptionLabel(res, cleanKey);
                if ("EXPIRED".equals(label)) label = "АКТИВНА";
                return "OK|" + label;
            }

            String fallbackLabel = buildLiveSubscriptionLabel(null, cleanKey);
            return "OK|" + ("EXPIRED".equals(fallbackLabel) ? "АКТИВНА" : fallbackLabel);
        } catch (Exception e) {
            String fallbackLabel = buildLiveSubscriptionLabel(null, cleanKey);
            return "OK|" + ("EXPIRED".equals(fallbackLabel) ? "АКТИВНА" : fallbackLabel);
        }
    }

    @JavascriptInterface
    public void logoutLicenseKey() { forceKickExpiredUser("Смена логина или лицензионного ключа"); }

    @JavascriptInterface
    public void openTelegramBot() {
        mainHandler.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SO2TeleportShop_bot"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void openSupportTelegram() {
        mainHandler.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/talent_dare"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public String requestFriendsHub(String jsonPayload) {
        if (getSavedKey().isEmpty() || !safeNativeIsSessionOk() || isClientTampered()) return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        try {
            Callable<String> task = () -> ApiManager.callFriendsApi(jsonPayload);
            Future<String> future = networkExecutor.submit(task);
            return future.get(4500, TimeUnit.MILLISECONDS);
        } catch (Exception e) { return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка сети\"}"; }
    }

    private static class BridgeTrashView extends View {
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outerRect = new RectF();
        private final RectF rect = new RectF();
        private final float density;
        private boolean hovered = false;

        public BridgeTrashView(Context context) {
            super(context);
            this.density = context.getResources().getDisplayMetrics().density;
            borderPaint.setStyle(Paint.Style.STROKE);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * density);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void setHovered(boolean h) {
            if (this.hovered != h) {
                this.hovered = h;
                animate().scaleX(h ? 1.12f : 1.0f).scaleY(h ? 1.12f : 1.0f).setDuration(120).start();
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float pad = 6f * density;
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);

            if (hovered) {
                outerRect.set(pad * 0.3f, pad * 0.3f, getWidth() - pad * 0.3f, getHeight() - pad * 0.3f);
                glowPaint.setColor(Color.parseColor("#55FF453A"));
                canvas.drawRoundRect(outerRect, 999f, 999f, glowPaint);
                bgPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, Color.parseColor("#EEFF453A"), Color.parseColor("#CCFF3B30"), Shader.TileMode.CLAMP));
                borderPaint.setStrokeWidth(1.5f * density);
                borderPaint.setColor(Color.WHITE);
                textPaint.setColor(Color.WHITE);
            } else {
                bgPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, Color.parseColor("#EE2C2C2E"), Color.parseColor("#EE1C1C1E"), Shader.TileMode.CLAMP));
                borderPaint.setStrokeWidth(1f * density);
                borderPaint.setColor(Color.parseColor("#99545458"));
                textPaint.setColor(Color.WHITE);
            }

            canvas.drawRoundRect(rect, 999f, 999f, bgPaint);
            canvas.drawRoundRect(rect, 999f, 999f, borderPaint);
            String label = hovered ? "ОТПУСТИТЕ, ЧТОБЫ УБРАТЬ «T»" : "ПЕРЕТАЩИТЕ СЮДА ДЛЯ УДАЛЕНИЯ";
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, getWidth() / 2f, cy, textPaint);
        }
    }

    private static class CrosshairOverlayView extends View {
        private String type = "cross";
        private int opacity = 100;
        private int lengthDp = 10;
        private int thicknessDp = 3;
        private int gapDp = 3;
        private int dotSizeDp = 3;
        private boolean outline = true;
        private int color = Color.parseColor("#30D158");
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CrosshairOverlayView(Context context) {
            super(context);
            outlinePaint.setColor(Color.BLACK);
            paint.setStrokeCap(Paint.Cap.ROUND);
            outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        }

        public void setCrosshairType(String t) { this.type = t; invalidate(); }

        public void setStyle(int scalePct, int opacityPct, String hex) {
            this.lengthDp = Math.max(3, Math.round(3 + (scalePct / 100f) * 18f));
            this.opacity = opacityPct;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        public void setAdvancedConfig(String t, String hex, int op, int len, int thick, int gap, int dot, boolean out) {
            this.type = t; this.opacity = op; this.lengthDp = len; this.thicknessDp = thick;
            this.gapDp = gap; this.dotSizeDp = dot; this.outline = out;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            if ("off".equalsIgnoreCase(type)) return;

            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float lenPx = lengthDp * d;
            float thickPx = Math.max(1.2f, thicknessDp * 0.78f * d);
            float gapPx = gapDp * d;
            float dotRadPx = Math.max(1.3f, dotSizeDp * 0.68f * d);

            int alpha = Math.max(25, Math.min(255, Math.round(opacity * 2.55f)));
            paint.setColor(color); paint.setAlpha(alpha); paint.setStrokeWidth(thickPx);
            outlinePaint.setAlpha(Math.min(220, alpha)); outlinePaint.setStrokeWidth(thickPx + 2.0f * d);

            if ("dot".equalsIgnoreCase(type)) {
                if (outline) { outlinePaint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, dotRadPx + 1.1f * d, outlinePaint); }
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, dotRadPx, paint);
                return;
            }

            if ("circle".equalsIgnoreCase(type) || "circledot".equalsIgnoreCase(type)) {
                float rad = Math.max(3.5f * d, lenPx * 0.65f);
                if (outline) { outlinePaint.setStyle(Paint.Style.STROKE); canvas.drawCircle(cx, cy, rad, outlinePaint); }
                paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(cx, cy, rad, paint);
                if ("circledot".equalsIgnoreCase(type)) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, dotRadPx, paint); }
                return;
            }

            if ("box".equalsIgnoreCase(type)) {
                float half = Math.max(2.0f * d, lenPx * 0.30f);
                if (outline) { outlinePaint.setStyle(Paint.Style.STROKE); canvas.drawRect(cx - half, cy - half, cx + half, cy + half, outlinePaint); }
                paint.setStyle(Paint.Style.STROKE); canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
                return;
            }

            if ("chevron".equalsIgnoreCase(type)) {
                float arm = Math.max(3.5f * d, lenPx * 0.55f);
                if (outline) { outlinePaint.setStyle(Paint.Style.STROKE); canvas.drawLine(cx, cy - gapPx * 0.4f, cx - arm, cy + arm, outlinePaint); canvas.drawLine(cx, cy - gapPx * 0.4f, cx + arm, cy + arm, outlinePaint); }
                paint.setStyle(Paint.Style.STROKE); canvas.drawLine(cx, cy - gapPx * 0.4f, cx - arm, cy + arm, paint); canvas.drawLine(cx, cy - gapPx * 0.4f, cx + arm, cy + arm, paint);
                return;
            }

            if ("corners".equalsIgnoreCase(type)) {
                float dist = Math.max(4f * d, (gapPx + lenPx) * 0.48f);
                float leg = Math.max(2.5f * d, lenPx * 0.32f);
                Paint[] layers = outline ? new Paint[]{outlinePaint, paint} : new Paint[]{paint};
                for (Paint p : layers) {
                    p.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(cx - dist, cy - dist, cx - dist + leg, cy - dist, p); canvas.drawLine(cx - dist, cy - dist, cx - dist, cy - dist + leg, p);
                    canvas.drawLine(cx + dist, cy - dist, cx + dist - leg, cy - dist, p); canvas.drawLine(cx + dist, cy - dist, cx + dist, cy - dist + leg, p);
                    canvas.drawLine(cx - dist, cy + dist, cx - dist + leg, cy + dist, p); canvas.drawLine(cx - dist, cy + dist, cx - dist, cy + dist - leg, p);
                    canvas.drawLine(cx + dist, cy + dist, cx + dist - leg, cy + dist, p); canvas.drawLine(cx + dist, cy + dist, cx + dist, cy - dist - leg, p);
                }
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, dotRadPx * 0.85f, paint);
                return;
            }

            boolean hasTop = !"tshape".equalsIgnoreCase(type);
            if (outline) {
                outlinePaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, outlinePaint);
                canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, outlinePaint);
                canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, outlinePaint);
                if (hasTop) canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, outlinePaint);
            }

            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, paint);
            canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, paint);
            canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, paint);
            if (hasTop) canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, paint);

            if ("crossdot".equalsIgnoreCase(type)) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, dotRadPx, paint); }
        }
    }
}
