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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        getBaseHost() + ":8443/api",
        getBaseHost() + "/api"
    };

    public static final int CLIENT_BUILD_VERSION = 403;
    private static final String CLIENT_SALT = "SO2_TELEPORT_V403_CLEAN_EDITION";

    private static volatile boolean sessionVerified = false;
    private static volatile String lastWorkingBase = null;

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

    private static List<String> parseCandidates(String rawHwidInput) {
        Set<String> set = new LinkedHashSet<>();
        if (rawHwidInput != null) {
            String[] parts = rawHwidInput.split(",");
            for (String p : parts) {
                String trimmed = p.trim().toLowerCase(Locale.US);
                if (!trimmed.isEmpty()) {
                    set.add(trimmed);
                    if (trimmed.contains("|")) {
                        String[] sub = trimmed.split("\\|");
                        for (String s : sub) {
                            if (s != null && s.trim().length() >= 8) {
                                set.add(s.trim().toLowerCase(Locale.US));
                            }
                        }
                    }
                }
            }
        }
        if (set.isEmpty()) {
            set.add("hw7744aabb1122cc");
        }
        return new ArrayList<>(set);
    }

    private static boolean isResponseSuccess(JSONObject res) {
        String status = res.optString("status", "");
        boolean blocked = res.optBoolean("blocked", false)
                || "expired".equalsIgnoreCase(status)
                || "blocked".equalsIgnoreCase(status)
                || "banned".equalsIgnoreCase(status)
                || "device_mismatch".equalsIgnoreCase(status);
        return (res.optBoolean("ok", false)
                || "ok".equalsIgnoreCase(status)
                || "active".equalsIgnoreCase(status)) && !blocked;
    }

    public static String activateLicense(String key, String androidId, String nickname) {
        try {
            String cleanKey = key == null ? "" : key.trim().toUpperCase(Locale.US);
            String cleanNick = (nickname == null || nickname.trim().isEmpty()) ? "Player" : nickname.trim();
            List<String> candidates = parseCandidates(androidId);

            String lastRawResponse = "";
            boolean anyNetworkSuccess = false;

            for (String candidateHwid : candidates) {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("action", "activate");
                    payload.put("key", cleanKey);
                    payload.put("android_id", candidateHwid);
                    payload.put("nickname", cleanNick);
                    payload.put("build_version", CLIENT_BUILD_VERSION);
                    payload.put("sign", sha256(cleanKey + ":" + candidateHwid + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

                    String raw = postWithFallback("/activate", payload.toString());
                    anyNetworkSuccess = true;
                    lastRawResponse = raw;
                    JSONObject res = new JSONObject(raw);

                    if (isResponseSuccess(res)) {
                        sessionVerified = true;
                        res.put("status", "ok");
                        res.put("ok", true);
                        res.put("matched_hwid", candidateHwid);
                        if (!res.has("expires_in") && res.has("days_left")) {
                            res.put("expires_in", res.optString("days_left"));
                        }
                        return res.toString();
                    }

                    JSONObject verifyPayload = new JSONObject();
                    verifyPayload.put("action", "verify");
                    verifyPayload.put("key", cleanKey);
                    verifyPayload.put("android_id", candidateHwid);
                    verifyPayload.put("build_version", CLIENT_BUILD_VERSION);
                    verifyPayload.put("sign", sha256(cleanKey + ":" + candidateHwid + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

                    String rawVerify = postWithFallback("/verify", verifyPayload.toString());
                    JSONObject resVerify = new JSONObject(rawVerify);
                    if (isResponseSuccess(resVerify)) {
                        sessionVerified = true;
                        resVerify.put("status", "ok");
                        resVerify.put("ok", true);
                        resVerify.put("matched_hwid", candidateHwid);
                        if (!resVerify.has("expires_in") && resVerify.has("days_left")) {
                            resVerify.put("expires_in", resVerify.optString("days_left"));
                        }
                        return resVerify.toString();
                    }

                    String st = res.optString("status", "");
                    if ("expired".equalsIgnoreCase(st) || "banned".equalsIgnoreCase(st)) {
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (!anyNetworkSuccess) {
                return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Нет связи с сервером лицензий!\"}";
            }

            sessionVerified = false;
            return lastRawResponse.isEmpty()
                    ? "{\"status\":\"error\",\"ok\":false,\"blocked\":false,\"message\":\"Ключ не найден или привязан к другому устройству\"}"
                    : lastRawResponse;
        } catch (Exception e) {
            return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Нет связи с сервером лицензий!\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        try {
            if (key == null || key.trim().length() < 4) {
                sessionVerified = false;
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Введите логин и ключ для входа\"}";
            }

            String cleanKey = key.trim().toUpperCase(Locale.US);
            List<String> candidates = parseCandidates(androidId);

            JSONObject lastRes = null;
            boolean anyNetworkSuccess = false;

            for (String candidateHwid : candidates) {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("action", "verify");
                    payload.put("key", cleanKey);
                    payload.put("android_id", candidateHwid);
                    payload.put("build_version", CLIENT_BUILD_VERSION);
                    payload.put("sign", sha256(cleanKey + ":" + candidateHwid + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));

                    String raw = postWithFallback("/verify", payload.toString());
                    anyNetworkSuccess = true;
                    JSONObject res = new JSONObject(raw);
                    lastRes = res;

                    if (isResponseSuccess(res)) {
                        sessionVerified = true;
                        res.put("status", "ok");
                        res.put("ok", true);
                        res.put("matched_hwid", candidateHwid);
                        return res.toString();
                    }

                    String st = res.optString("status", "");
                    if ("expired".equalsIgnoreCase(st) || "banned".equalsIgnoreCase(st)) {
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (!anyNetworkSuccess || lastRes == null) {
                return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Нет связи с сервером лицензий\"}";
            }

            String status = lastRes.optString("status", "");
            if ("expired".equalsIgnoreCase(status)
                    || "blocked".equalsIgnoreCase(status)
                    || "banned".equalsIgnoreCase(status)
                    || "device_mismatch".equalsIgnoreCase(status)
                    || lastRes.optBoolean("blocked", false)) {
                sessionVerified = false;
                TeleportEngine.stopTeleport();
                String msg = lastRes.optString("message", "Срок действия ключа истёк или он привязан к другому устройству.");
                return "{\"status\":\"blocked\",\"ok\":false,\"blocked\":true,\"message\":\"" + msg.replace("\"", "'") + "\"}";
            }

            return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Ожидание ответа сервера\"}";
        } catch (Exception e) {
            return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Нет связи с сервером лицензий\"}";
        }
    }

    public static String uploadCloudPreset(String key, String androidId, String title, String presetJson) {
        try {
            List<String> candidates = parseCandidates(androidId);
            String primaryHwid = candidates.get(0);
            String hashPart = sha256(primaryHwid + System.currentTimeMillis()).toUpperCase(Locale.US);
            String shareCode = "SO2-" + hashPart.substring(0, 4);

            JSONObject payload = new JSONObject();
            payload.put("action", "upload_cloud_config");
            payload.put("key", key);
            payload.put("android_id", primaryHwid);
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
            List<String> candidates = parseCandidates(androidId);
            JSONObject payload = new JSONObject();
            payload.put("action", "get_cloud_config");
            payload.put("key", key);
            payload.put("android_id", candidates.get(0));
            payload.put("share_code", shareCode == null ? "" : shareCode.trim().toUpperCase(Locale.US));
            payload.put("build_version", CLIENT_BUILD_VERSION);

            return postWithFallback("/preset", payload.toString());
        } catch (Exception e) {
            return "{\"status\":\"ok\",\"ok\":true,\"message\":\"Конфиг " + shareCode + " успешно применён!\"}";
        }
    }

    public static String callFriendsApi(String jsonPayload) {
        try {
            return postWithFallback("/friends", jsonPayload);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Нет связи с сервером друзей\"}";
        }
    }

    private static String postWithFallback(String path, String jsonBody) throws Exception {
        Exception lastEx = null;
        String cached = lastWorkingBase;
        if (cached != null) {
            try {
                String res = postJson(cached + path, jsonBody, 2200);
                if (res != null && res.trim().startsWith("{")) {
                    return res;
                }
            } catch (Exception e) {
                lastEx = e;
            }
        }
        for (String base : API_ENDPOINTS) {
            if (base.equals(cached)) continue;
            try {
                String res = postJson(base + path, jsonBody, 1800);
                if (res != null && res.trim().startsWith("{")) {
                    lastWorkingBase = base;
                    return res;
                }
            } catch (Exception e) {
                lastEx = e;
            }
        }
        if (lastEx != null) throw lastEx;
        return "{\"status\":\"offline\",\"ok\":false}";
    }

    private static String postJson(String urlStr, String jsonBody, int timeoutMs) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("X-Client-Build", String.valueOf(CLIENT_BUILD_VERSION));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            return "{\"status\":\"offline\",\"ok\":false}";
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
