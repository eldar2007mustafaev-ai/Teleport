package com.teleport.so2;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ApiManager {

    // Поле оставлено для совместимости с ProfileManager.java:159 и проверяется из C++ (JNI Honeypot)
    public static boolean TEST_MODE = false;

    private static final SecureRandom RNG = new SecureRandom();

    public static boolean isHoneypotTriggered() {
        return TEST_MODE;
    }

    public static void setSessionVerified(boolean verified) {
        if (!verified || TEST_MODE) {
            AndroidBridge.revokeNativeSessionFromApi();
        }
    }

    public static boolean isSessionVerified() {
        if (TEST_MODE) return false;
        return AndroidBridge.isNativeServerAuthorized();
    }

    public static void invalidateSession() {
        AndroidBridge.revokeNativeSessionFromApi();
    }

    public static String getBaseHost() {
        return AndroidBridge.getEncryptedServerEndpoint(0);
    }

    private static String getEngineHost() {
        return AndroidBridge.getEncryptedServerEndpoint(1);
    }

    // 100% нативная подпись запроса без Java fallback (Пункты #1, #16, #41)
    private static String computeRequestHmac(String body, String nonce, long ts) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String dayUtc = sdf.format(new Date());
            return AndroidBridge.signPayloadNative(body + "|" + nonce + "|" + ts, dayUtc);
        } catch (Throwable t) {
            return "";
        }
    }

    public static String postJson(String urlStr, String jsonBody) {
        if (TEST_MODE || urlStr == null || urlStr.isEmpty()) {
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Auth endpoint unavailable\"}";
        }
        HttpURLConnection conn = null;
        try {
            long ts = System.currentTimeMillis() / 1000L;
            String nonce = Long.toHexString(RNG.nextLong()) + Long.toHexString(System.nanoTime());
            String reqSig = computeRequestHmac(jsonBody, nonce, ts);
            if (reqSig == null || reqSig.length() < 64) {
                return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Native signature error\"}";
            }

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "SO2Teleport-Quantum-V12");
            conn.setRequestProperty("X-SO2-Ts", String.valueOf(ts));
            conn.setRequestProperty("X-SO2-Nonce", nonce);
            conn.setRequestProperty("X-SO2-Sig", reqSig);

            byte[] payloadBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payloadBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Empty server response\"}";
            }

            StringBuilder resp = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    resp.append(line);
                }
            }
            String result = resp.toString().trim();
            if (result.isEmpty() || !result.startsWith("{")) {
                return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Invalid server response\"}";
            }
            return result;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Connection failed\"}";
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    public static String postWithFallback(String endpointPath, String jsonBody) {
        String primaryHost = getBaseHost();
        if (!primaryHost.isEmpty()) {
            String res = postJson(primaryHost + endpointPath, jsonBody);
            try {
                JSONObject obj = new JSONObject(res);
                if (!"error".equalsIgnoreCase(obj.optString("status")) || obj.has("sig")) {
                    return res;
                }
            } catch (Throwable ignored) {}
        }

        String secondaryHost = getEngineHost();
        if (!secondaryHost.isEmpty()) {
            return postJson(secondaryHost + endpointPath, jsonBody);
        }
        return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Server unreachable\"}";
    }

    public static String activateLicense(String key, String hwid, String nickname) {
        if (TEST_MODE) {
            invalidateSession();
            return "{\"status\":\"banned\",\"ok\":false,\"blocked\":true,\"message\":\"Client integrity error\"}";
        }
        try {
            String cleanKey = (key == null) ? "" : key.trim().toUpperCase(Locale.US);
            String cleanHwid = (hwid == null) ? "" : hwid.trim();
            String cleanNick = (nickname == null || nickname.trim().isEmpty()) ? "Player" : nickname.trim();

            JSONObject req = new JSONObject();
            req.put("key", cleanKey);
            req.put("android_id", cleanHwid);
            req.put("hwid", cleanHwid);
            req.put("nickname", cleanNick);
            req.put("ts", System.currentTimeMillis() / 1000L);

            return postWithFallback("/api/activate", req.toString());
        } catch (Exception e) {
            invalidateSession();
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Activation network error\"}";
        }
    }

    public static String verifyLicense(String key, String hwid) {
        if (TEST_MODE) {
            invalidateSession();
            return "{\"status\":\"banned\",\"ok\":false,\"blocked\":true,\"message\":\"Client integrity error\"}";
        }
        try {
            String cleanKey = (key == null) ? "" : key.trim().toUpperCase(Locale.US);
            String cleanHwid = (hwid == null) ? "" : hwid.trim();

            JSONObject req = new JSONObject();
            req.put("key", cleanKey);
            req.put("android_id", cleanHwid);
            req.put("hwid", cleanHwid);
            req.put("ts", System.currentTimeMillis() / 1000L);

            return postWithFallback("/api/verify", req.toString());
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Verification network error\"}";
        }
    }

    public static String uploadCloudPreset(String key, String hwid, String title, String presetJson) {
        if (!isSessionVerified()) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Требуется активная лицензия\"}";
        }
        try {
            JSONObject req = new JSONObject();
            req.put("action", "upload");
            req.put("key", key);
            req.put("android_id", hwid);
            req.put("title", title == null ? "Custom" : title.trim());
            req.put("preset_json", presetJson == null ? "{}" : presetJson);
            return postWithFallback("/api/cloud", req.toString());
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка выгрузки в облако\"}";
        }
    }

    public static String downloadCloudPresetByCode(String key, String hwid, String shareCode) {
        if (!isSessionVerified()) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Требуется активная лицензия\"}";
        }
        try {
            JSONObject req = new JSONObject();
            req.put("action", "download");
            req.put("key", key);
            req.put("android_id", hwid);
            req.put("share_code", shareCode == null ? "" : shareCode.trim().toUpperCase(Locale.US));
            return postWithFallback("/api/cloud", req.toString());
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Код не найден\"}";
        }
    }

    public static String callFriendsApi(String jsonPayload) {
        if (!isSessionVerified()) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Авторизуйтесь для доступа к отряду\"}";
        }
        return postWithFallback("/api/friends", jsonPayload);
    }
}
