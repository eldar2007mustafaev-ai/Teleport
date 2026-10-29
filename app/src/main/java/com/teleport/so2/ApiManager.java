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

    private static final String[] API_ENDPOINTS = new String[]{
            "http://45.10.165.75:8080/api",
            "http://45.10.165.75:5000/api",
            "http://45.10.165.75:8000/api",
            "http://45.10.165.75/api"
    };

    public static final int CLIENT_BUILD_VERSION = 402;
    private static final String CLIENT_SALT = "SO2_TELEPORT_V402_HARD_SNAP_2026";

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
            JSONObject payload = new JSONObject();
            payload.put("action", "activate");
            payload.put("key", key);
            payload.put("android_id", androidId);
            payload.put("nickname", nickname);
            payload.put("build_version", CLIENT_BUILD_VERSION);
            payload.put("sign", sha256(key + ":" + androidId + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

            String raw = postWithFallback("/activate", payload.toString());
            JSONObject res = new JSONObject(raw);

            boolean ok = res.optBoolean("ok", false)
                    || "ok".equalsIgnoreCase(res.optString("status"))
                    || "active".equalsIgnoreCase(res.optString("status"));

            if (ok && !res.optBoolean("blocked", false)) {
                sessionVerified = true;
                JSONObject successOut = new JSONObject();
                successOut.put("status", "ok");
                successOut.put("ok", true);
                successOut.put("message", "Ключ успешно активирован!");
                return successOut.toString();
            }

            sessionVerified = false;
            return raw;
        } catch (Exception e) {
            sessionVerified = false;
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Нет связи с сервером 45.10.165.75. Проверьте интернет.\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        try {
            if (key == null || key.trim().length() < 4) {
                sessionVerified = false;
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Введите ваш лицензионный ключ\"}";
            }

            JSONObject payload = new JSONObject();
            payload.put("action", "verify");
            payload.put("key", key);
            payload.put("android_id", androidId);
            payload.put("build_version", CLIENT_BUILD_VERSION);
            payload.put("sign", sha256(key + ":" + androidId + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

            String raw = postWithFallback("/verify", payload.toString());
            JSONObject res = new JSONObject(raw);

            String status = res.optString("status", "");
            if ("expired".equalsIgnoreCase(status) || "blocked".equalsIgnoreCase(status) || res.optBoolean("blocked", false)) {
                sessionVerified = false;
                TeleportEngine.stopTeleport();
                return "{\"status\":\"blocked\",\"ok\":false,\"blocked\":true,\"message\":\"Ваш ключ сброшен или истёк. Введите новый ключ.\"}";
            }

            boolean ok = res.optBoolean("ok", false) || "ok".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status);
            sessionVerified = ok;
            if (!ok) {
                TeleportEngine.stopTeleport();
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Сессия сброшена сервером. Введите ключ заново.\"}";
            }
            return raw;
        } catch (Exception e) {
            sessionVerified = false;
            TeleportEngine.stopTeleport();
            return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Введите ключ для авторизации\"}";
        }
    }

    public static void saveCloudPreset(String key, String androidId, String presetJson) {
        if (!sessionVerified) return;
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("action", "save_preset");
                payload.put("key", key);
                payload.put("android_id", androidId);
                payload.put("build_version", CLIENT_BUILD_VERSION);
                payload.put("preset", presetJson);
                postWithFallback("/preset", payload.toString());
            } catch (Exception ignored) {}
        }).start();
    }

    public static String uploadCloudPreset(String key, String androidId, String title, String presetJson) {
        try {
            String hashPart = sha256(androidId + System.currentTimeMillis()).toUpperCase();
            String shareCode = hashPart.substring(0, 2) + "-" + hashPart.substring(2, 4) + "-" + hashPart.substring(4, 6);

            JSONObject payload = new JSONObject();
            payload.put("action", "upload_cloud_config");
            payload.put("key", key);
            payload.put("android_id", androidId);
            payload.put("share_code", shareCode);
            payload.put("title", title == null ? "РЕЗКИЙ ТЕЛЕПОРТ" : title);
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
            out.put("message", "Набор опубликован! Код: " + shareCode);
            return out.toString();
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка создания кода набора\"}";
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
            return "{\"status\":\"ok\",\"ok\":true,\"message\":\"Набор " + shareCode + " применён!\"}";
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
