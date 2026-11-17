package com.teleport.so2;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.media.AudioManager;
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

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
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
    private static final String VAULT_PREF_NAME = "SO2Teleport_SecureVault_V12";
    private static final String VAULT_ENC_KEY = "enc_saved_license_v12";

    private static boolean isNativeLoaded = false;
    static {
        try {
            System.loadLibrary("so2");
            isNativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            isNativeLoaded = false;
        }
    }

    // Полный набор нативных JNI-методов (закрывает пункты #1–#25)
    private static native void nativeInitBridge(Context context);
    private static native boolean nativeIsClientTampered(Context context);
    private static native boolean nativeVerifyAssetIntegrity(Context context);
    private static native String nativeGetDeviceHwid(Context context);
    private static native String nativeGetEncryptedEndpoint(int mode);
    private static native String nativeGateShizukuCmd(String rawCmd);
    private static native String nativeGetKeystoreAlias(int slot);
    private static native boolean nativeUnlockOfflineVipMode();
    private static native String nativeComputeHwid(String drmHex, String androidId, String boardInfo);
    private static native String nativeComputeDailyHmac(String payload, String dayUtc);
    private static native boolean nativeVerifyServerResponseSignature(String cleanKey, String hwid, String rawJson, String dayUtc);
    private static native boolean nativeVerifyServerHmac(String cleanKey, String hwid, String status, String serverSig, String dayUtc);
    private static native String nativeActivateLicense(Context context, String key, String nick);
    private static native void nativeForceKick(Context context, boolean killProcess);
    private static native boolean nativeIsHtmlTampered(byte[] htmlBytes);
    private static native boolean nativeIsBlacklisted(String candidate);
    private static native void nativeSetSessionVerified(String cleanKey, boolean verified);
    private static native boolean nativeIsSessionVerified();
    private static native String nativeGetSessionKey();
    private static native void nativeForceTerminate();

    // Нативный движок стабилизации и инжекта касаний (GameTouchLayer C++)
    private static native void nativeConfigureTouchLayer(float stickiness, float heaviness, float sharpness, float sprayPull, float aimScale);
    private static native void nativeResetFinger(int pointerId);
    private static native float[] nativeStabilizeTouchDelta(int pointerId, float rawDx, float rawDy);

    private final Context context;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final SharedPreferences vaultPrefs;
    private final SharedPreferences legacyPrefs;
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
    private int savedCrosshairOffsetX = 0;
    private int savedCrosshairOffsetY = 0;
    private boolean stealthOverlayFlag = false;

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.prefs = context.getSharedPreferences("SO2TeleportPrefs_V410", Context.MODE_PRIVATE);
        this.vaultPrefs = context.getSharedPreferences(VAULT_PREF_NAME, Context.MODE_PRIVATE);
        this.legacyPrefs = context.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        // Пункт #10: Обязательная инициализация в C++ (если удалить вызов — libso2.so заблокирует все функции)
        if (isNativeLoaded) {
            nativeInitBridge(this.context);
        } else {
            executeNativeKillOnTamper();
        }

        TeleportEngine.setServerAuthorizedFromVerifiedHmac(false);
        TeleportEngine.init(context);
        TeleportEngine.requestShizukuPermissionIfNeeded();
        startLicenseWatchdog();
    }

    // =========================================================================
    // 1. НАТИВНЫЕ ШЛЮЗЫ И КОНТРОЛЬ ЦЕЛОСТНОСТИ (#3, #5, #8, #11-#14, #19-#22)
    // =========================================================================
    public static String getEncryptedServerEndpoint(int mode) {
        if (!isNativeLoaded || mode < 0 || mode > 2 || ApiManager.isHoneypotTriggered()) {
            return "";
        }
        try {
            String url = nativeGetEncryptedEndpoint(mode);
            return (url != null) ? url : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String gateCommandThroughNative(String rawCmd) {
        if (!isNativeLoaded || rawCmd == null || rawCmd.isEmpty()) return "";
        try {
            return nativeGateShizukuCmd(rawCmd);
        } catch (Throwable ignored) {
            return "";
        }
    }

    // Пункты #11, #12, #13: Единый шлюз Shizuku для всех методов AndroidBridge
    private void execGatedShizuku(String rawCmd) {
        String gated = gateCommandThroughNative(rawCmd);
        if (gated != null && !gated.isEmpty()) {
            TeleportEngine.execShizukuCommand(gated);
        }
    }

    public static String getNativeHardwareHwid(Context ctx) {
        if (!isNativeLoaded || ctx == null) return "";
        try {
            return nativeGetDeviceHwid(ctx);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void configureNativeTouchLayer(float stick, float heavy, float sharp, float pull, float scale) {
        if (!isNativeLoaded) return;
        try {
            nativeConfigureTouchLayer(stick, heavy, sharp, pull, scale);
        } catch (Throwable ignored) {}
    }

    public static float[] stabilizeTouchDeltaNative(int pointerId, float dx, float dy) {
        if (!isNativeLoaded) return new float[]{dx, dy};
        try {
            float[] res = nativeStabilizeTouchDelta(pointerId, dx, dy);
            return (res != null && res.length == 2) ? res : new float[]{dx, dy};
        } catch (Throwable ignored) {
            return new float[]{dx, dy};
        }
    }

    public static String signPayloadNative(String payload, String dayUtc) {
        if (!isNativeLoaded || ApiManager.isHoneypotTriggered()) return "";
        try {
            String sig = nativeComputeDailyHmac(payload, dayUtc);
            return (sig != null) ? sig : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isNativeServerAuthorized() {
        if (!isNativeLoaded || ApiManager.isHoneypotTriggered()) return false;
        try {
            return nativeIsSessionVerified();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void revokeNativeSessionFromApi() {
        if (isNativeLoaded) {
            try {
                nativeSetSessionVerified("", false);
            } catch (Throwable ignored) {}
        }
        TeleportEngine.setServerAuthorizedFromVerifiedHmac(false);
    }

    // Пункт #14: Гарантированное завершение процесса при любом тампере (и в C++, и в Java)
    private void executeNativeKillOnTamper() {
        if (isNativeLoaded) {
            try {
                nativeForceKick(context, true);
                nativeForceTerminate();
            } catch (Throwable ignored) {}
        }
        Process.killProcess(Process.myPid());
        System.exit(137);
    }

    @JavascriptInterface
    public String getVoiceServerEndpoint() {
        if (!isNativeServerAuthorized()) return "";
        return getEncryptedServerEndpoint(2);
    }

    // =========================================================================
    // 2. ANDROID KEYSTORE С НАТИВНЫМИ АЛИАСАМИ И HMAC
    // =========================================================================
    private SecretKey getOrCreateKeystoreSecret() throws Exception {
        String aliasStrong = isNativeLoaded ? nativeGetKeystoreAlias(1) : "ks_sb_v12";
        String aliasTee = isNativeLoaded ? nativeGetKeystoreAlias(2) : "ks_tee_v12";

        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        if (ks.containsAlias(aliasStrong)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(aliasStrong, null);
            if (entry != null) return entry.getSecretKey();
        }
        if (ks.containsAlias(aliasTee)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(aliasTee, null);
            if (entry != null) return entry.getSecretKey();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                KeyGenerator kgStrong = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                KeyGenParameterSpec strongSpec = new KeyGenParameterSpec.Builder(
                        aliasStrong,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setIsStrongBoxBacked(true)
                        .build();
                kgStrong.init(strongSpec);
                return kgStrong.generateKey();
            } catch (Throwable ignored) {}
        }

        KeyGenerator kgTee = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec teeSpec = new KeyGenParameterSpec.Builder(
                aliasTee,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        kgTee.init(teeSpec);
        return kgTee.generateKey();
    }

    private String computeStaticVaultHmac(String data) {
        if (!isNativeLoaded) return "";
        try {
            return nativeComputeDailyHmac(data, "VAULT_STATIC_EPOCH_V12");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void saveEncryptedKeyToVault(String cleanKey) {
        try {
            if (cleanKey == null || cleanKey.isEmpty()) {
                vaultPrefs.edit().remove(VAULT_ENC_KEY).remove(VAULT_ENC_KEY + "_hmac").apply();
                revokeNativeSessionFromApi();
                return;
            }
            String hwid = getDeviceHwid();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreSecret());
            byte[] iv = cipher.getIV();
            cipher.updateAAD(hwid.getBytes(StandardCharsets.UTF_8));
            byte[] enc = cipher.doFinal(cleanKey.getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
            String sig = computeStaticVaultHmac(blob + "|" + hwid);
            vaultPrefs.edit()
                    .putString(VAULT_ENC_KEY, blob)
                    .putString(VAULT_ENC_KEY + "_hmac", sig)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private String readEncryptedKeyFromVault() {
        if (!isNativeLoaded || ApiManager.isHoneypotTriggered()) return "";
        try {
            String blob = vaultPrefs.getString(VAULT_ENC_KEY, "");
            String savedSig = vaultPrefs.getString(VAULT_ENC_KEY + "_hmac", "");
            if (blob != null && blob.contains(":") && savedSig != null && savedSig.length() >= 64) {
                String hwid = getDeviceHwid();
                String expectedSig = computeStaticVaultHmac(blob + "|" + hwid);
                if (expectedSig.isEmpty() || !expectedSig.equalsIgnoreCase(savedSig)) {
                    vaultPrefs.edit().clear().apply();
                    return "";
                }
                String[] parts = blob.split(":", 2);
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreSecret(), new GCMParameterSpec(128, iv));
                cipher.updateAAD(hwid.getBytes(StandardCharsets.UTF_8));
                String dec = new String(cipher.doFinal(enc), StandardCharsets.UTF_8).trim().toUpperCase(Locale.US);
                if (!nativeIsBlacklisted(dec)) return dec;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    // =========================================================================
    // 3. НАТИВНЫЙ HWID (#8) И СРОК ДЕЙСТВИЯ КЛЮЧА С ПОДПИСЬЮ (#9, #15)
    // =========================================================================
    @JavascriptInterface
    public String getDeviceHwid() {
        if (!isNativeLoaded) {
            executeNativeKillOnTamper();
            return "";
        }
        String hwid = nativeGetDeviceHwid(context);
        if (hwid == null || hwid.length() < 12 || nativeIsBlacklisted(hwid)) {
            executeNativeKillOnTamper();
            return "";
        }
        return hwid;
    }

    @JavascriptInterface
    public String getDisplayHwid() {
        return getDeviceHwid();
    }

    @JavascriptInterface
    public String getHardwareId() {
        return getDeviceHwid();
    }

    private long getDefaultDurationMsForKey(String cleanKey) {
        if (cleanKey == null || !isNativeLoaded || nativeIsBlacklisted(cleanKey)) return 0L;
        String u = cleanKey.toUpperCase(Locale.US);
        if (u.startsWith("TP-1HR") || u.contains("1HR") || u.contains("-1H-")) return 3600L * 1000L;
        if (u.startsWith("TP1D") || u.contains("-1D-") || u.contains("24H")) return 24L * 3600L * 1000L;
        if (u.startsWith("TP7D") || u.contains("-7D-")) return 7L * 24L * 3600L * 1000L;
        if (u.startsWith("TP21D") || u.contains("21D")) return 21L * 24L * 3600L * 1000L;
        if (u.startsWith("TP50D") || u.contains("50D")) return 50L * 24L * 3600L * 1000L;
        if (u.startsWith("TP90D") || u.contains("90D")) return 90L * 24L * 3600L * 1000L;
        return 30L * 24L * 3600L * 1000L;
    }

    private void saveSignedDeadline(String cleanKey, long deadlineMs) {
        String prefKey = "key_deadline_ms_" + cleanKey;
        String sig = computeStaticVaultHmac(cleanKey + "|" + deadlineMs + "|" + getDeviceHwid());
        prefs.edit()
                .putLong(prefKey, deadlineMs)
                .putString(prefKey + "_sig", sig)
                .apply();
    }

    // Пункты #9, #15: Только подписанный HMAC-дедлайн и решение сервера для бессрочных ключей
    private long getOrInitKeyDeadlineMs(String cleanKey, JSONObject serverRes) {
        if (cleanKey == null || cleanKey.isEmpty() || !isNativeLoaded || nativeIsBlacklisted(cleanKey)) return 0L;
        String deadlinePrefKey = "key_deadline_ms_" + cleanKey;
        long now = System.currentTimeMillis();

        if (serverRes != null) {
            if (serverRes.optBoolean("is_lifetime", false)) {
                saveSignedDeadline(cleanKey, -1L);
                return -1L;
            }
            long remSec = serverRes.optLong("remaining_sec",
                    serverRes.optLong("time_left_sec",
                            serverRes.optLong("seconds_left", -1L)));
            if (remSec > 80000000L) {
                saveSignedDeadline(cleanKey, -1L);
                return -1L;
            }
            if (remSec > 0) {
                long newDeadline = now + (remSec * 1000L);
                saveSignedDeadline(cleanKey, newDeadline);
                return newDeadline;
            }
            long expUnix = serverRes.optLong("expires_at", serverRes.optLong("exp", -1L));
            if (expUnix > 1700000000L) {
                long newDeadline = (expUnix < 100000000000L) ? (expUnix * 1000L) : expUnix;
                saveSignedDeadline(cleanKey, newDeadline);
                return newDeadline;
            }
        }

        long savedDeadline = prefs.getLong(deadlinePrefKey, 0L);
        String savedSig = prefs.getString(deadlinePrefKey + "_sig", "");
        if (savedDeadline != 0L) {
            String expectedSig = computeStaticVaultHmac(cleanKey + "|" + savedDeadline + "|" + getDeviceHwid());
            if (!expectedSig.isEmpty() && expectedSig.equalsIgnoreCase(savedSig)) {
                return savedDeadline;
            }
            return 0L;
        }

        long computedDeadline = now + getDefaultDurationMsForKey(cleanKey);
        saveSignedDeadline(cleanKey, computedDeadline);
        return computedDeadline;
    }

    private String buildLiveSubscriptionLabel(JSONObject serverRes, String cleanKey) {
        if (cleanKey == null || cleanKey.isEmpty() || !isNativeLoaded || nativeIsBlacklisted(cleanKey)) return "EXPIRED";

        long deadlineMs = getOrInitKeyDeadlineMs(cleanKey, serverRes);
        if (deadlineMs == -1L) return "БЕССРОЧНО";

        long diffMs = deadlineMs - System.currentTimeMillis();
        if (diffMs <= 0L) return "EXPIRED";

        if (serverRes != null) {
            String serverText = serverRes.optString("expires_in",
                    serverRes.optString("days_left",
                            serverRes.optString("time_left", "")));
            if (serverText != null && !serverText.isEmpty() && !serverText.equalsIgnoreCase("null")) {
                if (serverText.toUpperCase(Locale.US).contains("НАВСЕГДА") || serverText.toUpperCase(Locale.US).contains("БЕССРОЧНО")) {
                    return "БЕССРОЧНО";
                }
                if (serverText.toUpperCase(Locale.US).contains("АКТИВНА")) return serverText;
                return "АКТИВНА (" + serverText + ")";
            }
        }

        long totalMinutes = diffMs / (60L * 1000L);
        long days = totalMinutes / (60L * 24L);
        long hours = (totalMinutes % (60L * 24L)) / 60L;
        long mins = totalMinutes % 60L;

        if (days >= 1800) return "БЕССРОЧНО";
        if (days > 0) return "АКТИВНА (" + days + " дн. " + hours + " ч.)";
        if (hours > 0) return "АКТИВНА (" + hours + " ч. " + mins + " мин.)";
        return "АКТИВНА (" + Math.max(1, mins) + " мин.)";
    }

    private void startLicenseWatchdog() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isNativeLoaded || nativeIsClientTampered(context)) {
                    executeNativeKillOnTamper();
                    return;
                }
                final String savedKey = readEncryptedKeyFromVault();
                if (!savedKey.isEmpty() && prefs.getBoolean(PREF_FIRST_LOGIN_DONE, false)) {
                    String liveStatus = buildLiveSubscriptionLabel(null, savedKey);
                    if ("EXPIRED".equals(liveStatus)) {
                        forceKickExpiredUser("Срок действия вашего ключа истёк!");
                        return;
                    }

                    networkExecutor.execute(() -> {
                        try {
                            String hwid = getDeviceHwid();
                            String raw = ApiManager.verifyLicense(savedKey, hwid);
                            JSONObject res = new JSONObject(raw);
                            String status = res.optString("status", "");

                            boolean explicitlyBlocked = "expired".equalsIgnoreCase(status)
                                    || "blocked".equalsIgnoreCase(status)
                                    || "banned".equalsIgnoreCase(status)
                                    || "device_mismatch".equalsIgnoreCase(status)
                                    || "offline".equalsIgnoreCase(status)
                                    || res.optBoolean("blocked", false);

                            if (explicitlyBlocked) {
                                forceKickExpiredUser(res.optString("message", "Ключ заблокирован или истёк!"));
                                return;
                            }

                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                            String dayUtc = sdf.format(new Date());

                            if (nativeVerifyServerResponseSignature(savedKey, hwid, raw, dayUtc)) {
                                TeleportEngine.setServerAuthorizedFromVerifiedHmac(true);
                                getOrInitKeyDeadlineMs(savedKey, res);
                            } else if (!isNativeServerAuthorized()) {
                                forceKickExpiredUser("Сессия истекла, войдите заново");
                            }
                        } catch (Exception e) {
                            if (!isNativeServerAuthorized()) {
                                forceKickExpiredUser("Потеряна связь с сервером лицензий!");
                            }
                        }
                    });
                }
                mainHandler.postDelayed(this, 25000);
            }
        }, 25000);
    }

    // =========================================================================
    // 4. УПРАВЛЕНИЕ ОВЕРЛЕЯМИ И НАТИВНЫЙ КИК (#2, #24)
    // =========================================================================
    private void removeOverlayViewSafe(View v) {
        if (v == null) return;
        try {
            v.clearAnimation();
            v.setVisibility(View.GONE);
        } catch (Throwable ignored) {}
        if (windowManager == null) return;
        try {
            windowManager.removeViewImmediate(v);
            return;
        } catch (Throwable ignored) {}
        try {
            windowManager.removeView(v);
        } catch (Throwable ignored) {}
    }

    private void detachTrashOverlaySafe() {
        if (trashContainer != null) {
            removeOverlayViewSafe(trashContainer);
        }
        isTrashAttached = false;
    }

    private void attachTrashOverlaySafe() {
        if (!isTrashAttached && trashContainer != null && trashParams != null && windowManager != null) {
            try {
                if (trashPillView != null) {
                    trashPillView.setHovered(false);
                }
                trashContainer.setVisibility(View.VISIBLE);
                trashContainer.setAlpha(0f);
                windowManager.addView(trashContainer, trashParams);
                trashContainer.animate().alpha(0.95f).setDuration(110).start();
                isTrashAttached = true;
            } catch (Exception ignored) {
                try {
                    trashContainer.setVisibility(View.VISIBLE);
                    trashContainer.setAlpha(0.95f);
                    windowManager.updateViewLayout(trashContainer, trashParams);
                    isTrashAttached = true;
                } catch (Exception ignored2) {}
            }
        }
    }

    private String sanitizeJsText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("['\"\\\\<>\\r\\n()]", "");
    }

    // Пункты #2, #24: Делегирование кика в C++ (nativeForceKick)
    private void forceKickExpiredUser(String reasonToast) {
        if (isNativeLoaded) {
            try {
                nativeForceKick(context, false);
            } catch (Throwable ignored) {}
        }
        saveEncryptedKeyToVault("");
        prefs.edit().putBoolean(PREF_FIRST_LOGIN_DONE, false).apply();
        legacyPrefs.edit().remove("license_key").apply();
        TeleportEngine.setServerAuthorizedFromVerifiedHmac(false);
        TeleportEngine.stopTeleport();

        mainHandler.post(() -> {
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) {
                removeOverlayViewSafe(floatingStretchBtn);
                floatingStretchBtn = null;
            }
            trashContainer = null;
            trashPillView = null;
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

            if (reasonToast != null && !reasonToast.isEmpty()) {
                Toast.makeText(context, "⛔ " + reasonToast, Toast.LENGTH_LONG).show();
            }

            if (activity instanceof MainActivity) {
                String safeMsg = sanitizeJsText(reasonToast == null ? "Введите лицензионный ключ" : reasonToast);
                ((MainActivity) activity).evalJsFromNotification(
                        "try { if(typeof forceShowKickScreen==='function') forceShowKickScreen('" + safeMsg + "'); } catch(e){}"
                );
            }
        });
    }

    // =========================================================================
    // 5. МОСТЫ ИНЖЕКТОРА КАСАНИЙ (GAME TOUCH LAYER), РАСТЯГА И ГЕРЦОВКИ
    // =========================================================================
    @JavascriptInterface
    public void configureTouchLayer(boolean enabled, int stickiness, int heaviness, int sharpness, double sprayPull, double aimScale) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty()) return;
        TeleportEngine.configureGameTouchLayer(enabled, stickiness, heaviness, sharpness, (float) sprayPull, (float) aimScale);
    }

    @JavascriptInterface
    public void setTouchInjectorActive(boolean enabled, int mode, int power) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty()) return;
        TeleportEngine.setTouchInjectorActive(enabled, mode, power);
    }

    @JavascriptInterface
    public void injectTouchTap(int x, int y) {
        if (!isNativeServerAuthorized()) return;
        TeleportEngine.injectTouchTap(x, y);
    }

    @JavascriptInterface
    public void injectRecoilSwipe(int startX, int startY, int dx, int dy, int durationMs) {
        if (!isNativeServerAuthorized()) return;
        TeleportEngine.injectRecoilSwipe(startX, startY, dx, dy, durationMs);
    }

    @JavascriptInterface
    public int getLiveScreenRefreshRate() {
        return TeleportEngine.getLiveRefreshRateHz(context);
    }

    @JavascriptInterface
    public int getMaxHardwareRefreshRate() {
        return TeleportEngine.getMaxHardwareRefreshRateHz();
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        TeleportEngine.detectTrueHardwareScreenMetrics(context);
        String model = Build.MODEL;
        int shortPx = TeleportEngine.getNativeShortSide();
        int longPx = TeleportEngine.getNativeLongSide();
        int liveHz = TeleportEngine.getLiveRefreshRateHz(context);
        int maxHz = TeleportEngine.getMaxHardwareRefreshRateHz();
        String hzInfo = (liveHz == maxHz) ? (liveHz + " Гц") : (liveHz + "/" + maxHz + " Гц");
        return model + "|" + shortPx + "x" + longPx + " (" + hzInfo + ")";
    }

    // Пункт #11: Вызов Shizuku строго через gateCommandThroughNative
    @JavascriptInterface
    public void enforceGameAudioMode() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(AudioManager.MODE_NORMAL);
                am.setSpeakerphoneOn(true);
                am.setMicrophoneMute(false);
            }
        } catch (Throwable ignored) {}

        if (isNativeServerAuthorized() && isShizukuReady()) {
            String pkg = context.getPackageName();
            execGatedShizuku(
                    "settings put global block_untrusted_touches 0; " +
                    "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0; " +
                    "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; " +
                    "cmd appops set " + pkg + " RECORD_AUDIO allow; " +
                    "cmd appops set " + pkg + " PLAY_AUDIO allow; " +
                    "cmd appops set " + pkg + " TAKE_AUDIO_FOCUS ignore; " +
                    "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; " +
                    "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; " +
                    "cmd deviceidle whitelist +" + pkg + "; " +
                    "cmd appops set com.axlebolt.standoff2 PLAY_AUDIO allow; " +
                    "cmd appops set com.axlebolt.standoff2 TAKE_AUDIO_FOCUS ignore;"
            );
        }
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
                    Toast.makeText(context, "Выдайте разрешение «Поверх других окон»!", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.getPackageName())
                    );
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(fallback);
                }
            } else {
                Toast.makeText(context, "✅ Разрешение «Поверх других окон» активно!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Пункт #12: Вызов Shizuku строго через execGatedShizuku
    @JavascriptInterface
    public String activateStretchFloatingButton(String ratio) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return "NEED_AUTH";
        if (ratio != null && !ratio.trim().isEmpty()) {
            this.selectedStretchRatio = ratio.trim().toUpperCase(Locale.US);
        }
        prefs.edit().putString("saved_stretch_ratio", this.selectedStretchRatio).apply();
        legacyPrefs.edit()
                .putString("stretch_ratio", this.selectedStretchRatio)
                .putBoolean("float_btn_enabled", true)
                .apply();

        TeleportEngine.startTeleport("SET_RATIO:" + this.selectedStretchRatio);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission();
            return "NEED_PERMISSION";
        }

        TeleportEngine.requestShizukuPermissionIfNeeded();
        execGatedShizuku(
                "settings put global block_untrusted_touches 0; " +
                "settings put secure block_untrusted_touches 0; " +
                "device_config put input_native_boot maximum_obscuring_opacity_for_touch 1.0;"
        );

        mainHandler.post(() -> {
            try {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).toggleFloatingButton(false);
                }

                detachTrashOverlaySafe();
                if (floatingStretchBtn != null) {
                    removeOverlayViewSafe(floatingStretchBtn);
                    floatingStretchBtn = null;
                }
                isStretchOverlayShown = false;

                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                trashContainer = new FrameLayout(context);
                trashPillView = new BridgeTrashView(context);
                FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(dpToPx(276), dpToPx(58), Gravity.CENTER);
                trashContainer.addView(trashPillView, pillLp);

                int trashFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) trashFlags |= WindowManager.LayoutParams.FLAG_SECURE;

                trashParams = new WindowManager.LayoutParams(
                        dpToPx(300), dpToPx(84), overlayType, trashFlags, PixelFormat.TRANSLUCENT
                );
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
                int btnFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) btnFlags |= WindowManager.LayoutParams.FLAG_SECURE;

                stretchParams = new WindowManager.LayoutParams(
                        sizePx, sizePx, overlayType, btnFlags, PixelFormat.TRANSLUCENT
                );
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
                                initialX = stretchParams.x;
                                initialY = stretchParams.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                isDragging = false;
                                wasInTrashZone = false;
                                v.setScaleX(0.92f);
                                v.setScaleY(0.92f);
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                int dx = Math.round(event.getRawX() - initialTouchX);
                                int dy = Math.round(event.getRawY() - initialTouchY);
                                if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                                    isDragging = true;
                                    attachTrashOverlaySafe();
                                }
                                if (isDragging && windowManager != null && floatingStretchBtn != null) {
                                    stretchParams.x = initialX + dx;
                                    stretchParams.y = initialY + dy;
                                    try {
                                        windowManager.updateViewLayout(floatingStretchBtn, stretchParams);
                                    } catch (Exception ignored) {}

                                    boolean inTrash = isTouchOverTrashZone(event.getRawX(), event.getRawY());
                                    if (trashPillView != null) {
                                        trashPillView.setHovered(inTrash);
                                    }
                                    floatingStretchBtn.setAlpha(inTrash ? 0.62f : 1.0f);
                                    if (inTrash && !wasInTrashZone) {
                                        try {
                                            floatingStretchBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                        } catch (Throwable ignored) {}
                                    }
                                    wasInTrashZone = inTrash;
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                v.setScaleX(1.0f);
                                v.setScaleY(1.0f);
                                v.setAlpha(1.0f);

                                if (isDragging) {
                                    boolean droppedInTrash = wasInTrashZone || isTouchOverTrashZone(event.getRawX(), event.getRawY());
                                    if (trashPillView != null) {
                                        trashPillView.setHovered(false);
                                    }
                                    detachTrashOverlaySafe();
                                    isDragging = false;
                                    wasInTrashZone = false;

                                    if (droppedInTrash) {
                                        removeFloatingTpButtonOnly();
                                    }
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
                Toast.makeText(context, "⚡ Кнопка «T» (" + selectedStretchRatio + ") запущена!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "Ошибка кнопки T: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        return "OK";
    }

    // Пункт #16: Консервативный возврат true в catchall
    private boolean isTouchOverTrashZone(float rawX, float rawY) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                windowManager.getDefaultDisplay().getRealMetrics(dm);
            } else {
                dm = context.getResources().getDisplayMetrics();
            }

            int sw = dm.widthPixels;
            int sh = dm.heightPixels;
            int btnSize = dpToPx(54);

            if (trashPillView != null && isTrashAttached && trashPillView.getWidth() > 0) {
                int[] tLoc = new int[2];
                trashPillView.getLocationOnScreen(tLoc);
                int padX = dpToPx(55);
                int padY = dpToPx(55);

                if (rawX >= tLoc[0] - padX && rawX <= tLoc[0] + trashPillView.getWidth() + padX
                        && rawY >= tLoc[1] - padY && rawY <= tLoc[1] + trashPillView.getHeight() + padY) {
                    return true;
                }
            }

            boolean yNearBottom = rawY >= (sh - dpToPx(140))
                    || (stretchParams != null && (stretchParams.y + btnSize) >= (sh - dpToPx(140)))
                    || (stretchParams != null && stretchParams.y >= (sh * 0.68f));

            boolean xNearCenter = Math.abs(rawX - (sw / 2f)) <= (sw * 0.38f)
                    || (stretchParams != null && Math.abs((stretchParams.x + btnSize / 2f) - (sw / 2f)) <= (sw * 0.38f));

            return yNearBottom && xNearCenter;
        } catch (Throwable t) {
            return true;
        }
    }

    private void toggleTwoStepStretchAction() {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty()) {
            forceKickExpiredUser("Ключ не активен!");
            return;
        }
        if (!isShizukuReady()) {
            Toast.makeText(context, "⚠️ Включите Shizuku! Помощь: @talent_dare", Toast.LENGTH_LONG).show();
            TeleportEngine.requestShizukuPermissionIfNeeded();
            return;
        }

        if (!isBoxBarsActive) {
            boolean ok = TeleportEngine.applyAspectRatioStretch(selectedStretchRatio);
            if (ok) {
                isBoxBarsActive = true;
                Toast.makeText(context, "⬛ Шаг 1 (" + selectedStretchRatio + ") включен! Зайди в игру и нажми «T» снова", Toast.LENGTH_SHORT).show();
            }
        } else {
            TeleportEngine.releaseBoxToFullStretch();
            isBoxBarsActive = false;
            Toast.makeText(context, "🔥 Шаг 2: Растяг применён! Перетяни кнопку «T» вниз в ведро", Toast.LENGTH_SHORT).show();
        }
        updateFloatingBtnAppearance();
    }

    private void removeFloatingTpButtonOnly() {
        mainHandler.post(() -> {
            if (isBoxBarsActive) {
                TeleportEngine.releaseBoxToFullStretch();
                isBoxBarsActive = false;
            }
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) {
                removeOverlayViewSafe(floatingStretchBtn);
                floatingStretchBtn = null;
            }
            trashContainer = null;
            trashPillView = null;
            isStretchOverlayShown = false;
            legacyPrefs.edit().putBoolean("float_btn_enabled", false).apply();
            Toast.makeText(context, "🗑️ Кнопка «T» убрана с экрана, приятной катки!", Toast.LENGTH_SHORT).show();
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
            glassLens.setColors(new int[]{
                    Color.parseColor("#CC34D399"),
                    Color.parseColor("#8810B981"),
                    Color.parseColor("#AA064E3B")
            });
            glassLens.setStroke(dpToPx(2), Color.parseColor("#EEFFFFFF"));
            floatingStretchBtn.setText("2\n" + shortLabel);
        } else {
            glassLens.setColors(new int[]{
                    Color.parseColor("#B3475569"),
                    Color.parseColor("#801E293B"),
                    Color.parseColor("#990F172A")
            });
            glassLens.setStroke(dpToPx(2), Color.parseColor("#EE38BDF8"));
            floatingStretchBtn.setText("T\n" + shortLabel);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            floatingStretchBtn.setElevation(dpToPx(10));
        }
        floatingStretchBtn.setShadowLayer(8f, 0f, 2f, Color.parseColor("#CC000000"));
        floatingStretchBtn.setBackground(glassLens);
    }

    @JavascriptInterface
    public void applyStretchDirectlyNow(String ratio) {
        activateStretchFloatingButton(ratio);
    }

    @JavascriptInterface
    public void applyDirectStretchRatio(String ratio) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.applyAspectRatioStretch(ratio);
    }

    @JavascriptInterface
    public void resetResolution() {
        legacyPrefs.edit().putBoolean("float_btn_enabled", false).apply();
        mainHandler.post(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).toggleFloatingButton(false);
            }
            TeleportEngine.resetResolutionToNative();
            isBoxBarsActive = false;
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) {
                removeOverlayViewSafe(floatingStretchBtn);
                floatingStretchBtn = null;
            }
            trashContainer = null;
            trashPillView = null;
            isStretchOverlayShown = false;
            Toast.makeText(context, "🔄 Родной экран и пропорции восстановлены", Toast.LENGTH_SHORT).show();
        });
    }

    @JavascriptInterface
    public boolean saveFullConfigJson(String jsonConfig) {
        if (jsonConfig == null || jsonConfig.trim().isEmpty()) return false;
        prefs.edit().putString("full_user_config_v410", jsonConfig).apply();
        return true;
    }

    @JavascriptInterface
    public String loadFullConfigJson() {
        return prefs.getString("full_user_config_v410", "");
    }

    @JavascriptInterface
    public String setWideHitboxMode(boolean enabled, String ratio) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return "NEED_AUTH";
        String targetRatio = (ratio != null && !ratio.isEmpty()) ? ratio : "4:3";
        prefs.edit().putBoolean("wide_hitbox_mode", enabled).apply();
        if (enabled) {
            TeleportEngine.setTouchSamplingBoost(true, 240);
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

    // Пункт #17: Убрано дублирование логики между isShizukuReady и isShizukuActive
    @JavascriptInterface
    public boolean isShizukuReady() {
        return TeleportEngine.isShizukuReady();
    }

    @JavascriptInterface
    public boolean isShizukuActive() {
        return isShizukuReady();
    }

    @JavascriptInterface
    public void requestShizukuPermission() {
        TeleportEngine.requestShizukuPermissionIfNeeded();
    }

    @JavascriptInterface
    public void healTouch() {
        if (!isNativeServerAuthorized()) return;
        TeleportEngine.requestShizukuPermissionIfNeeded();
        TeleportEngine.healTouchAndRestart();
    }

    @JavascriptInterface
    public void startTeleport(String presetId) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.startTeleport(presetId);
    }

    @JavascriptInterface
    public void stopTeleport() {
        TeleportEngine.stopTeleport();
    }

    @JavascriptInterface
    public void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.updateParameters(sharp, sx, sy, chunks, chunkFast, straight, stick, grid, pause, accel);
    }

    @JavascriptInterface
    public void setRawInputNoSmoothing(boolean enabled) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.setRawInputNoSmoothing(enabled);
    }

    @JavascriptInterface
    public void setVulkanGpuTurbo(boolean enabled) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.setVulkanGpuTurbo(enabled);
    }

    @JavascriptInterface
    public void setNetPingStabilizer(boolean enabled) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.setNetPingStabilizer(enabled);
    }

    @JavascriptInterface
    public void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.setTouchSamplingBoost(enabled, hz);
    }

    @JavascriptInterface
    public String performRamBoost() {
        if (!isNativeServerAuthorized()) return "NEED_AUTH";
        return TeleportEngine.performRamBoost(context);
    }

    @JavascriptInterface
    public void setHzThermalGuard(boolean enabled) {
        if (!isNativeServerAuthorized()) return;
        TeleportEngine.setHzThermalGuard(enabled);
    }

    @JavascriptInterface
    public void applyShooterOptimizer(
            int fpsLock, int gpuBoost, int frameTime,
            int edgeSens, int multiTouch, int netBuffer, int thermalLim
    ) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.applyShooterOptimizer(fpsLock, gpuBoost, frameTime, edgeSens, multiTouch, netBuffer, thermalLim);
    }

    @JavascriptInterface
    public void setEnhancedRecoilFix(boolean enabled, int power) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.setEnhancedRecoilFix(enabled, power);
    }

    @JavascriptInterface
    public void setAutoGameDpi(boolean enabled, int dpi) {}

    @JavascriptInterface
    public void setPixelDownscaleFactor(int pct) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        TeleportEngine.setPixelDownscaleFactor(pct);
    }

    @JavascriptInterface
    public String runSmartDeviceScan() {
        return TeleportEngine.runSmartDeviceScan(context);
    }

    @JavascriptInterface
    public void applyUltraProTouchMatrix(int modeLevel) {
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) return;
        ProfileManager.addTeleportCountToTotal(context, 1);
        TeleportEngine.startTeleport("extreme");
    }

    @JavascriptInterface
    public String uploadCloudConfig(String title, String presetJson) {
        String key = getSavedKey();
        if (!isNativeServerAuthorized() || key.isEmpty() || nativeIsClientTampered(context)) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        }
        try {
            Callable<String> task = () -> ApiManager.uploadCloudPreset(key, getDeviceHwid(), title, presetJson);
            Future<String> future = networkExecutor.submit(task);
            return future.get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка создания кода\"}";
        }
    }

    @JavascriptInterface
    public String downloadCloudConfig(String shareCode) {
        String key = getSavedKey();
        if (!isNativeServerAuthorized() || key.isEmpty() || nativeIsClientTampered(context)) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        }
        try {
            Callable<String> task = () -> ApiManager.downloadCloudPresetByCode(key, getDeviceHwid(), shareCode);
            Future<String> future = networkExecutor.submit(task);
            return future.get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка загрузки конфига\"}";
        }
    }

    // =========================================================================
    // 6. ПРИЦЕЛ ПОВЕРХ ИГРЫ (140dp ОКНО БЕЗ ГОНКИ ПОТОКОВ)
    // =========================================================================
    private void ensureCrosshairOverlayAttached(String cleanType) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateCrosshair("off");
        }
        if ("off".equalsIgnoreCase(cleanType) || "none".equalsIgnoreCase(cleanType) || cleanType.isEmpty()) {
            if (crosshairView != null) {
                crosshairView.setCrosshairType("off");
                removeOverlayViewSafe(crosshairView);
            }
            crosshairView = null;
            isCrosshairShown = false;
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission();
            return;
        }

        if (!isCrosshairShown || crosshairView == null) {
            crosshairView = new CrosshairOverlayView(context);
            int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (stealthOverlayFlag) flags |= WindowManager.LayoutParams.FLAG_SECURE;

            int boxSize = dpToPx(140);
            crosshairParams = new WindowManager.LayoutParams(
                    boxSize, boxSize, overlayType, flags, PixelFormat.TRANSLUCENT
            );
            crosshairParams.gravity = Gravity.CENTER;
            crosshairParams.x = dpToPx(savedCrosshairOffsetX);
            crosshairParams.y = dpToPx(savedCrosshairOffsetY);
            crosshairParams.alpha = 1.0f;
            try {
                windowManager.addView(crosshairView, crosshairParams);
                isCrosshairShown = true;
            } catch (Exception ignored) {}
        }
        if (crosshairView != null) {
            crosshairView.setVisibility(View.VISIBLE);
            crosshairView.setCrosshairType(cleanType);
        }
    }

    @JavascriptInterface
    public void updateCrosshair(String type) {
        final String cleanType = (type == null) ? "off" : type.trim();
        if ((!isNativeServerAuthorized() || getSavedKey().isEmpty()) && !"off".equalsIgnoreCase(cleanType)) return;
        mainHandler.post(() -> ensureCrosshairOverlayAttached(cleanType));
    }

    @JavascriptInterface
    public void setCrosshairOffset(int offsetXDp, int offsetYDp) {
        this.savedCrosshairOffsetX = offsetXDp;
        this.savedCrosshairOffsetY = offsetYDp;
        mainHandler.post(() -> {
            if (isCrosshairShown && crosshairView != null && crosshairParams != null && windowManager != null) {
                crosshairParams.x = dpToPx(offsetXDp);
                crosshairParams.y = dpToPx(offsetYDp);
                try { windowManager.updateViewLayout(crosshairView, crosshairParams); } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void updateCrosshairStyle(int scalePct, int opacityPct, String hexColor) {
        mainHandler.post(() -> {
            if (crosshairView != null) {
                crosshairView.setStyle(scalePct, opacityPct, hexColor);
            }
        });
    }

    @JavascriptInterface
    public void updateCrosshairAdvanced(
            String type, String hexColor, int opacityPct,
            int lengthDp, int thicknessDp, int gapDp,
            int dotSizeDp, boolean showOutline
    ) {
        final String cleanType = (type == null) ? "off" : type.trim();
        if ("off".equalsIgnoreCase(cleanType) || cleanType.isEmpty() || opacityPct <= 0) {
            updateCrosshair("off");
            return;
        }
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty()) return;

        mainHandler.post(() -> {
            ensureCrosshairOverlayAttached(cleanType);
            if (crosshairView != null && isCrosshairShown) {
                crosshairView.setVisibility(View.VISIBLE);
                crosshairView.setAdvancedConfig(cleanType, hexColor, opacityPct, lengthDp, thicknessDp, gapDp, dotSizeDp, showOutline);
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
    public void setStealthOverlayMode(boolean stealth) {
        updateOverlayStealthFlag(stealth);
    }

    // =========================================================================
    // 7. НАТИВНАЯ АКТИВАЦИЯ И ПРОВЕРКА КЛЮЧА (#1, #6, #20, #23, #25)
    // =========================================================================
    @JavascriptInterface
    public String getSavedKey() {
        if (!isNativeLoaded || ApiManager.isHoneypotTriggered() || !prefs.getBoolean(PREF_FIRST_LOGIN_DONE, false)) {
            return "";
        }
        String vaultKey = readEncryptedKeyFromVault();
        if (vaultKey.isEmpty() || nativeIsBlacklisted(vaultKey)) return "";

        try {
            String natKey = nativeGetSessionKey();
            if (natKey != null && !natKey.isEmpty() && !natKey.equalsIgnoreCase(vaultKey)) {
                return "";
            }
        } catch (Throwable ignored) {}
        return vaultKey;
    }

    @JavascriptInterface
    public String getSavedNickname() {
        String nick = prefs.getString("saved_player_nick", "");
        if (nick == null || nick.trim().isEmpty()) {
            nick = legacyPrefs.getString("nickname", "");
        }
        return nick == null ? "" : nick.trim();
    }

    @JavascriptInterface
    public void savePlayerNickname(String nick) {
        if (nick != null && !nick.trim().isEmpty()) {
            String safeNick = sanitizeJsText(nick.trim());
            prefs.edit().putString("saved_player_nick", safeNick).apply();
            legacyPrefs.edit().putString("nickname", safeNick).apply();
        }
    }

    private void markKeyActivated(String nick, String cleanKey, JSONObject serverRes) {
        saveEncryptedKeyToVault(cleanKey);
        getOrInitKeyDeadlineMs(cleanKey, serverRes);
        prefs.edit().putBoolean(PREF_FIRST_LOGIN_DONE, true).apply();
        ProfileManager.saveActivatedProfile(context, nick, cleanKey);
    }

    // Пункты #1, #23, #25: Активация выполняется через нативный C++ метод nativeActivateLicense
    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (!isNativeLoaded || nativeIsClientTampered(context)) {
            executeNativeKillOnTamper();
            return "ERROR|Обнаружена модификация приложения!";
        }
        if (key == null || key.trim().length() < 5 || nativeIsBlacklisted(key)) {
            return "ERROR|Введите лицензионный ключ из Telegram-бота";
        }
        final String cleanKey = key.trim().toUpperCase(Locale.US);
        final String nick = getSavedNickname().isEmpty() ? "dare" : getSavedNickname();

        try {
            Callable<String> task = () -> nativeActivateLicense(context, cleanKey, nick);
            Future<String> future = networkExecutor.submit(task);
            String natResult = future.get(6000, TimeUnit.MILLISECONDS);

            if (natResult != null && natResult.startsWith("OK|")) {
                String rawJson = natResult.substring(3);
                JSONObject res = new JSONObject(rawJson);

                // Дополнительно сверяем, что C++ действительно записал ключ в нативную сессию (#25)
                String activeSessionKey = nativeGetSessionKey();
                if (activeSessionKey == null || !activeSessionKey.equalsIgnoreCase(cleanKey)) {
                    forceKickExpiredUser("Ошибка инициализации нативной сессии");
                    return "ERROR|Ошибка инициализации нативной сессии";
                }

                TeleportEngine.setServerAuthorizedFromVerifiedHmac(true);
                markKeyActivated(nick, cleanKey, res);
                SecurityShield.issueVerifiedSessionToken(context, cleanKey);
                String liveLabel = buildLiveSubscriptionLabel(res, cleanKey);
                if ("EXPIRED".equals(liveLabel)) {
                    forceKickExpiredUser("Срок действия этого ключа истёк!");
                    return "ERROR|Срок действия этого ключа истёк!";
                }
                return "OK|" + liveLabel;
            }

            String errMsg = (natResult != null && natResult.contains("|"))
                    ? natResult.substring(natResult.indexOf('|') + 1)
                    : "Ключ не найден или привязан к другому устройству!";
            return "ERROR|" + errMsg;
        } catch (Exception e) {
            return "ERROR|Нет связи с сервером авторизации. Проверьте интернет!";
        }
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        if (!isNativeLoaded || nativeIsClientTampered(context)) {
            executeNativeKillOnTamper();
            return "BANNED|Модификация файлов приложения";
        }
        if (!prefs.getBoolean(PREF_FIRST_LOGIN_DONE, false)) {
            return "EXPIRED|Введите ваш логин и лицензионный ключ";
        }

        final String cleanKey = (key != null && !key.trim().isEmpty())
                ? key.trim().toUpperCase(Locale.US)
                : readEncryptedKeyFromVault();

        if (cleanKey.length() < 5 || nativeIsBlacklisted(cleanKey)) {
            forceKickExpiredUser("Введите лицензионный ключ");
            return "EXPIRED|Введите ключ";
        }

        String localLiveLabel = buildLiveSubscriptionLabel(null, cleanKey);
        if ("EXPIRED".equals(localLiveLabel)) {
            forceKickExpiredUser("Время вашей подписки истекло!");
            return "EXPIRED|Время вашей подписки истекло!";
        }

        try {
            final String hwid = getDeviceHwid();
            Callable<String> task = () -> ApiManager.verifyLicense(cleanKey, hwid);
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(4500, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            boolean blockedOrExpired = "expired".equalsIgnoreCase(status)
                    || "blocked".equalsIgnoreCase(status)
                    || "banned".equalsIgnoreCase(status)
                    || "device_mismatch".equalsIgnoreCase(status)
                    || "offline".equalsIgnoreCase(status)
                    || res.optBoolean("blocked", false);

            if (blockedOrExpired) {
                String msg = res.optString("message", "Ключ заблокирован или истёк!");
                forceKickExpiredUser(msg);
                return "BLOCKED|" + msg;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String dayUtc = sdf.format(new Date());

            if (nativeVerifyServerResponseSignature(cleanKey, hwid, raw, dayUtc)) {
                TeleportEngine.setServerAuthorizedFromVerifiedHmac(true);
                String label = buildLiveSubscriptionLabel(res, cleanKey);
                if ("EXPIRED".equals(label)) {
                    forceKickExpiredUser("Срок действия ключа истёк!");
                    return "EXPIRED|Срок действия ключа истёк!";
                }
                return "OK|" + label;
            }

            String msg = res.optString("message", "Сессия недействительна, войдите снова!");
            forceKickExpiredUser(msg);
            return "EXPIRED|" + msg;
        } catch (Exception e) {
            if (!isNativeServerAuthorized()) {
                forceKickExpiredUser("Нет связи с сервером проверки лицензии!");
                return "EXPIRED|Проверьте подключение к интернету";
            }
            return "OK|" + localLiveLabel;
        }
    }

    @JavascriptInterface
    public void logoutLicenseKey() {
        forceKickExpiredUser("Смена логина или лицензионного ключа");
    }

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
        if (!isNativeServerAuthorized() || getSavedKey().isEmpty() || nativeIsClientTampered(context)) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        }
        try {
            Callable<String> task = () -> ApiManager.callFriendsApi(jsonPayload);
            Future<String> future = networkExecutor.submit(task);
            return future.get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка сети\"}";
        }
    }

    // =========================================================================
    // 8. ГОЛОСОВОЙ ЧАТ С ЗАЩИЩЁННЫМ WHITELIST (#13, #18)
    // =========================================================================
    private static final String VOICE_CHANNEL_ID = "so2tp_voice_call_channel_v2";
    private static final int VOICE_NOTIF_ID = 7744;
    private static final String ACTION_VOICE_MUTE = "com.teleport.so2.ACTION_VOICE_MUTE";
    private static final String ACTION_VOICE_DEAFEN = "com.teleport.so2.ACTION_VOICE_DEAFEN";
    private static final String ACTION_VOICE_HANGUP = "com.teleport.so2.ACTION_VOICE_HANGUP";

    private boolean isReceiverRegistered = false;

    private final BroadcastReceiver voiceNotifReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String act = intent.getAction();
            if (ACTION_VOICE_MUTE.equals(act)) {
                evalVoiceActionInWebView(1);
            } else if (ACTION_VOICE_DEAFEN.equals(act)) {
                evalVoiceActionInWebView(2);
            } else if (ACTION_VOICE_HANGUP.equals(act)) {
                evalVoiceActionInWebView(3);
            }
        }
    };

    // Пункт #18: Полный запрет произвольных JS-строк (работает только по целочисленному коду 1..3)
    private void evalVoiceActionInWebView(final int actionCode) {
        mainHandler.post(() -> {
            try {
                if (!(activity instanceof MainActivity)) return;
                final String fixedCmd;
                if (actionCode == 1) {
                    fixedCmd = "if(window.toggleMicMute) window.toggleMicMute();";
                } else if (actionCode == 2) {
                    fixedCmd = "if(window.toggleDeafenSpeaker) window.toggleDeafenSpeaker();";
                } else if (actionCode == 3) {
                    fixedCmd = "if(window.leaveVoiceRoom) window.leaveVoiceRoom();";
                } else {
                    return;
                }
                ((MainActivity) activity).evalJsFromNotification(fixedCmd);
            } catch (Exception ignored) {}
        });
    }

    // Пункт #13: Вызов Shizuku строго через execGatedShizuku
    private void ensureVoiceNotifSetup() {
        if (isNativeServerAuthorized() && isShizukuReady()) {
            String pkg = context.getPackageName();
            execGatedShizuku(
                    "cmd appops set " + pkg + " POST_NOTIFICATION allow; " +
                    "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS; " +
                    "pm grant " + pkg + " android.permission.RECORD_AUDIO"
            );
        }

        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_VOICE_MUTE);
            filter.addAction(ACTION_VOICE_DEAFEN);
            filter.addAction(ACTION_VOICE_HANGUP);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(voiceNotifReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(voiceNotifReceiver, filter);
            }
            isReceiverRegistered = true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(VOICE_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        VOICE_CHANNEL_ID,
                        "SO2Teleport Squad Voice",
                        NotificationManager.IMPORTANCE_LOW
                );
                ch.setDescription("Пульт управления микрофоном и звуком в шторке");
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @JavascriptInterface
    public void updateVoiceCallNotification(String roomName, boolean isMuted, boolean isDeafened) {
        if (!isNativeServerAuthorized()) return;
        final String cleanRoom = sanitizeJsText(roomName == null || roomName.isEmpty() ? "SQUAD-MAIN" : roomName);
        mainHandler.post(() -> {
            try {
                ensureVoiceNotifSetup();
                enforceGameAudioMode();
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;

                if (Build.VERSION.SDK_INT >= 33 && activity != null) {
                    if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2005);
                    }
                }

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                Intent openIntent = new Intent(context, MainActivity.class);
                openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent contentPi = PendingIntent.getActivity(context, 10, openIntent, flags);

                PendingIntent mutePi = PendingIntent.getBroadcast(
                        context, 11, new Intent(ACTION_VOICE_MUTE).setPackage(context.getPackageName()), flags);
                PendingIntent deafPi = PendingIntent.getBroadcast(
                        context, 12, new Intent(ACTION_VOICE_DEAFEN).setPackage(context.getPackageName()), flags);
                PendingIntent hangPi = PendingIntent.getBroadcast(
                        context, 13, new Intent(ACTION_VOICE_HANGUP).setPackage(context.getPackageName()), flags);

                String muteLabel = isMuted ? "🎤 ВКЛ МИКРО" : "🔇 ВЫКЛ МИКРО";
                String deafLabel = isDeafened ? "🎧 ВКЛ ЗВУК" : "🔈 ВЫКЛ ЗВУК";
                String statusSub = (isMuted ? "🔇 Микро ВЫКЛ" : "🎤 Микро ВКЛ") + "  •  " + (isDeafened ? "🔈 Звук ВЫКЛ" : "🎧 Звук 400% (Squad Isolator)");

                Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? new Notification.Builder(context, VOICE_CHANNEL_ID)
                        : new Notification.Builder(context);

                int smallIco = isMuted ? android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_btn_speak_now;

                builder.setSmallIcon(smallIco)
                        .setContentTitle("🟢 Войс Отряда • " + cleanRoom)
                        .setContentText(statusSub)
                        .setContentIntent(contentPi)
                        .setOngoing(true)
                        .setAutoCancel(false)
                        .setOnlyAlertOnce(true)
                        .addAction(new Notification.Action.Builder(android.R.drawable.ic_btn_speak_now, muteLabel, mutePi).build())
                        .addAction(new Notification.Action.Builder(android.R.drawable.ic_lock_silent_mode_off, deafLabel, deafPi).build())
                        .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "ВЫЙТИ", hangPi).build());

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    builder.setColor(Color.parseColor("#10B981"));
                    builder.setCategory(Notification.CATEGORY_CALL);
                    builder.setVisibility(Notification.VISIBILITY_PUBLIC);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
                }

                nm.notify(VOICE_NOTIF_ID, builder.build());
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void cancelVoiceCallNotification() {
        mainHandler.post(() -> {
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(VOICE_NOTIF_ID);
            } catch (Exception ignored) {}
        });
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static class BridgeTrashView extends View {
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint specularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outerRect = new RectF();
        private final RectF rect = new RectF();
        private final RectF highlightRect = new RectF();
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
            textPaint.setShadowLayer(6f * density, 0f, 1.5f * density, Color.parseColor("#AA000000"));
        }

        public void setHovered(boolean h) {
            if (this.hovered != h) {
                this.hovered = h;
                animate()
                        .scaleX(h ? 1.15f : 1.0f)
                        .scaleY(h ? 1.15f : 1.0f)
                        .setDuration(120)
                        .start();
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
                glowPaint.setColor(Color.parseColor("#77FF1744"));
                canvas.drawRoundRect(outerRect, 999f, 999f, glowPaint);

                bgPaint.setShader(new LinearGradient(
                        0, rect.top, 0, rect.bottom,
                        new int[]{
                                Color.parseColor("#D9FF5277"),
                                Color.parseColor("#99D50032"),
                                Color.parseColor("#CC4A0418")
                        },
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP
                ));
                borderPaint.setStrokeWidth(2.6f * density);
                borderPaint.setColor(Color.WHITE);
                textPaint.setColor(Color.WHITE);
            } else {
                bgPaint.setShader(new LinearGradient(
                        0, rect.top, 0, rect.bottom,
                        new int[]{
                                Color.parseColor("#AA334155"),
                                Color.parseColor("#660F172A"),
                                Color.parseColor("#99090D16")
                        },
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP
                ));
                borderPaint.setStrokeWidth(1.8f * density);
                borderPaint.setColor(Color.parseColor("#CCFF2E5B"));
                textPaint.setColor(Color.parseColor("#FFE4E6"));
            }

            canvas.drawRoundRect(rect, 999f, 999f, bgPaint);

            highlightRect.set(
                    rect.left + 14f * density,
                    rect.top + 2.5f * density,
                    rect.right - 14f * density,
                    rect.top + (rect.height() * 0.42f)
            );
            specularPaint.setShader(new LinearGradient(
                    0, highlightRect.top, 0, highlightRect.bottom,
                    Color.parseColor("#66FFFFFF"),
                    Color.parseColor("#00FFFFFF"),
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRoundRect(highlightRect, 999f, 999f, specularPaint);
            canvas.drawRoundRect(rect, 999f, 999f, borderPaint);

            String label = hovered ? "🔥 ОТПУСТИТЕ, ЧТОБЫ УБРАТЬ «T»" : "🗑️ ПЕРЕТАЩИТЕ СЮДА ДЛЯ УДАЛЕНИЯ";
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
        private int color = Color.parseColor("#00E5FF");

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CrosshairOverlayView(Context context) {
            super(context);
            outlinePaint.setColor(Color.BLACK);
            paint.setStrokeCap(Paint.Cap.ROUND);
            outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        }

        public void setCrosshairType(String t) {
            this.type = t;
            invalidate();
        }

        public void setStyle(int scalePct, int opacityPct, String hex) {
            this.lengthDp = Math.max(3, Math.round(3 + (scalePct / 100f) * 18f));
            this.opacity = opacityPct;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        public void setAdvancedConfig(
                String t, String hex, int op,
                int len, int thick, int gap, int dot, boolean out
        ) {
            this.type = t;
            this.opacity = op;
            this.lengthDp = len;
            this.thicknessDp = thick;
            this.gapDp = gap;
            this.dotSizeDp = dot;
            this.outline = out;
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
            paint.setColor(color);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(thickPx);

            outlinePaint.setAlpha(Math.min(220, alpha));
            outlinePaint.setStrokeWidth(thickPx + 2.0f * d);

            if ("dot".equalsIgnoreCase(type)) {
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, dotRadPx + 1.1f * d, outlinePaint);
                }
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dotRadPx, paint);
                return;
            }

            if ("circle".equalsIgnoreCase(type) || "circledot".equalsIgnoreCase(type)) {
                float rad = Math.max(3.5f * d, lenPx * 0.75f);
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(cx, cy, rad, outlinePaint);
                }
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, rad, paint);
                if ("circledot".equalsIgnoreCase(type)) {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, dotRadPx, paint);
                }
                return;
            }

            if ("box".equalsIgnoreCase(type)) {
                float half = Math.max(2.5f * d, lenPx * 0.45f);
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(cx - half, cy - half, cx + half, cy + half, outlinePaint);
                }
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
                return;
            }

            if ("chevron".equalsIgnoreCase(type)) {
                float arm = Math.max(3.5f * d, lenPx * 0.65f);
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(cx, cy - gapPx * 0.4f, cx - arm, cy + arm, outlinePaint);
                    canvas.drawLine(cx, cy - gapPx * 0.4f, cx + arm, cy + arm, outlinePaint);
                }
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx, cy - gapPx * 0.4f, cx - arm, cy + arm, paint);
                canvas.drawLine(cx, cy - gapPx * 0.4f, cx + arm, cy + arm, paint);
                return;
            }

            if ("corners".equalsIgnoreCase(type)) {
                float dist = Math.max(4f * d, (gapPx + lenPx) * 0.55f);
                float leg = Math.max(2.5f * d, lenPx * 0.38f);
                Paint[] layers = outline ? new Paint[]{outlinePaint, paint} : new Paint[]{paint};
                for (Paint p : layers) {
                    p.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(cx - dist, cy - dist, cx - dist + leg, cy - dist, p);
                    canvas.drawLine(cx - dist, cy - dist, cx - dist, cy - dist + leg, p);
                    canvas.drawLine(cx + dist, cy - dist, cx + dist - leg, cy - dist, p);
                    canvas.drawLine(cx + dist, cy - dist, cx + dist, cy - dist + leg, p);
                    canvas.drawLine(cx - dist, cy + dist, cx - dist + leg, cy + dist, p);
                    canvas.drawLine(cx - dist, cy + dist, cx - dist, cy + dist - leg, p);
                    canvas.drawLine(cx + dist, cy + dist, cx + dist - leg, cy + dist, p);
                    canvas.drawLine(cx + dist, cy + dist, cx + dist, cy + dist - leg, p);
                }
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dotRadPx * 0.85f, paint);
                return;
            }

            boolean hasTop = !"tshape".equalsIgnoreCase(type);
            if (outline) {
                outlinePaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, outlinePaint);
                canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, outlinePaint);
                canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, outlinePaint);
                if (hasTop) {
                    canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, outlinePaint);
                }
            }

            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, paint);
            canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, paint);
            canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, paint);
            if (hasTop) {
                canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, paint);
            }

            if ("crossdot".equalsIgnoreCase(type)) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dotRadPx, paint);
            }
        }
    }
}
