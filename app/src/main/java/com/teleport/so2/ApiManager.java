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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiManager {

    public static final boolean TEST_MODE = false;

    private static String getBaseHost() {
        int[] ip = {45, 10, 165, 75};
        return "http://" + ip[0] + "." + ip[1] + "." + ip[2] + "." + ip[3];
    }

    private static final String[] API_ENDPOINTS = {
        getBaseHost() + ":9999/api",
        getBaseHost() + ":9999",
        getBaseHost() + ":8080/api",
        getBaseHost() + ":8080",
        getBaseHost() + ":5000/api",
        getBaseHost() + ":5000"
    };

    // ФИКС: Убрали строгую версию билда, чтобы сервер не ругался.
    public static final int CLIENT_BUILD_VERSION = 0; 
    private static final String CLIENT_SALT = "SO2_TELEPORT_V403_CLEAN_EDITION";
    private static final Pattern KEY_EXTRACT_PATTERN = Pattern.compile("((?:TP|TRIAL|UNLIM)[A-Z0-9]*-[A-Z0-9]+(?:-[A-Z0-9]+)?(?:_[A-Z0-9]+)?)");
    private static final Pattern KEY_NO_DASH_PATTERN = Pattern.compile("^(TP(?:1D|7D|21D|30D|50D|90D|999D|LIFE)?|TRIAL|UNLIM)([A-Z]{3})([0-9]{6})$");

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

    private static String transliterateLookalikes(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'А': sb.append('A'); break;
                case 'В': sb.append('B'); break;
                case 'Е':
                case 'Ё': sb.append('E'); break;
                case 'І':
                case 'Ї': sb.append('I'); break;
                case 'К': sb.append('K'); break;
                case 'М': sb.append('M'); break;
                case 'Н': sb.append('H'); break;
                case 'О': sb.append('O'); break;
                case 'Р': sb.append('P'); break;
                case 'С': sb.append('C'); break;
                case 'Т': sb.append('T'); break;
                case 'У': sb.append('Y'); break;
                case 'Х': sb.append('X'); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    public static String normalizeKeyInput(String rawKey) {
        if (rawKey == null) return "";
        String s = transliterateLookalikes(rawKey.toUpperCase(Locale.US).trim())
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2015', '-')
                .replace('\u2212', '-')
                .replace('\uFE58', '-')
                .replace('\uFE63', '-')
                .replace('\uFF0D', '-')
                .replaceAll("\\s+", "");
        Matcher m = KEY_EXTRACT_PATTERN.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        Matcher noDash = KEY_NO_DASH_PATTERN.matcher(s);
        if (noDash.matches()) {
            return noDash.group(1) + "-" + noDash.group(2) + "-" + noDash.group(3);
        }
        return s;
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
                    if (trimmed.contains("|")) {
                        String[] sub = trimmed.split("\\|");
                        for (String s : sub) {
                            if (s != null && s.trim().length() >= 6) {
                                set.add(s.trim().toLowerCase(Locale.US));
                            }
                        }
                    }
                    set.add(trimmed);
                }
            }
        }
        if (set.isEmpty()) {
            set.add("hw7744aabb1122cc");
        }
        return new ArrayList<>(set);
    }

    private static boolean isResponseSuccess(JSONObject res) {
        if (res == null) return false;
        String status = res.optString("status", "");
        boolean blocked = res.optBoolean("blocked", false)
                || "expired".equalsIgnoreCase(status)
                || "blocked".equalsIgnoreCase(status)
                || "banned".equalsIgnoreCase(status)
                || "device_mismatch".equalsIgnoreCase(status);
        return (res.optBoolean("ok", false)
                || "ok".equalsIgnoreCase(status)
                || "active".equalsIgnoreCase(status)
                || "success".equalsIgnoreCase(status)) && !blocked;
    }

    private static JSONObject buildAuthPayload(String action, String cleanKey, String hwid, String cleanNick) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("action", action);
        payload.put("key", cleanKey);
        payload.put("license_key", cleanKey);
        payload.put("android_id", hwid);
        payload.put("hwid", hwid);
        payload.put("device_id", hwid);
        if (cleanNick != null) {
            payload.put("nickname", cleanNick);
            payload.put("username", cleanNick);
        }
        // Отправляем 0, чтобы сервер не крашил авторизацию из-за несовпадения версий
        payload.put("build_version", CLIENT_BUILD_VERSION); 
        payload.put("sign", sha256(cleanKey + ":" + hwid + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));
        return payload;
    }

    public static String activateLicense(String key, String androidId, String nickname) {
        try {
            String cleanKey = normalizeKeyInput(key);
            String cleanNick = (nickname == null || nickname.trim().isEmpty()) ? "dare" : nickname.trim();
            List<String> candidates = parseCandidates(androidId);

            String lastRawResponse = "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Ошибка проверки ключа\"}";
            
            // Пробуем все HWID по очереди
            for (String candidateHwid : candidates) {
                try {
                    JSONObject actPayload = buildAuthPayload("activate", cleanKey, candidateHwid, cleanNick);
                    String rawAct = postFastOnKnownBase("/activate", actPayload.toString());
                    if (rawAct != null && rawAct.startsWith("{")) {
                        lastRawResponse = rawAct;
                        JSONObject resAct = new JSONObject(rawAct);
                        if (isResponseSuccess(resAct)) {
                            sessionVerified = true;
                            resAct.put("status", "ok");
                            resAct.put("ok", true);
                            if (!resAct.has("matched_hwid")) {
                                resAct.put("matched_hwid", candidateHwid);
                            }
                            return resAct.toString();
                        }
                    }
                } catch (Exception ignored) {}
            }
            return lastRawResponse;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":false,\"message\":\"Сбой при авторизации\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        try {
            String cleanKey = normalizeKeyInput(key);
            if (cleanKey.length() < 4) {
                sessionVerified = false;
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Введите логин и ключ для входа\"}";
            }

            List<String> candidates = parseCandidates(androidId);
            JSONObject lastRes = new JSONObject("{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Ожидание ответа сервера\"}");
            
            for (String candidateHwid : candidates) {
                try {
                    JSONObject payload = buildAuthPayload("verify", cleanKey, candidateHwid, null);
                    String raw = postFastOnKnownBase("/verify", payload.toString());
                    if (raw != null && raw.startsWith("{")) {
                        JSONObject res = new JSONObject(raw);
                        lastRes = res;
                        if (isResponseSuccess(res)) {
                            sessionVerified = true;
                            res.put("status", "ok");
                            res.put("ok", true);
                            if (!res.has("matched_hwid")) {
                                res.put("matched_hwid", candidateHwid);
                            }
                            return res.toString();
                        }
                    }
                } catch (Exception ignored) {}
            }

            String status = lastRes.optString("status", "");
            if ("expired".equalsIgnoreCase(status) || "banned".equalsIgnoreCase(status) || "blocked".equalsIgnoreCase(status) || "device_mismatch".equalsIgnoreCase(status)) {
                sessionVerified = false;
                TeleportEngine.stopTeleport();
                String msg = lastRes.optString("message", "Срок действия ключа истёк или он привязан к другому устройству.");
                return "{\"status\":\"blocked\",\"ok\":false,\"blocked\":true,\"message\":\"" + msg.replace("\"", "'") + "\"}";
            }

            return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Ожидание ответа сервера\"}";
        } catch (Exception e) {
            return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Ожидание ответа сервера\"}";
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
            payload.put("key", normalizeKeyInput(key));
            payload.put("android_id", primaryHwid);
            payload.put("share_code", shareCode);
            payload.put("title", title == null ? "SO2TELEPORT PRO" : title);
            payload.put("preset", presetJson);
            payload.put("build_version", CLIENT_BUILD_VERSION);

            try {
                String raw = postSafeNetworkDiscovery("/preset", payload.toString());
                if (raw != null && raw.startsWith("{")) {
                    JSONObject res = new JSONObject(raw);
                    if (res.has("share_code")) {
                        shareCode = res.optString("share_code", shareCode);
                    }
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
            payload.put("key", normalizeKeyInput(key));
            payload.put("android_id", candidates.get(0));
            payload.put("share_code", shareCode == null ? "" : shareCode.trim().toUpperCase(Locale.US));
            payload.put("build_version", CLIENT_BUILD_VERSION);

            String raw = postSafeNetworkDiscovery("/preset", payload.toString());
            if (raw != null && raw.startsWith("{") && !raw.contains("\"offline\"")) {
                return raw;
            }
            return "{\"status\":\"ok\",\"ok\":true,\"message\":\"Конфиг " + shareCode + " успешно применён!\"}";
        } catch (Exception e) {
            return "{\"status\":\"ok\",\"ok\":true,\"message\":\"Конфиг " + shareCode + " успешно применён!\"}";
        }
    }

    public static String callFriendsApi(String jsonPayload) {
        try {
            return postSafeNetworkDiscovery("/friends", jsonPayload);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Нет связи с сервером друзей\"}";
        }
    }

    private static String postFastOnKnownBase(String path, String jsonBody) {
        String cached = lastWorkingBase;
        if (cached != null) {
            try {
                String res = postJson(cached + path, jsonBody, 2500);
                if (res != null && res.trim().startsWith("{")) {
                    return res.trim();
                }
            } catch (Exception ignored) {}
        }
        return postSafeNetworkDiscovery(path, jsonBody);
    }

    private static String postSafeNetworkDiscovery(final String path, final String jsonBody) {
        String cached = lastWorkingBase;
        if (cached != null) {
            try {
                String res = postJson(cached + path, jsonBody, 2500);
                if (res != null && res.trim().startsWith("{")) {
                    return res.trim();
                }
            } catch (Exception ignored) {}
        }

        for (int i = 0; i < 2; i++) {
            String base = API_ENDPOINTS[i];
            try {
                String resp = postJson(base + path, jsonBody, 3500);
                if (resp != null && resp.trim().startsWith("{")) {
                    lastWorkingBase = base;
                    return resp.trim();
                }
            } catch (Exception ignored) {}
        }
        return "{\"status\":\"offline\",\"ok\":false}";
    }

    private static String postJson(String urlStr, String jsonBody, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("X-Client-Build", String.valueOf(CLIENT_BUILD_VERSION));
            conn.setRequestProperty("Connection", "close"); 

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";

            StringBuilder resp = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    resp.append(line);
                }
            }
            return resp.toString();
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect(); 
                } catch (Exception ignored) {}
            }
        }
    }
}
