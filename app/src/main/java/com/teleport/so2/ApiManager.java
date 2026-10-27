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

    // Укажи здесь свой рабочий URL сервера/бота проверки лицензий
    private static final String API_BASE_URL = "https://your-teleport-server.com/api";

    // Поднимай это число с каждым новым билдом, если хочешь отрубить предыдущие версии
    public static final int CLIENT_BUILD_VERSION = 400;
    private static final String CLIENT_SALT = "SO2_SKY_V400_PRIVATE_SALT_2026";

    // Флаг железной блокировки: пока он false, TeleportEngine не выполнит ни одной команды
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

            String raw = postJson(API_BASE_URL + "/activate", payload.toString());
            JSONObject res = new JSONObject(raw);

            if (res.optInt("min_build", CLIENT_BUILD_VERSION) > CLIENT_BUILD_VERSION
                    || "deprecated".equalsIgnoreCase(res.optString("status"))) {
                sessionVerified = false;
                return "{\"status\":\"deprecated\",\"ok\":false,\"blocked\":true,\"message\":\"Версия устарела! Скачайте обновление в Telegram.\"}";
            }

            boolean ok = res.optBoolean("ok", false) || "ok".equalsIgnoreCase(res.optString("status"));
            sessionVerified = ok && !res.optBoolean("blocked", false);
            return raw;
        } catch (Exception e) {
            sessionVerified = false;
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Нет связи с сервером лицензий\"}";
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

            String raw = postJson(API_BASE_URL + "/verify", payload.toString());
            JSONObject res = new JSONObject(raw);

            // Если сервер сообщает, что минимальная поддерживаемая сборка выше текущей -> рубим доступ
            int serverMinBuild = res.optInt("min_build", CLIENT_BUILD_VERSION);
            String status = res.optString("status", "");

            if (serverMinBuild > CLIENT_BUILD_VERSION || "deprecated".equalsIgnoreCase(status)) {
                sessionVerified = false;
                TeleportEngine.stopTeleport();
                return "{\"status\":\"deprecated\",\"ok\":false,\"blocked\":true,\"message\":\"Эта версия больше не поддерживается!\"}";
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
            // При отсутствии ответа сервера не даём запустить в обход проверки
            sessionVerified = false;
            return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false}";
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
                postJson(API_BASE_URL + "/preset", payload.toString());
            } catch (Exception ignored) {}
        }).start();
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(6500);
        conn.setReadTimeout(6500);
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
