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

    // Обязательное поле для совместимости с ProfileManager.java (исправляет ошибку сборки)
    public static final boolean TEST_MODE = false;

    // Твой IP сервера из Termius (45.10.165.75) с авто-проверкой стандартных портов API
    private static final String[] API_ENDPOINTS = new String[]{
            "http://45.10.165.75:8080/api",
            "http://45.10.165.75:5000/api",
            "http://45.10.165.75:8000/api",
            "http://45.10.165.75/api"
    };

    // Номер актуальной сборки. Все версии ниже 400 блокируются сервером!
    public static final int CLIENT_BUILD_VERSION = 400;
    private static final String CLIENT_SALT = "SO2_SKY_V400_PRIVATE_SALT_2026";

    private static volatile boolean sessionVerified = false;

    public static boolean isSessionVerified() {
        return sessionVerified;
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

            if (res.optInt("min_build", CLIENT_BUILD_VERSION) > CLIENT_BUILD_VERSION
                    || res.optBoolean("deprecated", false)
                    || "deprecated".equalsIgnoreCase(res.optString("status"))) {
                sessionVerified = false;
                return "{\"status\":\"deprecated\",\"ok\":false,\"blocked\":true,\"message\":\"⚠️ Ваша версия устарела! Скачайте обновление в Telegram.\"}";
            }

            boolean ok = res.optBoolean("ok", false) || "ok".equalsIgnoreCase(res.optString("status"));
            sessionVerified = ok && !res.optBoolean("blocked", false);
            return raw;
        } catch (Exception e) {
            sessionVerified = false;
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Нет связи с сервером лицензий 45.10.165.75\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        try {
            if (key == null || key.trim().length() < 6) {
                sessionVerified = false;
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false}";
            }

            JSONObject payload = new JSONObject();
            payload.put("action", "verify");
            payload.put("key", key);
            payload.put("android_id", androidId);
            payload.put("build_version", CLIENT_BUILD_VERSION);
            payload.put("sign", sha256(key + ":" + androidId + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

            String raw = postWithFallback("/verify", payload.toString());
            JSONObject res = new JSONObject(raw);

            int serverMinBuild = res.optInt("min_build", CLIENT_BUILD_VERSION);
            String status = res.optString("status", "");

            if (serverMinBuild > CLIENT_BUILD_VERSION
                    || res.optBoolean("deprecated", false)
                    || "deprecated".equalsIgnoreCase(status)) {
                sessionVerified = false;
                TeleportEngine.stopTeleport();
                return "{\"status\":\"deprecated\",\"ok\":false,\"blocked\":true,\"message\":\"⚠️ Эта версия приложения устарела и отключена!\"}";
            }

            if ("expired".equalsIgnoreCase(status) || "blocked".equalsIgnoreCase(status) || res.optBoolean("blocked", false)) {
                sessionVerified = false;
                TeleportEngine.stopTeleport();
                return raw;
            }

            boolean ok = res.optBoolean("ok", false) || "ok".equalsIgnoreCase(status);
            sessionVerified = ok;
            if (!ok) {
                TeleportEngine.stopTeleport();
            }
            return raw;
        } catch (Exception e) {
            sessionVerified = false;
            TeleportEngine.stopTeleport();
            return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Включите интернет для проверки ключа\"}";
        }
    }

    public static void saveCloudPreset(String key, String androidId, String presetJson) {
        if (!sessionVerified) return;
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("key", key);
                payload.put("android_id", androidId);
                payload.put("build_version", CLIENT_BUILD_VERSION);
                payload.put("preset", presetJson);
                postWithFallback("/preset", payload.toString());
            } catch (Exception ignored) {}
        }).start();
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
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
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
