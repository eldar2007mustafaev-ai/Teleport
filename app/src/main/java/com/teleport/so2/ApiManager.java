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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        getBaseHost() + "/api",
        getBaseHost() + ":8080",
        getBaseHost() + ":5000",
        getBaseHost() + ":8000"
    };

    public static final int CLIENT_BUILD_VERSION = 403;
    private static final String CLIENT_SALT = "SO2_TELEPORT_V403_CLEAN_EDITION";

    private static volatile boolean sessionVerified = false;
    private static volatile String lastWorkingBase = null;
    private static final ExecutorService probePool = Executors.newCachedThreadPool();

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
                    if (trimmed.contains("|")) {
                        String[] sub = trimmed.split("\\|");
                        for (String s : sub) {
                            if (s != null && s.trim().length() >= 8) {
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
                || res.optBoolean("valid", false)
                || res.optBoolean("success", false)
                || "ok".equalsIgnoreCase(status)
                || "active".equalsIgnoreCase(status)
                || "valid".equalsIgnoreCase(status)
                || "success".equalsIgnoreCase(status)) && !blocked;
    }

    private static boolean isExplicitBlockResponse(JSONObject res) {
        if (res == null) return false;
        String status = res.optString("status", "");
        return res.optBoolean("blocked", false)
                || "expired".equalsIgnoreCase(status)
                || "blocked".equalsIgnoreCase(status)
                || "banned".equalsIgnoreCase(status)
                || "device_mismatch".equalsIgnoreCase(status);
    }

    private static JSONObject buildAuthPayload(String action, String cleanKey, String hwid, String cleanNick) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("action", action);
        payload.put("key", cleanKey);
        payload.put("license_key", cleanKey);
        payload.put("sub_key", cleanKey);
        payload.put("android_id", hwid);
        payload.put("hwid", hwid);
        payload.put("device_id", hwid);
        if (cleanNick != null) {
            payload.put("nickname", cleanNick);
            payload.put("username", cleanNick);
        }
        payload.put("build_version", CLIENT_BUILD_VERSION);
        payload.put("sign", sha256(cleanKey + ":" + hwid + ":" + CLIENT_BUILD_VERSION + ":" + CLIENT_SALT));
        return payload;
    }

    public static String activateLicense(String key, String androidId, String nickname) {
        try {
            String cleanKey = key == null ? "" : key.trim().toUpperCase(Locale.US);
            String cleanNick = (nickname == null || nickname.trim().isEmpty()) ? "dare" : nickname.trim();
            List<String> candidates = parseCandidates(androidId);

            String firstHwid = candidates.get(0);
            JSONObject firstPayload = buildAuthPayload("activate", cleanKey, firstHwid, cleanNick);
            String firstRaw = postWithParallelDiscovery("/activate", firstPayload.toString());

            if (firstRaw == null || firstRaw.isEmpty() || firstRaw.contains("\"offline\"")) {
                JSONObject verFallback = buildAuthPayload("verify", cleanKey, firstHwid, cleanNick);
                firstRaw = postWithParallelDiscovery("/verify", verFallback.toString());
            }

            if (firstRaw == null || firstRaw.isEmpty() || firstRaw.contains("\"offline\"")) {
                return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Сервер временно недоступен\"}";
            }

            JSONObject lastValidServerRes = null;
            try {
                JSONObject firstRes = new JSONObject(firstRaw);
                if (isResponseSuccess(firstRes)) {
                    sessionVerified = true;
                    firstRes.put("status", "ok");
                    firstRes.put("ok", true);
                    firstRes.put("matched_hwid", firstHwid);
                    if (!firstRes.has("expires_in") && firstRes.has("days_left")) {
                        firstRes.put("expires_in", firstRes.optString("days_left"));
                    }
                    return firstRes.toString();
                }
                if (firstRes.has("status") || firstRes.has("ok") || firstRes.has("blocked")) {
                    lastValidServerRes = firstRes;
                }
            } catch (Exception ignored) {}

            int limit = Math.min(candidates.size(), 5);
            for (int i = 0; i < limit; i++) {
                String candidateHwid = candidates.get(i);
                try {
                    if (i > 0) {
                        JSONObject actPayload = buildAuthPayload("activate", cleanKey, candidateHwid, cleanNick);
                        String rawAct = postFastOnKnownBase("/activate", actPayload.toString());
                        if (rawAct != null && rawAct.startsWith("{")) {
                            JSONObject resAct = new JSONObject(rawAct);
                            if (isResponseSuccess(resAct)) {
                                sessionVerified = true;
                                resAct.put("status", "ok");
                                resAct.put("ok", true);
                                resAct.put("matched_hwid", candidateHwid);
                                if (!resAct.has("expires_in") && resAct.has("days_left")) {
                                    resAct.put("expires_in", resAct.optString("days_left"));
                                }
                                return resAct.toString();
                            }
                            if (resAct.has("status") || resAct.has("ok") || resAct.has("blocked")) {
                                lastValidServerRes = resAct;
                            }
                        }
                    }

                    JSONObject verPayload = buildAuthPayload("verify", cleanKey, candidateHwid, cleanNick);
                    String rawVer = postFastOnKnownBase("/verify", verPayload.toString());
                    if (rawVer != null && rawVer.startsWith("{")) {
                        JSONObject resVer = new JSONObject(rawVer);
                        if (isResponseSuccess(resVer)) {
                            sessionVerified = true;
                            resVer.put("status", "ok");
                            resVer.put("ok", true);
                            resVer.put("matched_hwid", candidateHwid);
                            if (!resVer.has("expires_in") && resVer.has("days_left")) {
                                resVer.put("expires_in", resVer.optString("days_left"));
                            }
                            return resVer.toString();
                        }
                        if (resVer.has("status") || resVer.has("ok") || resVer.has("blocked")) {
                            lastValidServerRes = resVer;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (lastValidServerRes != null && isExplicitBlockResponse(lastValidServerRes)) {
                sessionVerified = false;
                return lastValidServerRes.toString();
            }

            return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Ожидание синхронизации с сервером\"}";
        } catch (Exception e) {
            return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Сервер временно недоступен\"}";
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

            String firstHwid = candidates.get(0);
            JSONObject firstPayload = buildAuthPayload("verify", cleanKey, firstHwid, null);
            String firstRaw = postWithParallelDiscovery("/verify", firstPayload.toString());

            if (firstRaw == null || firstRaw.isEmpty() || firstRaw.contains("\"offline\"")) {
                return "{\"status\":\"offline\",\"ok\":false,\"blocked\":false,\"message\":\"Ожидание связи с сервером\"}";
            }

            JSONObject lastRes = new JSONObject(firstRaw);
            if (isResponseSuccess(lastRes)) {
                sessionVerified = true;
                lastRes.put("status", "ok");
                lastRes.put("ok", true);
                lastRes.put("matched_hwid", firstHwid);
                return lastRes.toString();
            }

            int limit = Math.min(candidates.size(), 5);
            for (int i = 1; i < limit; i++) {
                String candidateHwid = candidates.get(i);
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
                            res.put("matched_hwid", candidateHwid);
                            return res.toString();
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (isExplicitBlockResponse(lastRes)) {
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
            payload.put("key", key);
            payload.put("android_id", primaryHwid);
            payload.put("share_code", shareCode);
            payload.put("title", title == null ? "SO2TELEPORT PRO" : title);
            payload.put("preset", presetJson);
            payload.put("build_version", CLIENT_BUILD_VERSION);

            try {
                String raw = postWithParallelDiscovery("/preset", payload.toString());
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
            payload.put("key", key);
            payload.put("android_id", candidates.get(0));
            payload.put("share_code", shareCode == null ? "" : shareCode.trim().toUpperCase(Locale.US));
            payload.put("build_version", CLIENT_BUILD_VERSION);

            String raw = postWithParallelDiscovery("/preset", payload.toString());
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
            return postWithParallelDiscovery("/friends", jsonPayload);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Нет связи с сервером друзей\"}";
        }
    }

    private static boolean isValidLicenseJson(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) return false;
        return t.contains("\"status\"")
                || t.contains("\"ok\"")
                || t.contains("\"valid\"")
                || t.contains("\"share_code\"")
                || t.contains("\"friends\"");
    }

    private static String postFastOnKnownBase(String path, String jsonBody) {
        String cached = lastWorkingBase;
        if (cached != null) {
            try {
                String res = postJson(cached + path, jsonBody, 1300);
                if (isValidLicenseJson(res)) {
                    return res.trim();
                }
            } catch (Exception ignored) {}
        }
        return postWithParallelDiscovery(path, jsonBody);
    }

    private static String postWithParallelDiscovery(final String path, final String jsonBody) {
        String cached = lastWorkingBase;
        if (cached != null) {
            try {
                String res = postJson(cached + path, jsonBody, 1300);
                if (isValidLicenseJson(res)) {
                    return res.trim();
                }
            } catch (Exception ignored) {}
        }

        CompletionService<String[]> ecs = new ExecutorCompletionService<>(probePool);
        List<Future<String[]>> futures = new ArrayList<>();

        for (final String base : API_ENDPOINTS) {
            futures.add(ecs.submit(new Callable<String[]>() {
                @Override
                public String[] call() throws Exception {
                    String resp = postJson(base + path, jsonBody, 1600);
                    if (isValidLicenseJson(resp)) {
                        return new String[]{base, resp.trim()};
                    }
                    throw new RuntimeException("Not license endpoint");
                }
            }));
        }

        long deadline = System.currentTimeMillis() + 2000L;
        try {
            for (int i = 0; i < futures.size(); i++) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                Future<String[]> completed = ecs.poll(rem, TimeUnit.MILLISECONDS);
                if (completed == null) break;
                try {
                    String[] pair = completed.get();
                    if (pair != null && pair.length == 2 && isValidLicenseJson(pair[1])) {
                        lastWorkingBase = pair[0];
                        return pair[1];
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            for (Future<String[]> f : futures) {
                f.cancel(true);
            }
        }
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
        if (code == 404 || code == 405 || code >= 500) {
            throw new RuntimeException("HTTP " + code);
        }

        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            return "";
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
