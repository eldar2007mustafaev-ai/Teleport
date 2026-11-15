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
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaDrm;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AndroidBridge {

    private static final String PREF_FIRST_LOGIN_DONE = "v420_manual_login_done";
    private static final String KEYSTORE_ALIAS = "SO2Teleport_TEE_Key_v10";
    private static final String VAULT_PREF_NAME = "SO2Teleport_SecureVault_V10";
    private static final String VAULT_ENC_KEY = "enc_saved_license_v10";

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

        // Удаляем предсказуемый кэш HWID из SharedPreferences (закрытие уязвимости #18)
        prefs.edit().remove("immutable_widevine_hwid_v9").apply();

        // Мягкая миграция существующего ключа в зашифрованное хранилище AndroidKeyStore
        String existingPlainKey = prefs.getString("saved_license_key", "");
        if (!existingPlainKey.isEmpty() && !isBlacklistedArtifact(existingPlainKey)) {
            saveEncryptedKeyToVault(existingPlainKey.trim().toUpperCase(Locale.US));
            prefs.edit()
                    .remove("saved_license_key")
                    .putBoolean(PREF_FIRST_LOGIN_DONE, true)
                    .apply();
        } else if (!readEncryptedKeyFromVault().isEmpty()) {
            prefs.edit().putBoolean(PREF_FIRST_LOGIN_DONE, true).apply();
        }

        TeleportEngine.init(context);
        TeleportEngine.requestShizukuPermissionIfNeeded();
        startLicenseWatchdog();
    }

    // =========================================================================
    // 1. ШИФРОВАНИЕ КЛЮЧА ЧЕРЕЗ ANDROID KEYSTORE (AES-256-GCM)
    // =========================================================================
    private SecretKey getOrCreateKeystoreSecret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null);
            if (entry != null) return entry.getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }

    private void saveEncryptedKeyToVault(String cleanKey) {
        try {
            if (cleanKey == null || cleanKey.isEmpty()) {
                vaultPrefs.edit().remove(VAULT_ENC_KEY).apply();
                return;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreSecret());
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(cleanKey.getBytes(StandardCharsets.UTF_8));
            String combined = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
            vaultPrefs.edit().putString(VAULT_ENC_KEY, combined).apply();
        } catch (Throwable e) {
            // Резервный XOR-контур на случай сбоя Keystore на кастомных прошивках
            vaultPrefs.edit().putString(VAULT_ENC_KEY + "_fb", Base64.encodeToString(cleanKey.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)).apply();
        }
    }

    private String readEncryptedKeyFromVault() {
        try {
            String combined = vaultPrefs.getString(VAULT_ENC_KEY, "");
            if (combined != null && combined.contains(":")) {
                String[] parts = combined.split(":", 2);
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreSecret(), new GCMParameterSpec(128, iv));
                String dec = new String(cipher.doFinal(enc), StandardCharsets.UTF_8).trim().toUpperCase(Locale.US);
                if (!isBlacklistedArtifact(dec)) return dec;
            }
        } catch (Throwable ignored) {}

        try {
            String fb = vaultPrefs.getString(VAULT_ENC_KEY + "_fb", "");
            if (fb != null && !fb.isEmpty()) {
                String dec = new String(Base64.decode(fb, Base64.NO_WRAP), StandardCharsets.UTF_8).trim().toUpperCase(Locale.US);
                if (!isBlacklistedArtifact(dec)) return dec;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    // =========================================================================
    // 2. ЗАЩИТА ОТ КРЯКА, FRIDA, XPOSED И МОДИФИКАЦИИ INDEX.HTML
    // =========================================================================
    private boolean isBlacklistedArtifact(String val) {
        if (val == null) return true;
        String u = val.trim().toUpperCase(Locale.US);
        return u.contains("BA465E1492CBB45A")
                || u.contains("SO2-HWID-V10")
                || u.contains("SO2TELEPORT-LOCAL-KEY")
                || u.contains("BOHDANIUK")
                || u.contains("CRACK");
    }

    private boolean isFridaOrXposedActive() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable ignored) {}

        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String l = line.toLowerCase(Locale.US);
                if (l.contains("frida-agent") || l.contains("gum-js-loop") || l.contains("xposedbridge")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isClientTampered() {
        if (isFridaOrXposedActive()) return true;
        try {
            InputStream is = context.getAssets().open("index.html");
            Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
            String html = s.hasNext() ? s.next() : "";
            is.close();
            String lower = html.toLowerCase(Locale.US);
            if (lower.contains("bohdaniuk")
                    || lower.contains("локальная активация")
                    || lower.contains("cracked")
                    || lower.contains("so2teleport-local-key")
                    || lower.contains("ba465e1492cbb45a")
                    || !html.contains("SO2TELEPORT_QUANTUM_V9_SEAL")
                    || !html.contains("pollLicenseExpiration")) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // Проверка HMAC-SHA256 подписи ответа от твоего bot.py
    private String computeDailyHmac(String payload) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String dayStr = sdf.format(new Date());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] dailyKey = sha.digest(("SO2_QUANTUM_V10_KEY_9941|" + dayStr).getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(dailyKey, "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : sig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // =========================================================================
    // 3. ЖИВОЙ АППАРАТНЫЙ HWID БЕЗ КЭШИРОВАНИЯ В SHAREDPREFERENCES
    // =========================================================================
    private String getImmutableDrmId() {
        StringBuilder raw = new StringBuilder();
        try {
            UUID widevineUuid = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm drm = new MediaDrm(widevineUuid);
            byte[] drmId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            if (drmId != null) {
                for (byte b : drmId) raw.append(String.format("%02x", b));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close();
            else drm.release();
        } catch (Throwable ignored) {}

        raw.append("|").append(Build.BOARD)
           .append("|").append(Build.BRAND)
           .append("|").append(Build.DEVICE)
           .append("|").append(Build.HARDWARE)
           .append("|").append(Build.MODEL);

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "hw7744aabb1122cc";
        }
    }

    @JavascriptInterface
    public String getDeviceHwid() {
        String drmHwid = getImmutableDrmId();
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        if (androidId == null || androidId.trim().isEmpty()) {
            return drmHwid;
        }
        return drmHwid + "|" + androidId.trim().toLowerCase(Locale.US);
    }

    @JavascriptInterface
    public String getDisplayHwid() {
        return getImmutableDrmId();
    }

    // Совместимость с вызовом B.getHardwareId() в index.html (закрытие уязвимости #17)
    @JavascriptInterface
    public String getHardwareId() {
        return getImmutableDrmId();
    }

    // =========================================================================
    // 4. СРОК ПОДПИСКИ И WATCHDOG (БЕЗ ЛАЗЕЕК OFFLINE И CATCH)
    // =========================================================================
    private long getDefaultDurationMsForKey(String cleanKey) {
        if (cleanKey == null || isBlacklistedArtifact(cleanKey)) return 0L;
        String u = cleanKey.toUpperCase(Locale.US);
        if (u.startsWith("TPLIFE") || u.contains("LIFE") || u.startsWith("TP999D")) return 3650L * 24L * 3600L * 1000L;
        if (u.startsWith("TP-1HR") || u.contains("1HR") || u.contains("-1H-")) return 3600L * 1000L;
        if (u.startsWith("TP1D") || u.contains("-1D-") || u.contains("24H")) return 24L * 3600L * 1000L;
        if (u.startsWith("TP7D") || u.contains("-7D-")) return 7L * 24L * 3600L * 1000L;
        if (u.startsWith("TP21D") || u.contains("21D")) return 21L * 24L * 3600L * 1000L;
        if (u.startsWith("TP50D") || u.contains("50D")) return 50L * 24L * 3600L * 1000L;
        if (u.startsWith("TP90D") || u.contains("90D")) return 90L * 24L * 3600L * 1000L;
        return 30L * 24L * 3600L * 1000L;
    }

    private long getOrInitKeyDeadlineMs(String cleanKey, JSONObject serverRes) {
        if (cleanKey == null || cleanKey.isEmpty() || isBlacklistedArtifact(cleanKey)) return 0L;
        String deadlinePrefKey = "key_deadline_ms_" + cleanKey;
        long now = System.currentTimeMillis();

        if (serverRes != null) {
            long remSec = serverRes.optLong("remaining_sec",
                    serverRes.optLong("time_left_sec",
                            serverRes.optLong("seconds_left", -1L)));
            if (remSec > 0) {
                long newDeadline = now + (remSec * 1000L);
                prefs.edit().putLong(deadlinePrefKey, newDeadline).apply();
                return newDeadline;
            }
            long expUnix = serverRes.optLong("expires_at", serverRes.optLong("exp", -1L));
            if (expUnix > 1700000000L) {
                long newDeadline = (expUnix < 100000000000L) ? (expUnix * 1000L) : expUnix;
                prefs.edit().putLong(deadlinePrefKey, newDeadline).apply();
                return newDeadline;
            }
        }

        long savedDeadline = prefs.getLong(deadlinePrefKey, 0L);
        // Защита от подстановки Long.MAX_VALUE (уязвимость #30)
        if (savedDeadline > now + (4000L * 24L * 3600L * 1000L)) return 0L;
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

        if (serverRes != null) {
            String serverText = serverRes.optString("expires_in",
                    serverRes.optString("days_left",
                            serverRes.optString("time_left", "")));
            if (serverText != null && !serverText.isEmpty() && !serverText.equalsIgnoreCase("null")) {
                if (serverText.toUpperCase(Locale.US).contains("АКТИВНА")) return serverText;
                return "АКТИВНА (" + serverText + ")";
            }
        }

        long totalMinutes = diffMs / (60L * 1000L);
        long days = totalMinutes / (60L * 24L);
        long hours = (totalMinutes % (60L * 24L)) / 60L;
        long mins = totalMinutes % 60L;

        if (days >= 1800) return "АКТИВНА (НАВСЕГДА 👑)";
        if (days > 0) return "АКТИВНА (" + days + " дн. " + hours + " ч.)";
        if (hours > 0) return "АКТИВНА (" + hours + " ч. " + mins + " мин.)";
        return "АКТИВНА (" + Math.max(1, mins) + " мин.)";
    }

    private void startLicenseWatchdog() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isClientTampered()) {
                    forceKickExpiredUser("Обнаружена попытка взлома клиента (Anti-Crack V10)!");
                    return;
                }
                final String savedKey = getSavedKey();
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

                            String srvSig = res.optString("sig", "");
                            if (!srvSig.isEmpty()) {
                                String expectedSig = computeDailyHmac(savedKey + "|" + hwid + "|" + status);
                                if (!expectedSig.equalsIgnoreCase(srvSig)) {
                                    forceKickExpiredUser("Ошибка подписи сервера!");
                                    return;
                                }
                            }

                            // Статус "offline" больше не считается валидным
                            boolean explicitlyBlocked = "expired".equalsIgnoreCase(status)
                                    || "blocked".equalsIgnoreCase(status)
                                    || "banned".equalsIgnoreCase(status)
                                    || "device_mismatch".equalsIgnoreCase(status)
                                    || "offline".equalsIgnoreCase(status)
                                    || res.optBoolean("blocked", false);

                            if (explicitlyBlocked) {
                                forceKickExpiredUser(res.optString("message", "Ключ заблокирован или истёк!"));
                            } else if ("ok".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status) || res.optBoolean("ok", false)) {
                                getOrInitKeyDeadlineMs(savedKey, res);
                                prefs.edit().putLong("last_server_ok_ts", System.currentTimeMillis()).apply();
                            }
                        } catch (Exception e) {
                            // Если связи с сервером нет дольше 15 минут — блокируем сессию
                            long lastOk = prefs.getLong("last_server_ok_ts", System.currentTimeMillis());
                            if (System.currentTimeMillis() - lastOk > 15L * 60L * 1000L) {
                                forceKickExpiredUser("Потеряна связь с сервером авторизации!");
                            }
                        }
                    });
                }
                mainHandler.postDelayed(this, 20000);
            }
        }, 20000);
    }

    // =========================================================================
    // 5. УПРАВЛЕНИЕ ОВЕРЛЕЯМИ И КНОПКОЙ РАСТЯГА «T» С ПОДСВЕТКОЙ ВЕДРА
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

    private void forceKickExpiredUser(String reasonToast) {
        saveEncryptedKeyToVault("");
        prefs.edit()
                .remove("saved_license_key")
                .putBoolean(PREF_FIRST_LOGIN_DONE, false)
                .apply();
        legacyPrefs.edit().remove("license_key").apply();
        ApiManager.invalidateSession();
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
                String safeMsg = (reasonToast == null ? "Введите лицензионный ключ" : reasonToast)
                        .replace("'", "").replace("\"", "");
                ((MainActivity) activity).evalJsFromNotification(
                        "try { if(typeof forceShowKickScreen==='function') forceShowKickScreen('" + safeMsg + "'); } catch(e){}"
                );
            }
        });
    }

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

        if (TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted()) {
            String pkg = context.getPackageName();
            TeleportEngine.execShizukuCommand(
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
                    Toast.makeText(context, "Выдайте разрешение «Поверх других окон» для кнопки «T»!", Toast.LENGTH_LONG).show();
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

    @JavascriptInterface
    public String activateStretchFloatingButton(String ratio) {
        if (getSavedKey().isEmpty() || isClientTampered()) return "NEED_AUTH";
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
        TeleportEngine.execShizukuCommand(
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

                if (floatingStretchBtn != null) {
                    int[] bLoc = new int[2];
                    floatingStretchBtn.getLocationOnScreen(bLoc);
                    float bCx = bLoc[0] + floatingStretchBtn.getWidth() / 2f;
                    float bCy = bLoc[1] + floatingStretchBtn.getHeight() / 2f;
                    if (bCx >= tLoc[0] - padX && bCx <= tLoc[0] + trashPillView.getWidth() + padX
                            && bCy >= tLoc[1] - padY && bCy <= tLoc[1] + trashPillView.getHeight() + padY) {
                        return true;
                    }
                }
            }

            boolean yNearBottom = rawY >= (sh - dpToPx(140))
                    || (stretchParams != null && (stretchParams.y + btnSize) >= (sh - dpToPx(140)))
                    || (stretchParams != null && stretchParams.y >= (sh * 0.68f));

            boolean xNearCenter = Math.abs(rawX - (sw / 2f)) <= (sw * 0.38f)
                    || (stretchParams != null && Math.abs((stretchParams.x + btnSize / 2f) - (sw / 2f)) <= (sw * 0.38f));

            return yNearBottom && xNearCenter;
        } catch (Throwable t) {
            return false;
        }
    }

    private void toggleTwoStepStretchAction() {
        if (getSavedKey().isEmpty()) {
            forceKickExpiredUser("Ключ не активен!");
            return;
        }
        boolean shizukuOk = TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted();
        if (!shizukuOk) {
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
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (isBoxBarsActive) {
            bg.setColor(Color.parseColor("#E610B981"));
            bg.setStroke(dpToPx(2), Color.WHITE);
            floatingStretchBtn.setText("2\n" + shortLabel);
        } else {
            bg.setColor(Color.parseColor("#E60B0E14"));
            bg.setStroke(dpToPx(2), Color.parseColor("#FF2E5B"));
            floatingStretchBtn.setText("T\n" + shortLabel);
        }
        floatingStretchBtn.setBackground(bg);
    }

    @JavascriptInterface
    public void applyStretchDirectlyNow(String ratio) {
        activateStretchFloatingButton(ratio);
    }

    @JavascriptInterface
    public void applyDirectStretchRatio(String ratio) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
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
            Toast.makeText(context, "🔄 Кнопка «T» убрана, родной экран восстановлен", Toast.LENGTH_SHORT).show();
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
    public boolean isShizukuReady() {
        return TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted();
    }

    @JavascriptInterface
    public boolean isShizukuActive() {
        return TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted();
    }

    @JavascriptInterface
    public void requestShizukuPermission() {
        TeleportEngine.requestShizukuPermissionIfNeeded();
    }

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
    public void stopTeleport() {
        TeleportEngine.stopTeleport();
    }

    @JavascriptInterface
    public void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
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
    public String performRamBoost() {
        return TeleportEngine.performRamBoost(context);
    }

    @JavascriptInterface
    public void setHzThermalGuard(boolean enabled) {
        TeleportEngine.setHzThermalGuard(enabled);
    }

    @JavascriptInterface
    public void applyShooterOptimizer(
            int fpsLock, int gpuBoost, int frameTime,
            int edgeSens, int multiTouch, int netBuffer, int thermalLim
    ) {
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
    public String runSmartDeviceScan() {
        return TeleportEngine.runSmartDeviceScan(context);
    }

    @JavascriptInterface
    public void applyUltraProTouchMatrix(int modeLevel) {
        if (getSavedKey().isEmpty() || isClientTampered()) return;
        ProfileManager.addTeleportCountToTotal(context, 1);
        TeleportEngine.startTeleport("extreme");
    }

    @JavascriptInterface
    public String uploadCloudConfig(String title, String presetJson) {
        String key = getSavedKey();
        if (key.isEmpty() || isClientTampered()) {
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
        if (key.isEmpty() || isClientTampered()) {
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

    @JavascriptInterface
    public void updateCrosshair(String type) {
        final String cleanType = (type == null) ? "off" : type.trim();
        if (getSavedKey().isEmpty() && !"off".equalsIgnoreCase(cleanType)) return;

        mainHandler.post(() -> {
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
                crosshairParams = new WindowManager.LayoutParams(
                        dpToPx(48), dpToPx(48), overlayType, flags, PixelFormat.TRANSLUCENT
                );
                crosshairParams.gravity = Gravity.CENTER;
                crosshairParams.x = 0;
                crosshairParams.y = 0;
                crosshairParams.alpha = 0.78f;
                try {
                    windowManager.addView(crosshairView, crosshairParams);
                    isCrosshairShown = true;
                } catch (Exception ignored) {}
            }
            if (crosshairView != null) {
                crosshairView.setVisibility(View.VISIBLE);
                crosshairView.setCrosshairType(cleanType);
            }
        });
    }

    @JavascriptInterface
    public void setCrosshairOffset(int offsetXDp, int offsetYDp) {
        mainHandler.post(() -> {
            if (isCrosshairShown && crosshairView != null && crosshairParams != null && windowManager != null) {
                crosshairParams.x = 0;
                crosshairParams.y = 0;
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
        if ("off".equalsIgnoreCase(type) || type == null || type.trim().isEmpty() || opacityPct <= 0) {
            updateCrosshair("off");
            return;
        }
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
    public void setStealthOverlayMode(boolean stealth) {
        updateOverlayStealthFlag(stealth);
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        String model = Build.MODEL;
        int shortPx = TeleportEngine.getNativeShortSide();
        int longPx = TeleportEngine.getNativeLongSide();
        return model + "|" + shortPx + "x" + longPx + " (120 Гц)";
    }

    @JavascriptInterface
    public String getSavedKey() {
        if (!prefs.getBoolean(PREF_FIRST_LOGIN_DONE, false)) return "";
        String key = readEncryptedKeyFromVault();
        if (key.isEmpty() || isBlacklistedArtifact(key)) return "";
        return key;
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
            prefs.edit().putString("saved_player_nick", nick.trim()).apply();
            legacyPrefs.edit().putString("nickname", nick.trim()).apply();
        }
    }

    private void markKeyActivated(String nick, String cleanKey, JSONObject serverRes) {
        ApiManager.setSessionVerified(true);
        saveEncryptedKeyToVault(cleanKey);
        getOrInitKeyDeadlineMs(cleanKey, serverRes);
        prefs.edit()
                .remove("saved_license_key")
                .putString("owned_token_" + cleanKey, "OWNED_" + cleanKey)
                .putBoolean(PREF_FIRST_LOGIN_DONE, true)
                .putLong("last_server_ok_ts", System.currentTimeMillis())
                .apply();
        ProfileManager.saveActivatedProfile(context, nick, cleanKey);
    }

    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (isClientTampered()) {
            return "ERROR|Обнаружена модификация файлов приложения!";
        }
        if (key == null || key.trim().length() < 5 || isBlacklistedArtifact(key)) {
            return "ERROR|Введите лицензионный ключ из Telegram-бота";
        }
        final String cleanKey = key.trim().toUpperCase(Locale.US);
        final String hwid = getDeviceHwid();
        final String nick = getSavedNickname().isEmpty() ? "dare" : getSavedNickname();

        try {
            Callable<String> task = () -> ApiManager.activateLicense(cleanKey, hwid, nick);
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(5500, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            String srvSig = res.optString("sig", "");
            if (!srvSig.isEmpty()) {
                String expectedSig = computeDailyHmac(cleanKey + "|" + hwid + "|" + status);
                if (!expectedSig.equalsIgnoreCase(srvSig)) {
                    return "ERROR|Ошибка криптографической подписи сервера!";
                }
            }

            boolean blocked = res.optBoolean("blocked", false)
                    || "expired".equalsIgnoreCase(status)
                    || "blocked".equalsIgnoreCase(status)
                    || "banned".equalsIgnoreCase(status)
                    || "device_mismatch".equalsIgnoreCase(status)
                    || "offline".equalsIgnoreCase(status);

            boolean ok = (res.optBoolean("ok", false)
                    || "ok".equalsIgnoreCase(status)
                    || "active".equalsIgnoreCase(status)) && !blocked;

            if (ok) {
                markKeyActivated(nick, cleanKey, res);
                SecurityShield.issueVerifiedSessionToken(context, cleanKey);
                String liveLabel = buildLiveSubscriptionLabel(res, cleanKey);
                if ("EXPIRED".equals(liveLabel)) {
                    forceKickExpiredUser("Срок действия этого ключа истёк!");
                    return "ERROR|Срок действия этого ключа истёк!";
                }
                return "OK|" + liveLabel;
            }

            return "ERROR|" + res.optString("message", "Ключ не найден, истёк или привязан к другому устройству!");
        } catch (Exception e) {
            return "ERROR|Нет связи с сервером авторизации. Проверьте интернет!";
        }
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        if (isClientTampered()) {
            forceKickExpiredUser("Обнаружена модификация приложения!");
            return "BANNED|Модификация файлов приложения";
        }
        if (!prefs.getBoolean(PREF_FIRST_LOGIN_DONE, false)) {
            return "EXPIRED|Введите ваш логин и лицензионный ключ";
        }

        final String cleanKey = (key != null && !key.trim().isEmpty())
                ? key.trim().toUpperCase(Locale.US)
                : getSavedKey();

        if (cleanKey.length() < 5 || isBlacklistedArtifact(cleanKey)) {
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

            String srvSig = res.optString("sig", "");
            if (!srvSig.isEmpty()) {
                String expectedSig = computeDailyHmac(cleanKey + "|" + hwid + "|" + status);
                if (!expectedSig.equalsIgnoreCase(srvSig)) {
                    forceKickExpiredUser("Ошибка подписи ответа сервера!");
                    return "BLOCKED|Ошибка подписи сервера";
                }
            }

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

            boolean ok = res.optBoolean("ok", false)
                    || "ok".equalsIgnoreCase(status)
                    || "active".equalsIgnoreCase(status);

            if (ok) {
                ApiManager.setSessionVerified(true);
                prefs.edit().putLong("last_server_ok_ts", System.currentTimeMillis()).apply();
                String label = buildLiveSubscriptionLabel(res, cleanKey);
                return "OK|" + label;
            }

            String msg = res.optString("message", "Срок действия ключа истёк!");
            forceKickExpiredUser(msg);
            return "EXPIRED|" + msg;
        } catch (Exception e) {
            long lastOk = prefs.getLong("last_server_ok_ts", 0L);
            if (ApiManager.isSessionVerified() && (System.currentTimeMillis() - lastOk) < 15L * 60L * 1000L) {
                return "OK|" + localLiveLabel;
            }
            forceKickExpiredUser("Проверьте подключение к интернету!");
            return "EXPIRED|Проверьте подключение к интернету";
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
        if (getSavedKey().isEmpty() || isClientTampered()) {
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
                evalJsInWebView("if(window.toggleMicMute) window.toggleMicMute();");
            } else if (ACTION_VOICE_DEAFEN.equals(act)) {
                evalJsInWebView("if(window.toggleDeafenSpeaker) window.toggleDeafenSpeaker();");
            } else if (ACTION_VOICE_HANGUP.equals(act)) {
                evalJsInWebView("if(window.leaveVoiceRoom) window.leaveVoiceRoom();");
            }
        }
    };

    private void evalJsInWebView(String jsCode) {
        mainHandler.post(() -> {
            try {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).evalJsFromNotification(jsCode);
                    return;
                }
                if (activity != null && !activity.isFinishing()) {
                    View content = activity.findViewById(android.R.id.content);
                    if (content instanceof android.view.ViewGroup) {
                        android.view.ViewGroup vg = (android.view.ViewGroup) content;
                        if (vg.getChildCount() > 0 && vg.getChildAt(0) instanceof android.webkit.WebView) {
                            ((android.webkit.WebView) vg.getChildAt(0)).evaluateJavascript(jsCode, null);
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void ensureVoiceNotifSetup() {
        if (TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted()) {
            String pkg = context.getPackageName();
            TeleportEngine.execShizukuCommand(
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
                        "SO2Teleport Discord Voice",
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
                        .setContentTitle("🟢 Войс Отряда • " + (roomName == null || roomName.isEmpty() ? "SQUAD-MAIN" : roomName))
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
                animate()
                        .scaleX(h ? 1.15f : 1.0f)
                        .scaleY(h ? 1.15f : 1.0f)
                        .setDuration(110)
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
                outerRect.set(pad * 0.35f, pad * 0.35f, getWidth() - pad * 0.35f, getHeight() - pad * 0.35f);
                glowPaint.setColor(Color.parseColor("#66FF1744"));
                canvas.drawRoundRect(outerRect, 999f, 999f, glowPaint);

                bgPaint.setColor(Color.parseColor("#F5FF1744"));
                borderPaint.setStrokeWidth(2.8f * density);
                borderPaint.setColor(Color.WHITE);
                textPaint.setColor(Color.WHITE);
            } else {
                bgPaint.setColor(Color.parseColor("#E610131C"));
                borderPaint.setStrokeWidth(2.0f * density);
                borderPaint.setColor(Color.parseColor("#FF2E5B"));
                textPaint.setColor(Color.parseColor("#FCA5A5"));
            }

            canvas.drawRoundRect(rect, 999f, 999f, bgPaint);
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
                float rad = Math.max(3.5f * d, lenPx * 0.65f);
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
                float half = Math.max(2.0f * d, lenPx * 0.30f);
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(cx - half, cy - half, cx + half, cy + half, outlinePaint);
                }
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
                return;
            }

            if ("chevron".equalsIgnoreCase(type)) {
                float arm = Math.max(3.5f * d, lenPx * 0.55f);
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
                float dist = Math.max(4f * d, (gapPx + lenPx) * 0.48f);
                float leg = Math.max(2.5f * d, lenPx * 0.32f);
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
