package com.teleport.so2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AndroidBridge {
    private final MainActivity activity;
    private volatile boolean licenseVerified = false;

    // Список адресов твоего сервера с bot.py (порт 8080)
    private static final String[] BOT_SERVERS = new String[]{
            "http://45.10.165.75:8080",
            "http://188.191.20.243:8080"
    };

    public AndroidBridge(MainActivity activity) {
        this.activity = activity;
        this.licenseVerified = false;
    }

    /**
     * Постоянный системный android_id (не меняется при обновлении APK)
     */
    @JavascriptInterface
    public String getDeviceHwid() {
        try {
            String androidId = Settings.Secure.getString(
                    activity.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            if (androidId != null && !androidId.trim().isEmpty()) {
                return androidId.trim().toLowerCase();
            }
        } catch (Exception ignored) {}
        return ("android_" + Build.BOARD + "_" + Build.MODEL).replaceAll("\\s+", "").toLowerCase();
    }

    @JavascriptInterface
    public String getSavedKey() {
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        return prefs.getString("saved_license_key", "");
    }

    @JavascriptInterface
    public void openTelegramBot() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SO2TeleportShop_bot"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ignored) {}
    }

    /**
     * Активация нового или существующего ключа через POST /api/activate в bot.py
     */
    @JavascriptInterface
    public String activateLicenseKey(String rawKey) {
        if (rawKey == null || rawKey.trim().length() < 5) {
            clearVerifiedKey();
            return "ERROR|Введите корректный ключ из @SO2TeleportShop_bot";
        }

        String key = rawKey.trim().toUpperCase();
        String androidId = getDeviceHwid();
        String nickname = Build.MODEL != null ? Build.MODEL : "Player";

        String respJson = postToBot("/api/activate", key, androidId, nickname);
        if (respJson == null) {
            return "SERVER_ERROR|Нет связи с сервером бота (порт 8080). Проверьте интернет!";
        }

        return handleBotJsonResponse(key, respJson);
    }

    /**
     * Проверка активного ключа и его срока через POST /api/verify в bot.py
     */
    @JavascriptInterface
    public String verifyLicenseKey(String rawKey) {
        String key = (rawKey != null && !rawKey.trim().isEmpty())
                ? rawKey.trim().toUpperCase()
                : getSavedKey();

        if (key == null || key.length() < 5) {
            clearVerifiedKey();
            return "NEED_AUTH|Введите лицензионный ключ для входа";
        }

        String androidId = getDeviceHwid();
        String nickname = Build.MODEL != null ? Build.MODEL : "Player";

        String respJson = postToBot("/api/verify", key, androidId, nickname);
        if (respJson == null) {
            // Если кратковременно пропал интернет, проверяем сохранённый локально timestamp истечения
            SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
            long savedExpSec = prefs.getLong("license_expires_at_sec", 0L);
            long nowSec = System.currentTimeMillis() / 1000L;
            if (key.equals(getSavedKey()) && savedExpSec > nowSec) {
                this.licenseVerified = true;
                return "OK|" + formatRemainingTime(savedExpSec - nowSec);
            }
            return "SERVER_ERROR|Нет связи с сервером проверки ключей";
        }

        // Если ключ ещё ни разу не был активирован (status == unused), пробуем активировать
        if (respJson.contains("need_auth")) {
            String actJson = postToBot("/api/activate", key, androidId, nickname);
            if (actJson != null) {
                return handleBotJsonResponse(key, actJson);
            }
        }

        return handleBotJsonResponse(key, respJson);
    }

    private String handleBotJsonResponse(String key, String respJson) {
        try {
            JSONObject json = new JSONObject(respJson);
            String status = json.optString("status", "");
            boolean ok = json.optBoolean("ok", false);
            boolean blocked = json.optBoolean("blocked", false);
            String message = json.optString("message", "");

            long expiresAtSec = json.optLong("expires_at", 0L);
            if (expiresAtSec > 10000000000L) {
                expiresAtSec = expiresAtSec / 1000L;
            }
            long nowSec = System.currentTimeMillis() / 1000L;

            if (blocked || !ok || !"ok".equalsIgnoreCase(status)) {
                clearVerifiedKey();
                if (expiresAtSec > 0L && expiresAtSec < nowSec) {
                    return "EXPIRED|Срок действия вашего ключа истёк! Купите новый в @SO2TeleportShop_bot";
                }
                if ("blocked".equalsIgnoreCase(status)) {
                    return "BLOCKED|" + (message.isEmpty()
                            ? "Срок действия ключа истёк или он привязан к другому устройству!"
                            : message);
                }
                return "ERROR|" + (message.isEmpty()
                        ? "Ключ не найден, недействителен или привязан к другому телефону!"
                        : message);
            }

            if (expiresAtSec > 0L && expiresAtSec <= nowSec) {
                clearVerifiedKey();
                return "EXPIRED|Срок действия ключа истёк! Продлите подписку в @SO2TeleportShop_bot";
            }

            saveVerifiedKey(key, expiresAtSec);
            long leftSec = expiresAtSec > nowSec ? (expiresAtSec - nowSec) : 0L;
            return "OK|" + formatRemainingTime(leftSec);

        } catch (Exception e) {
            clearVerifiedKey();
            return "ERROR|Ошибка чтения ответа сервера";
        }
    }

    private String formatRemainingTime(long leftSec) {
        if (leftSec <= 0L) return "Активен";
        long days = leftSec / 86400L;
        long hours = (leftSec % 86400L) / 3600L;
        long mins = (leftSec % 3600L) / 60L;
        if (days > 0) {
            return days + " дн. " + hours + " ч.";
        } else if (hours > 0) {
            return hours + " ч. " + mins + " мин.";
        } else {
            return mins + " мин.";
        }
    }

    private String postToBot(String path, String key, String androidId, String nickname) {
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        String customHost = prefs.getString("custom_bot_host", "");

        String[] candidates = customHost.isEmpty()
                ? BOT_SERVERS
                : new String[]{customHost, BOT_SERVERS[0], BOT_SERVERS[1]};

        for (String base : candidates) {
            try {
                URL url = new URL(base + path);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                // Обязательный заголовок для прохождения _hard_block_aiohttp_v400 в bot.py
                conn.setRequestProperty("X-Client-Build", "400");
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("key", key);
                payload.put("android_id", androidId);
                payload.put("nickname", nickname);
                payload.put("build_version", 400);

                byte[] bytes = payload.toString().getBytes("UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                String body = readStream(is);
                if (body != null && !body.trim().isEmpty()) {
                    return body;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void saveVerifiedKey(String key, long expiresAtSec) {
        this.licenseVerified = true;
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString("saved_license_key", key);
        if (expiresAtSec > 0L) {
            ed.putLong("license_expires_at_sec", expiresAtSec);
        }
        ed.apply();
    }

    @JavascriptInterface
    public void logoutLicenseKey() {
        clearVerifiedKey();
    }

    private void clearVerifiedKey() {
        this.licenseVerified = false;
        TeleportEngine.stopTeleport();
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        prefs.edit()
                .remove("saved_license_key")
                .remove("license_expires_at_sec")
                .apply();
    }

    private String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean isShizukuReady() {
        return ShizukuManager.isPermissionGranted();
    }

    @JavascriptInterface
    public boolean isEngineActive() {
        return TeleportEngine.isActive();
    }

    @JavascriptInterface
    public void startTeleport(String presetId) {
        if (!licenseVerified) return;
        TeleportEngine.startTeleport(activity, presetId);
    }

    @JavascriptInterface
    public void stopTeleport() {
        TeleportEngine.stopTeleport();
    }

    @JavascriptInterface
    public void updateParameters(
            float sharp, float sx, float sy,
            int ch, int pktHz, float straight,
            int stick, int grid, int pause, float ac
    ) {
        if (!licenseVerified) return;
        TeleportEngine.updateParameters(sharp, sx, sy, ch, pktHz, straight, stick, grid, pause, ac);
    }

    @JavascriptInterface
    public void setSmartAwpLock(boolean enabled) {
        if (!licenseVerified) return;
        TeleportEngine.setSmartAwpLock(enabled);
    }

    @JavascriptInterface
    public void setAntiRecoilQuantization(boolean enabled, int strength) {
        if (!licenseVerified) return;
        TeleportEngine.setAntiRecoilQuantization(enabled, strength);
    }

    @JavascriptInterface
    public void setHzThermalGuard(boolean enabled) {
        if (!licenseVerified) return;
        TeleportEngine.setHzThermalGuard(enabled);
    }

    @JavascriptInterface
    public void setPixelDownscaleFactor(int percent) {
        if (!licenseVerified) return;
        TeleportEngine.setPixelDownscaleFactor(percent);
    }

    @JavascriptInterface
    public void applyResolutionRatio(String ratio) {
        if (!licenseVerified) return;
        activity.applyResolutionRatio(ratio);
    }

    @JavascriptInterface
    public void resetResolution() {
        activity.resetResolution();
    }

    @JavascriptInterface
    public void toggleFloatingButton(boolean enable) {
        if (!licenseVerified) return;
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("float_btn_enabled", enable).apply();
        activity.toggleFloatingButton(enable);
    }

    @JavascriptInterface
    public void updateCrosshair(String type) {
        if (!licenseVerified) return;
        activity.updateCrosshair(type);
    }

    @JavascriptInterface
    public void updateCrosshairStyle(int scale, int opacity, String colorHex) {
        if (!licenseVerified) return;
        activity.updateCrosshairStyle(scale, opacity, colorHex);
    }

    @JavascriptInterface
    public void updateOverlayStealthFlag(boolean enabled) {
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("stealth_overlays", enabled).apply();
        activity.updateOverlayStealthFlag(enabled);
    }

    @JavascriptInterface
    public void setQuantumOverlayStrobe(boolean enabled) {
        activity.setQuantumOverlayStrobe(enabled);
    }

    @JavascriptInterface
    public void healTouch() {
        activity.healBrokenTouchTimeouts();
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        int longPx = Math.max(dm.widthPixels, dm.heightPixels);
        return Build.MODEL + "|" + Build.HARDWARE + "|" + shortPx + "x" + longPx + "|" + dm.densityDpi;
    }
}
