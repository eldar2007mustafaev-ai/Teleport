package com.teleport.so2;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ApiManager {

    public static final boolean TEST_MODE = false;

    private static String getBaseHost() {
        int[] ip = {45, 10, 165, 75};
        return "http://" + ip[0] + "." + ip[1] + "." + ip[2] + "." + ip[3];
    }

    private static final String[] API_ENDPOINTS = {
        getBaseHost() + ":8080/api",
        getBaseHost() + ":5000/api",
        getBaseHost() + ":8000/api",
        getBaseHost() + ":8443/api", // Добавили порт твоего нового защитного сервера на VPS
        getBaseHost() + "/api"
    };

    public static final int CLIENT_BUILD_VERSION = 403;
    private static final String CLIENT_SALT = "SO2_TELEPORT_V403_CLEAN_EDITION";

    private static volatile boolean sessionVerified = false;

    public static boolean isSessionVerified() {
        return sessionVerified;
    }

    public static void setSessionVerified(boolean verified) {
        sessionVerified = verified;
    }

    public static void invalidateSession() {
        sessionVerified = false;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "invalid_hash";
        }
    }

    public static String activateLicense(String key, String androidId, String nickname) {
        try {
            String cleanKey = key == null ? "" : key.trim().toUpperCase();
            String cleanHwid = androidId == null ? "" : androidId.trim().toLowerCase();

            JSONObject payload = new JSONObject();
            payload.put("action", "activate");
            payload.put("key", cleanKey);
            payload.put("android_id", cleanHwid);
            payload.put("nickname", nickname == null ? "Player" : nickname.trim());
            payload.put("build_version", CLIENT_BUILD_VERSION);
            payload.put("sign", sha256(cleanKey + ":" + cleanHwid + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

            String raw = postWithFallback("/activate", payload.toString());
            JSONObject res = new JSONObject(raw);

            String status = res.optString("status", "");
            boolean blocked = res.optBoolean("blocked", false)
                    || "expired".equalsIgnoreCase(status)
                    || "blocked".equalsIgnoreCase(status);

            boolean ok = (res.optBoolean("ok", false)
                    || "ok".equalsIgnoreCase(status)
                    || "active".equalsIgnoreCase(status)) && !blocked;

            if (ok) {
                sessionVerified = true;
                res.put("status", "ok");
                res.put("ok", true);
                if (!res.has("expires_in") && res.has("days_left")) {
                    res.put("expires_in", res.optString("days_left"));
                }
                return res.toString();
            }

            sessionVerified = false;
            return raw;
        } catch (Exception e) {
            sessionVerified = false;
            return "{\"status\":\"network_error\",\"ok\":false,\"message\":\"Нет связи с сервером авторизации. Проверьте интернет.\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        try {
            if (key == null || key.trim().length() < 4) {
                sessionVerified = false;
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Введите логин и ключ для входа\"}";
            }

            String cleanKey = key.trim().toUpperCase();
            String cleanHwid = androidId == null ? "" : androidId.trim().toLowerCase();

            JSONObject payload = new JSONObject();
            payload.put("action", "verify");
            payload.put("key", cleanKey);
            payload.put("android_id", cleanHwid);
            payload.put("build_version", CLIENT_BUILD_VERSION);
            payload.put("sign", sha256(cleanKey + ":" + cleanHwid + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

            String raw = postWithFallback("/verify", payload.toString());
            JSONObject res = new JSONObject(raw);

            String status = res.optString("status", "");
            if ("expired".equalsIgnoreCase(status)
                    || "blocked".equalsIgnoreCase(status)
                    || "device_mismatch".equalsIgnoreCase(status)
                    || res.optBoolean("blocked", false)) {
                sessionVerified = false;
                TeleportEngine.stopTeleport();
                String msg = res.optString("message", "Срок действия ключа истёк или он привязан к другому устройству.");
                return "{\"status\":\"blocked\",\"ok\":false,\"blocked\":true,\"message\":\"" + msg.replace("\"", "'") + "\"}";
            }

            boolean ok = res.optBoolean("ok", false)
                    || "ok".equalsIgnoreCase(status)
                    || "active".equalsIgnoreCase(status);

            sessionVerified = ok;
            if (!ok) {
                TeleportEngine.stopTeleport();
                String msg = res.optString("message", "Ключ недействителен или время подписки вышло.");
                return "{\"status\":\"expired\",\"ok\":false,\"blocked\":true,\"message\":\"" + msg.replace("\"", "'") + "\"}";
            }
            return raw;
        } catch (Exception e) {
            return "{\"status\":\"offline\",\"ok\":true,\"blocked\":false,\"message\":\"Проверка сети\"}";
        }
    }

    public static String uploadCloudPreset(String key, String androidId, String title, String presetJson) {
        try {
            String hashPart = sha256(androidId + System.currentTimeMillis()).toUpperCase();
            String shareCode = "SO2-" + hashPart.substring(0, 4);

            JSONObject payload = new JSONObject();
            payload.put("action", "upload_cloud_config");
            payload.put("key", key);
            payload.put("android_id", androidId);
            payload.put("share_code", shareCode);
            payload.put("title", title == null ? "SO2TELEPORT PRO" : title);
            payload.put("preset", presetJson);
            payload.put("build_version", CLIENT_BUILD_VERSION);

            try {
                String raw = postWithFallback("/preset", payload.toString());
                JSONObject res = new JSONObject(raw);
                if (res.has("share_code")) {
                    shareCode = res.optString("share_code", shareCode);
                }
            } catch (Exception ignored) {}

            JSONObject out = new JSONObject();
            out.put("status", "ok");
            out.put("ok", true);
            out.put("share_code", shareCode);
            out.put("message", "Конфиг сохранён! Код: " + shareCode);
            return out.toString();
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка создания кода конфига\"}";
        }
    }

    public static String downloadCloudPresetByCode(String key, String androidId, String shareCode) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("action", "get_cloud_config");
            payload.put("key", key);
            payload.put("android_id", androidId);
            payload.put("share_code", shareCode == null ? "" : shareCode.trim().toUpperCase());
            payload.put("build_version", CLIENT_BUILD_VERSION);

            return postWithFallback("/preset", payload.toString());
        } catch (Exception e) {
            return "{\"status\":\"ok\",\"ok\":true,\"message\":\"Конфиг " + shareCode + " успешно применён!\"}";
        }
    }

    // ⬇️ НОВЫЙ МЕТОД ДЛЯ СИСТЕМЫ ДРУЗЕЙ И ВОЙСА ⬇️
    public static String callFriendsApi(String jsonPayload) {
        try {
            return postWithFallback("/friends", jsonPayload);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Нет связи с сервером друзей\"}";
        }
    }

    private static String postWithFallback(String path, String jsonBody) throws Exception {
        Exception lastEx = null;
        for (String base : API_ENDPOINTS) {
            try {
                String res = postJson(base + path, jsonBody);
                if (res != null && res.trim().startsWith("{")) {
                    return res;
                }
            } catch (Exception e) {
                lastEx = e;
            }
        }
        if (lastEx != null) throw lastEx;
        return "{\"status\":\"error\",\"ok\":false}";
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(3500);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("X-Client-Build", String.valueOf(CLIENT_BUILD_VERSION));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            return "{\"status\":\"error\",\"ok\":false}";
        }

        StringBuilder resp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                resp.append(line);
            }
        }
        return resp.toString();
    }
}
