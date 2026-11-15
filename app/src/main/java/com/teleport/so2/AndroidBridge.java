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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.MediaDrm;
import android.net.Uri;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
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
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AndroidBridge {

    private static final String KEYSTORE_ALIAS = "SO2Teleport_TEE_MasterKey_v10";
    private static final String PREF_VAULT_NAME = "SO2Teleport_Vault_V10";
    private static final String VAULT_KEY_LIC = "enc_lic_blob_v10";
    private static final String VAULT_KEY_DEADLINE = "enc_deadline_blob_v10";
    private static final String VAULT_KEY_TOKEN = "enc_session_token_v10";

    // Подгрузка нативного щита libso2.so (если скомпилирован в APK)
    private static boolean nativeLoaded = false;
    static {
        try {
            System.loadLibrary("so2");
            nativeLoaded = true;
        } catch (Throwable ignored) {
            nativeLoaded = false;
        }
    }

    private static native String nativeGetHwid(String drmHex, String androidId, String boardInfo);
    private static native boolean nativeVerifyGuardState();
    private static native String nativeSignHmac(String payload, String daySalt);

    private final Context context;
    private final Activity activity;
    private final SharedPreferences vaultPrefs;
    private final SharedPreferences prefs;
    private final SharedPreferences legacyPrefs;
    private final SharedPreferences profilePrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService watchdogExecutor = Executors.newSingleThreadScheduledExecutor();

    // Криптографическое состояние сессии (невозможно подделать одним boolean в smali)
    private final AtomicLong verifiedSessionTokenHash = new AtomicLong(0L);
    private final AtomicLong lastVerifiedServerTs = new AtomicLong(0L);
    private final AtomicBoolean sessionAlive = new AtomicBoolean(false);
    private volatile String activeRuntimeKey = "";
    private volatile String expectedHtmlSha256 = "";

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
        this.vaultPrefs = context.getSharedPreferences(PREF_VAULT_NAME, Context.MODE_PRIVATE);
        this.prefs = context.getSharedPreferences("SO2TeleportPrefs_V410", Context.MODE_PRIVATE);
        this.legacyPrefs = context.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        this.profilePrefs = context.getSharedPreferences("so2_teleport_profile_prefs", Context.MODE_PRIVATE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        // Удаляем старые предсказуемые ключи из SharedPreferences (закрытие уязвимостей #6, #8, #18)
        prefs.edit().remove("immutable_widevine_hwid_v9").remove("saved_license_key").apply();
        legacyPrefs.edit().remove("license_key").apply();

        // Мгновенная проверка окружения при старте
        if (detectHostileEnvironment() || inspectHtmlIntegrityAndtampering()) {
            hardTerminateProcess("Security Violation V10");
            return;
        }

        // Миграция ключа в зашифрованное хранилище AndroidKeyStore TEE
        String existingEncKey = readFromKeystoreVault(VAULT_KEY_LIC);
        if (!existingEncKey.isEmpty() && !isBlacklistedCrackArtifact(existingEncKey)) {
            this.activeRuntimeKey = existingEncKey;
        }

        TeleportEngine.init(context);
        TeleportEngine.requestShizukuPermissionIfNeeded();
        startDualIndependentWatchdogs();
    }

    // =========================================================================
    // 1. ANDROID KEYSTORE (TEE / AES-256-GCM) — ЗАКРЫТИЕ УЯЗВИМОСТЕЙ #6, #7, #18
    // =========================================================================
    private SecretKey getOrCreateHardwareAesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null);
            if (entry != null) return entry.getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        kg.init(builder.build());
        return kg.generateKey();
    }

    private void writeToKeystoreVault(String prefKey, String plainText) {
        try {
            if (plainText == null || plainText.isEmpty()) {
                vaultPrefs.edit().remove(prefKey).apply();
                return;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateHardwareAesKey());
            byte[] iv = cipher.getIV();
            byte[] aad = computeLiveHardwareId().getBytes(StandardCharsets.UTF_8);
            cipher.updateAAD(aad);
            byte[] enc = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
            vaultPrefs.edit().putString(prefKey, blob).apply();
        } catch (Throwable ignored) {}
    }

    private String readFromKeystoreVault(String prefKey) {
        try {
            String blob = vaultPrefs.getString(prefKey, "");
            if (blob == null || !blob.contains(":")) return "";
            String[] parts = blob.split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateHardwareAesKey(), spec);
            // Привязка к текущему HWID: чужой дамп XML не расшифруется на другом устройстве
            cipher.updateAAD(computeLiveHardwareId().getBytes(StandardCharsets.UTF_8));
            byte[] dec = cipher.doFinal(enc);
            return new String(dec, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    // =========================================================================
    // 2. АНТИ-ОТЛАДКА, АНТИ-FRIDA, АНТИ-XPOSED, АНТИ-ЭМУЛЯТОР (ПУНКТЫ 15-28)
    // =========================================================================
    private boolean isBlacklistedCrackArtifact(String val) {
        if (val == null) return true;
        String u = val.trim().toUpperCase(Locale.US);
        return u.contains("BA465E1492CBB45A")
                || u.contains("HW9900AABB1122CC")
                || u.contains("SO2-HWID-V10")
                || u.contains("SO2TELEPORT-LOCAL-KEY")
                || u.contains("CRACK")
                || u.contains("BOHDANIUK")
                || u.contains("FREE_KEY");
    }

    private boolean detectHostileEnvironment() {
        try {
            if (nativeLoaded && !nativeVerifyGuardState()) return true;

            // 1. Проверка отладчика Java и флага android:debuggable
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;
            if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true;

            // 2. Проверка TracerPid в /proc/self/status
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("TracerPid:")) {
                        int tracerPid = Integer.parseInt(line.substring(10).trim());
                        if (tracerPid != 0) return true;
                        break;
                    }
                }
            } catch (Throwable ignored) {}

            // 3. Сканирование /proc/self/maps на Frida, Xposed, Substrate, LSPlant
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String l = line.toLowerCase(Locale.US);
                    if (l.contains("frida")
                            || l.contains("gum-js-loop")
                            || l.contains("xposed")
                            || l.contains("substrate")
                            || l.contains("lsplant")
                            || l.contains("edxposed")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}

            // 4. Проверка ClassLoader на XposedBridge
            try {
                Class.forName("de.robv.android.xposed.XposedBridge");
                return true;
            } catch (ClassNotFoundException ignored) {}

            // 5. Проверка эмулятора (BlueStacks, Genymotion, Goldfish, Ranchu, отсутствие физ. акселерометра)
            String fp = (Build.FINGERPRINT + "|" + Build.MODEL + "|" + Build.HARDWARE + "|" + Build.BOARD + "|" + Build.PRODUCT).toLowerCase(Locale.US);
            if (fp.contains("generic")
                    || fp.contains("goldfish")
                    || fp.contains("ranchu")
                    || fp.contains("bluestacks")
                    || fp.contains("genymotion")
                    || fp.contains("sdk_gphone")
                    || fp.contains("vbox86")) {
                return true;
            }
            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sm != null && sm.getSensorList(Sensor.TYPE_ACCELEROMETER).isEmpty()) {
                return true;
            }

            // 6. Проверка целостности подписи APK (защита от переподписи тестовым ключом MT Manager)
            String certHex = getApkSignatureSha256();
            // Дефолтный тестовый сертификат AOSP / MT Manager блокируется мгновенно
            if (certHex.startsWith("a40da80a59d170caa950cf15c18c454d")
                    || certHex.startsWith("61ed377e85d386a8dfee6b864bd85b0b")) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isFridaPortOpen() {
        int[] ports = new int[]{27042, 27043};
        for (int p : ports) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", p), 45);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private String getApkSignatureSha256() {
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            byte[] certBytes = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo != null) {
                    Signature[] sigs = pi.signingInfo.hasMultipleSigners()
                            ? pi.signingInfo.getApkContentsSigners()
                            : pi.signingInfo.getSigningCertificateHistory();
                    if (sigs != null && sigs.length > 0) certBytes = sigs[0].toByteArray();
                }
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (pi.signatures != null && pi.signatures.length > 0) {
                    certBytes = pi.signatures[0].toByteArray();
                }
            }
            if (certBytes == null) return "no_sig";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(certBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "err_sig";
        }
    }

    // Проверка целостности assets/index.html + SHA-256 (закрытие уязвимостей #1, #15, #28, #35)
    private boolean inspectHtmlIntegrityAndtampering() {
        try {
            InputStream is = context.getAssets().open("index.html");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            is.close();
            byte[] rawBytes = bos.toByteArray();
            if (rawBytes.length < 512) return true;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawBytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            String currentSha = hex.toString();

            if (expectedHtmlSha256.isEmpty()) {
                expectedHtmlSha256 = currentSha;
            } else if (!expectedHtmlSha256.equals(currentSha)) {
                return true;
            }

            String html = new String(rawBytes, StandardCharsets.UTF_8);
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
        } catch (Throwable t) {
            return true;
        }
        return false;
    }

    // Жёсткое завершение процесса, которое нельзя отключить nop-ом forceKickExpiredUser (уязвимость #2)
    private void hardTerminateProcess(String reason) {
        sessionAlive.set(false);
        verifiedSessionTokenHash.set(0L);
        activeRuntimeKey = "";
        writeToKeystoreVault(VAULT_KEY_LIC, "");
        writeToKeystoreVault(VAULT_KEY_TOKEN, "");
        try {
            TeleportEngine.stopTeleport();
            if (TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted()) {
                TeleportEngine.execShizukuCommand("wm size reset; wm density reset;");
            }
        } catch (Throwable ignored) {}
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {}
        try {
            Runtime.getRuntime().halt(1);
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // 3. ЖИВОЙ АППАРАТНЫЙ HWID БЕЗ КЭША В SHAREDPREFERENCES (УЯЗВИМОСТИ #7, #8, #17, #32)
    // =========================================================================
    private String computeLiveHardwareId() {
        StringBuilder drmPart = new StringBuilder();
        try {
            UUID widevineUuid = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm drm = new MediaDrm(widevineUuid);
            byte[] drmId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            if (drmId != null) {
                for (byte b : drmId) drmPart.append(String.format("%02x", b));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close();
            else drm.release();
        } catch (Throwable ignored) {}

        String androidId = "";
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
        } catch (Throwable ignored) {}

        String boardInfo = Build.BOARD + "|" + Build.BRAND + "|" + Build.DEVICE + "|" + Build.HARDWARE + "|" + Build.MODEL;

        if (nativeLoaded) {
            try {
                String nat = nativeGetHwid(drmPart.toString(), androidId, boardInfo);
                if (nat != null && nat.length() >= 16 && !isBlacklistedCrackArtifact(nat)) {
                    return nat;
                }
            } catch (Throwable ignored) {}
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String seed = drmPart + "|" + boardInfo + "|" + androidId.toLowerCase(Locale.US);
            byte[] digest = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            String drmHash = hex.toString();
            if (isBlacklistedCrackArtifact(drmHash)) {
                hardTerminateProcess("Spoofed HWID");
                return "blocked";
            }
            return androidId.isEmpty() ? drmHash : (drmHash + "|" + androidId.trim().toLowerCase(Locale.US));
        } catch (Exception e) {
            hardTerminateProcess("HWID Crypto Error");
            return "blocked";
        }
    }

    @JavascriptInterface
    public String getDeviceHwid() {
        return computeLiveHardwareId();
    }

    @JavascriptInterface
    public String getDisplayHwid() {
        String full = computeLiveHardwareId();
        int idx = full.indexOf('|');
        return idx > 0 ? full.substring(0, idx) : full;
    }

    // Закрытие уязвимости #17 (совпадение имени метода с вызовом B.getHardwareId() в index.html)
    @JavascriptInterface
    public String getHardwareId() {
        return getDisplayHwid();
    }

    // =========================================================================
    // 4. КРИПТОГРАФИЧЕСКАЯ ПОДПИСЬ ЗАПРОСОВ И ОТВЕТОВ (HMAC-SHA256 + NONCE + TS)
    // =========================================================================
    private String computeDailyHmac(String data) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String dayStr = sdf.format(new Date());
            if (nativeLoaded) {
                try {
                    return nativeSignHmac(data, dayStr);
                } catch (Throwable ignored) {}
            }
            // Мастер-секрет собирается динамически и связывается с датой UTC
            byte[] rawSecret = new byte[]{
                    0x53, 0x4F, 0x32, 0x5F, 0x51, 0x55, 0x41, 0x4E,
                    0x54, 0x55, 0x4D, 0x5F, 0x56, 0x31, 0x30, 0x5F,
                    0x4B, 0x45, 0x59, 0x5F, 0x39, 0x39, 0x34, 0x31
            };
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] dailyKey = sha.digest((new String(rawSecret, StandardCharsets.UTF_8) + "|" + dayStr).getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(dailyKey, "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : sig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private long computeRuntimeTokenHash(String cleanKey, String hwid) {
        try {
            String sig = computeDailyHmac(cleanKey + "|" + hwid + "|" + expectedHtmlSha256);
            return Long.parseUnsignedLong(sig.substring(0, 15), 16);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private boolean isSessionCryptographicallyValid() {
        if (!sessionAlive.get() || activeRuntimeKey.isEmpty()) return false;
        if (isBlacklistedCrackArtifact(activeRuntimeKey)) {
            hardTerminateProcess("Crack Key Detected");
            return false;
        }
        long expected = computeRuntimeTokenHash(activeRuntimeKey, computeLiveHardwareId());
        return expected != 0L && verifiedSessionTokenHash.get() == expected;
    }

    // =========================================================================
    // 5. ДВОЙНОЙ НЕЗАВИСИМЫЙ WATCHDOG + ПРОВЕРКА DOM (УЯЗВИМОСТИ #3, #16, #27, #33)
    // =========================================================================
    private void startDualIndependentWatchdogs() {
        // Поток 1: Фоновый пул проверяет анти-отладку, порты Frida, целостность HTML и серверный статус
        watchdogExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (detectHostileEnvironment() || isFridaPortOpen() || inspectHtmlIntegrityAndtampering()) {
                    hardTerminateProcess("Runtime Integrity Triggered");
                    return;
                }
                final String currentKey = getSavedKey();
                if (!currentKey.isEmpty()) {
                    String liveLabel = buildLiveSubscriptionLabel(null, currentKey);
                    if ("EXPIRED".equals(liveLabel)) {
                        forceKickExpiredUser("Срок действия вашего ключа истёк!");
                        return;
                    }
                    String raw = ApiManager.verifyLicense(currentKey, computeLiveHardwareId());
                    JSONObject res = new JSONObject(raw);
                    String status = res.optString("status", "");

                    // Закрытие уязвимости #25: статус "offline" больше НЕ считается валидной лицензией
                    boolean ok = ("ok".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status) || res.optBoolean("ok", false))
                            && !"offline".equalsIgnoreCase(status)
                            && !res.optBoolean("blocked", false)
                            && !"expired".equalsIgnoreCase(status)
                            && !"banned".equalsIgnoreCase(status)
                            && !"device_mismatch".equalsIgnoreCase(status);

                    if (!ok) {
                        forceKickExpiredUser(res.optString("message", "Сессия недействительна или ключ истёк!"));
                        return;
                    }
                    getOrInitKeyDeadlineMs(currentKey, res);
                    lastVerifiedServerTs.set(SystemClock.elapsedRealtime());
                }
            } catch (Throwable t) {
                // Закрытие уязвимости #27: при обрыве или подмене ответа более 60 секунд — принудительный кик
                long elapsed = SystemClock.elapsedRealtime() - lastVerifiedServerTs.get();
                if (sessionAlive.get() && elapsed > 60000L) {
                    forceKickExpiredUser("Потеряна связь с сервером защиты!");
                }
            }
        }, 8, 12, TimeUnit.SECONDS);

        // Поток 2: Проверка целостности DOM в WebView (защита от скрытия #authScreenOverlay через CSS)
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isSessionCryptographicallyValid() && activity instanceof MainActivity) {
                        ((MainActivity) activity).evalJsFromNotification(
                                "(function(){var el=document.getElementById('authScreenOverlay');" +
                                "if(!el || window.getComputedStyle(el).display==='none' || window.getComputedStyle(el).visibility==='hidden' || parseFloat(window.getComputedStyle(el).opacity)<0.5){" +
                                "if(typeof forceShowKickScreen==='function')forceShowKickScreen('Требуется авторизация');else location.reload();" +
                                "}})();"
                        );
                    }
                } catch (Throwable ignored) {}
                mainHandler.postDelayed(this, 6500);
            }
        }, 6500);
    }

    // =========================================================================
    // 6. СРОКИ ДЕЙСТВИЯ КЛЮЧА И ПОДПИСКИ (УЯЗВИМОСТИ #30, #31)
    // =========================================================================
    private long getDefaultDurationMsForKey(String cleanKey) {
        if (cleanKey == null || isBlacklistedCrackArtifact(cleanKey)) return 0L;
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
        if (cleanKey == null || cleanKey.isEmpty() || isBlacklistedCrackArtifact(cleanKey)) return 0L;
        long now = System.currentTimeMillis();

        if (serverRes != null) {
            // Проверка расхождения времени с сервером (защита от перевода часов назад — пункт 30)
            long srvTime = serverRes.optLong("server_ts", 0L);
            if (srvTime > 1700000000L) {
                long srvMs = (srvTime < 100000000000L) ? (srvTime * 1000L) : srvTime;
                if (Math.abs(now - srvMs) > 300000L) {
                    return 0L;
                }
            }

            long remSec = serverRes.optLong("remaining_sec",
                    serverRes.optLong("time_left_sec",
                            serverRes.optLong("seconds_left", -1L)));
            if (remSec > 0) {
                long newDeadline = now + (remSec * 1000L);
                writeToKeystoreVault(VAULT_KEY_DEADLINE + "_" + cleanKey, String.valueOf(newDeadline));
                return newDeadline;
            }
            long expUnix = serverRes.optLong("expires_at", serverRes.optLong("exp", -1L));
            if (expUnix > 1700000000L) {
                long newDeadline = (expUnix < 100000000000L) ? (expUnix * 1000L) : expUnix;
                writeToKeystoreVault(VAULT_KEY_DEADLINE + "_" + cleanKey, String.valueOf(newDeadline));
                return newDeadline;
            }
        }

        String savedStr = readFromKeystoreVault(VAULT_KEY_DEADLINE + "_" + cleanKey);
        if (!savedStr.isEmpty()) {
            try {
                long savedDeadline = Long.parseLong(savedStr);
                // Защита от Long.MAX_VALUE заглушки (уязвимость #30)
                if (savedDeadline > now + (4000L * 24L * 3600L * 1000L)) return 0L;
                if (savedDeadline > 0L) return savedDeadline;
            } catch (NumberFormatException ignored) {}
        }

        long computedDeadline = now + getDefaultDurationMsForKey(cleanKey);
        writeToKeystoreVault(VAULT_KEY_DEADLINE + "_" + cleanKey, String.valueOf(computedDeadline));
        return computedDeadline;
    }

    private String buildLiveSubscriptionLabel(JSONObject serverRes, String cleanKey) {
        if (!isSessionCryptographicallyValid() && serverRes == null) return "EXPIRED";

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

        if (days >= 1800) return "АКТИВНА (НАВСЕГДА)";
        if (days > 0) return "АКТИВНА (" + days + " дн. " + hours + " ч.)";
        if (hours > 0) return "АКТИВНА (" + hours + " ч. " + mins + " мин.)";
        return "АКТИВНА (" + Math.max(1, mins) + " мин.)";
    }

    // =========================================================================
    // 7. УПРАВЛЕНИЕ ОВЕРЛЕЯМИ И ПРИНУДИТЕЛЬНЫЙ КИК
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
        sessionAlive.set(false);
        verifiedSessionTokenHash.set(0L);
        activeRuntimeKey = "";
        writeToKeystoreVault(VAULT_KEY_LIC, "");
        writeToKeystoreVault(VAULT_KEY_TOKEN, "");
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

    // Встроенный охранник для каждого рабочего метода: если сессия не прошла криптопроверку — жёсткий выход
    private boolean requireVerifiedSessionOrHalt() {
        if (detectHostileEnvironment() || inspectHtmlIntegrityAndtampering()) {
            hardTerminateProcess("Tamper Detected");
            return false;
        }
        if (!isSessionCryptographicallyValid()) {
            forceKickExpiredUser("Недействительная сессия лицензии!");
            return false;
        }
        return true;
    }

    // =========================================================================
    // 8. АУДИО, SHIZUKU И ПЛАВАЮЩАЯ КНОПКА РАСТЯГА «T» С ПОДСВЕТКОЙ ВЕДРА
    // =========================================================================
    @JavascriptInterface
    public void enforceGameAudioMode() {
        if (!requireVerifiedSessionOrHalt()) return;
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
        if (!requireVerifiedSessionOrHalt()) return "NEED_AUTH";
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
        if (!requireVerifiedSessionOrHalt()) return;

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
        if (!requireVerifiedSessionOrHalt()) return;
        activateStretchFloatingButton(ratio);
    }

    @JavascriptInterface
    public void applyDirectStretchRatio(String ratio) {
        if (!requireVerifiedSessionOrHalt()) return;
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
        if (!requireVerifiedSessionOrHalt()) return false;
        if (jsonConfig == null || jsonConfig.trim().isEmpty()) return false;
        prefs.edit().putString("full_user_config_v410", jsonConfig).apply();
        return true;
    }

    @JavascriptInterface
    public String loadFullConfigJson() {
        if (!requireVerifiedSessionOrHalt()) return "";
        return prefs.getString("full_user_config_v410", "");
    }

    @JavascriptInterface
    public String setWideHitboxMode(boolean enabled, String ratio) {
        if (!requireVerifiedSessionOrHalt()) return "NEED_AUTH";
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
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.requestShizukuPermissionIfNeeded();
        TeleportEngine.healTouchAndRestart();
    }

    @JavascriptInterface
    public void startTeleport(String presetId) {
        if (!requireVerifiedSessionOrHalt()) return;
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
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.updateParameters(sharp, sx, sy, chunks, chunkFast, straight, stick, grid, pause, accel);
    }

    @JavascriptInterface
    public void setRawInputNoSmoothing(boolean enabled) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.setRawInputNoSmoothing(enabled);
    }

    @JavascriptInterface
    public void setVulkanGpuTurbo(boolean enabled) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.setVulkanGpuTurbo(enabled);
    }

    @JavascriptInterface
    public void setNetPingStabilizer(boolean enabled) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.setNetPingStabilizer(enabled);
    }

    @JavascriptInterface
    public void setTouchSamplingBoost(boolean enabled, int hz) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.setTouchSamplingBoost(enabled, hz);
    }

    @JavascriptInterface
    public String performRamBoost() {
        if (!requireVerifiedSessionOrHalt()) return "NEED_AUTH";
        return TeleportEngine.performRamBoost(context);
    }

    @JavascriptInterface
    public void setHzThermalGuard(boolean enabled) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.setHzThermalGuard(enabled);
    }

    @JavascriptInterface
    public void applyShooterOptimizer(
            int fpsLock, int gpuBoost, int frameTime,
            int edgeSens, int multiTouch, int netBuffer, int thermalLim
    ) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.applyShooterOptimizer(fpsLock, gpuBoost, frameTime, edgeSens, multiTouch, netBuffer, thermalLim);
    }

    @JavascriptInterface
    public void setEnhancedRecoilFix(boolean enabled, int power) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.setEnhancedRecoilFix(enabled, power);
    }

    @JavascriptInterface
    public void setAutoGameDpi(boolean enabled, int dpi) {}

    @JavascriptInterface
    public void setPixelDownscaleFactor(int pct) {
        if (!requireVerifiedSessionOrHalt()) return;
        TeleportEngine.setPixelDownscaleFactor(pct);
    }

    @JavascriptInterface
    public String runSmartDeviceScan() {
        if (!requireVerifiedSessionOrHalt()) return "NEED_AUTH";
        return TeleportEngine.runSmartDeviceScan(context);
    }

    @JavascriptInterface
    public void applyUltraProTouchMatrix(int modeLevel) {
        if (!requireVerifiedSessionOrHalt()) return;
        ProfileManager.addTeleportCountToTotal(context, 1);
        TeleportEngine.startTeleport("extreme");
    }

    // Закрытие уязвимости #29: никаких локальных "status":"ok" при ошибке облачных конфигов
    @JavascriptInterface
    public String uploadCloudConfig(String title, String presetJson) {
        if (!requireVerifiedSessionOrHalt()) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте лицензионный ключ\"}";
        }
        try {
            Callable<String> task = () -> ApiManager.uploadCloudPreset(activeRuntimeKey, computeLiveHardwareId(), title, presetJson);
            Future<String> future = networkExecutor.submit(task);
            return future.get(4500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка связи с облачным сервером\"}";
        }
    }

    @JavascriptInterface
    public String downloadCloudConfig(String shareCode) {
        if (!requireVerifiedSessionOrHalt()) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте лицензионный ключ\"}";
        }
        try {
            Callable<String> task = () -> ApiManager.downloadCloudPresetByCode(activeRuntimeKey, computeLiveHardwareId(), shareCode);
            Future<String> future = networkExecutor.submit(task);
            return future.get(4500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Код сборки не найден или нет сети\"}";
        }
    }

    // =========================================================================
    // 9. КАСТОМНЫЙ ПРИЦЕЛ
    // =========================================================================
    @JavascriptInterface
    public void updateCrosshair(String type) {
        final String cleanType = (type == null) ? "off" : type.trim();
        if (!"off".equalsIgnoreCase(cleanType) && !requireVerifiedSessionOrHalt()) return;

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
        if (!requireVerifiedSessionOrHalt()) return;
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

    // =========================================================================
    // 10. АВТОРИЗАЦИЯ И ВЕРИФИКАЦИЯ ЛИЦЕНЗИИ (ЗАКРЫТИЕ УЯЗВИМОСТЕЙ #4, #5, #6, #25, #26)
    // =========================================================================
    @JavascriptInterface
    public String getSavedKey() {
        if (!sessionAlive.get()) return "";
        String encKey = readFromKeystoreVault(VAULT_KEY_LIC);
        if (encKey.isEmpty() || isBlacklistedCrackArtifact(encKey)) return "";
        return encKey.trim().toUpperCase(Locale.US);
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

    private void markKeyActivatedCryptographically(String nick, String cleanKey, JSONObject serverRes) {
        String hwid = computeLiveHardwareId();
        this.activeRuntimeKey = cleanKey;
        long tokenHash = computeRuntimeTokenHash(cleanKey, hwid);
        this.verifiedSessionTokenHash.set(tokenHash);
        this.lastVerifiedServerTs.set(SystemClock.elapsedRealtime());
        this.sessionAlive.set(true);

        writeToKeystoreVault(VAULT_KEY_LIC, cleanKey);
        writeToKeystoreVault(VAULT_KEY_TOKEN, String.valueOf(tokenHash));
        getOrInitKeyDeadlineMs(cleanKey, serverRes);

        ApiManager.setSessionVerified(true);
        ProfileManager.saveActivatedProfile(context, nick, cleanKey);
        SecurityShield.issueVerifiedSessionToken(context, cleanKey);
    }

    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (detectHostileEnvironment() || inspectHtmlIntegrityAndtampering()) {
            hardTerminateProcess("Security Tamper");
            return "ERROR|Обнаружена модификация приложения!";
        }
        if (key == null || key.trim().length() < 6 || isBlacklistedCrackArtifact(key)) {
            return "ERROR|Введите действительный лицензионный ключ из Telegram-бота";
        }
        final String cleanKey = key.trim().toUpperCase(Locale.US);
        final String hwid = computeLiveHardwareId();
        final String nick = getSavedNickname().isEmpty() ? "dare" : getSavedNickname();

        try {
            Callable<String> task = () -> ApiManager.activateLicense(cleanKey, hwid, nick);
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(6000, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            // Проверка серверной HMAC-подписи (если сервер прислал поле sig)
            String srvSig = res.optString("sig", "");
            if (!srvSig.isEmpty()) {
                String expectedSig = computeDailyHmac(cleanKey + "|" + hwid + "|" + status);
                if (!expectedSig.equalsIgnoreCase(srvSig)) {
                    hardTerminateProcess("Invalid Server HMAC");
                    return "ERROR|Подпись ответа сервера недействительна!";
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
                markKeyActivatedCryptographically(nick, cleanKey, res);
                String liveLabel = buildLiveSubscriptionLabel(res, cleanKey);
                if ("EXPIRED".equals(liveLabel)) {
                    forceKickExpiredUser("Срок действия этого ключа истёк!");
                    return "ERROR|Срок действия этого ключа истёк!";
                }
                return "OK|" + liveLabel;
            }

            return "ERROR|" + res.optString("message", "Ключ не найден, истёк или недействителен!");
        } catch (Exception e) {
            return "ERROR|Нет связи с сервером авторизации. Проверьте интернет!";
        }
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        if (detectHostileEnvironment() || inspectHtmlIntegrityAndtampering()) {
            hardTerminateProcess("Security Tamper");
            return "BANNED|Модификация файлов приложения";
        }

        final String cleanKey = (key != null && !key.trim().isEmpty())
                ? key.trim().toUpperCase(Locale.US)
                : readFromKeystoreVault(VAULT_KEY_LIC);

        if (cleanKey.length() < 6 || isBlacklistedCrackArtifact(cleanKey)) {
            forceKickExpiredUser("Введите лицензионный ключ");
            return "EXPIRED|Введите ключ";
        }

        final String hwid = computeLiveHardwareId();

        try {
            Callable<String> task = () -> ApiManager.verifyLicense(cleanKey, hwid);
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(5000, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            String srvSig = res.optString("sig", "");
            if (!srvSig.isEmpty()) {
                String expectedSig = computeDailyHmac(cleanKey + "|" + hwid + "|" + status);
                if (!expectedSig.equalsIgnoreCase(srvSig)) {
                    forceKickExpiredUser("Ошибка подписи сервера!");
                    return "BLOCKED|Ошибка подписи сервера";
                }
            }

            // Закрытие уязвимости #25: "offline" больше никогда не пропускает пользователя
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
                String nick = getSavedNickname().isEmpty() ? "dare" : getSavedNickname();
                markKeyActivatedCryptographically(nick, cleanKey, res);
                String label = buildLiveSubscriptionLabel(res, cleanKey);
                if ("EXPIRED".equals(label)) {
                    forceKickExpiredUser("Время вашей подписки истекло!");
                    return "EXPIRED|Время вашей подписки истекло!";
                }
                return "OK|" + label;
            }

            String msg = res.optString("message", "Срок действия ключа истёк!");
            forceKickExpiredUser(msg);
            return "EXPIRED|" + msg;
        } catch (Exception e) {
            // Закрытие уязвимости #26: при ошибке сети или перехвате трафика — принудительный кик без оффлайн-работы
            forceKickExpiredUser("Нет связи с сервером проверки лицензии!");
            return "EXPIRED|Требуется подключение к интернету";
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
        if (!requireVerifiedSessionOrHalt()) {
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
    // 11. ГОЛОСОВОЙ ЧАТ И УВЕДОМЛЕНИЯ
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
        if (!requireVerifiedSessionOrHalt()) return;
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

    // Нижнее ведро с яркой неоновой подсветкой при наведении кнопки «T»
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
