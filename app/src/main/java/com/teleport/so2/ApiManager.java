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
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ApiManager {

    private static final AtomicLong VERIFIED_SESSION_STAMP = new AtomicLong(0L);
    private static final long SESSION_MAGIC_MASK = 0x5A93F10C7744E2B1L;
    private static final SecureRandom RNG = new SecureRandom();

    public static void setSessionVerified(boolean verified) {
        if (verified) {
            long nowSlice = (System.currentTimeMillis() / 60000L) ^ SESSION_MAGIC_MASK;
            VERIFIED_SESSION_STAMP.set(nowSlice);
        } else {
            VERIFIED_SESSION_STAMP.set(0L);
        }
    }

    public static boolean isSessionVerified() {
        long stamp = VERIFIED_SESSION_STAMP.get();
        if (stamp == 0L) return false;
        long decoded = stamp ^ SESSION_MAGIC_MASK;
        long currentSlice = System.currentTimeMillis() / 60000L;
        return Math.abs(currentSlice - decoded) <= 1440L;
    }

    public static void invalidateSession() {
        VERIFIED_SESSION_STAMP.set(0L);
    }

    public static String getBaseHost() {
        // Обфусцированная сборка хоста 45.10.165.75:8080
        int[] p = new int[]{45 ^ 0x33, 10 ^ 0x33, 165 ^ 0x33, 75 ^ 0x33};
        String ip = (p[0] ^ 0x33) + "." + (p[1] ^ 0x33) + "." + (p[2] ^ 0x33) + "." + (p[3] ^ 0x33);
        return "http://" + ip + ":8080";
    }

    private static String getEngineHost() {
        int[] p = new int[]{45 ^ 0x5A, 10 ^ 0x5A, 165 ^ 0x5A, 75 ^ 0x5A};
        String ip = (p[0] ^ 0x5A) + "." + (p[1] ^ 0x5A) + "." + (p[2] ^ 0x5A) + "." + (p[3] ^ 0x5A);
        return "http://" + ip + ":8443";
    }

    private static String computeRequestHmac(String body, String nonce, long ts) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String dayUtc = sdf.format(new Date());

            byte[] enc = new byte[]{
                    0x06, 0x1A, 0x67, 0x0A, 0x04, 0x00, 0x14, 0x1B,
                    0x01, 0x00, 0x18, 0x0A, 0x03, 0x64, 0x65, 0x0A,
                    0x1E, 0x10, 0x0C, 0x0A, 0x6C, 0x6C, 0x61, 0x64
            };
            byte[] raw = new byte[enc.length];
            for (int i = 0; i < enc.length; i++) raw[i] = (byte) (enc[i] ^ 0x55);

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] dailyKey = sha.digest((new String(raw, StandardCharsets.UTF_8) + "|" + dayUtc).getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(dailyKey, "HmacSHA256"));
            String msg = body + "|" + nonce + "|" + ts;
            byte[] sig = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : sig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String postJson(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            long ts = System.currentTimeMillis() / 1000L;
            String nonce = Long.toHexString(RNG.nextLong()) + Long.toHexString(System.nanoTime());
            String reqSig = computeRequestHmac(jsonBody, nonce, ts);

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "SO2Teleport-Quantum-V11");
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
                return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Пустой ответ сервера\"}";
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
                return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Некорректный ответ сервера\"}";
            }
            return result;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Нет связи с сервером авторизации\"}";
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch ( Throwable ignored) {}
            }
        }
    }

    public static String postWithFallback(String endpointPath, String jsonBody) {
        String primaryUrl = getBaseHost() + endpointPath;
        String res = postJson(primaryUrl, jsonBody);
        try {
            JSONObject obj = new JSONObject(res);
            if (!"error".equalsIgnoreCase(obj.optString("status")) || obj.has("sig")) {
                return res;
            }
        } catch (Throwable ignored) {}

        String secondaryUrl = getEngineHost() + endpointPath;
        return postJson(secondaryUrl, jsonBody);
    }

    public static String activateLicense(String key, String hwid, String nickname) {
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

            String raw = postWithFallback("/api/activate", req.toString());
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "error");
            if ("ok".equalsIgnoreCase(status) && !res.optBoolean("blocked", false)) {
                setSessionVerified(true);
            } else {
                invalidateSession();
            }
            return raw;
        } catch (Exception e) {
            invalidateSession();
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Ошибка сети при активации\"}";
        }
    }

    public static String verifyLicense(String key, String hwid) {
        try {
            String cleanKey = (key == null) ? "" : key.trim().toUpperCase(Locale.US);
            String cleanHwid = (hwid == null) ? "" : hwid.trim();

            JSONObject req = new JSONObject();
            req.put("key", cleanKey);
            req.put("android_id", cleanHwid);
            req.put("hwid", cleanHwid);
            req.put("ts", System.currentTimeMillis() / 1000L);

            String raw = postWithFallback("/api/verify", req.toString());
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "error");

            // НИКАКОГО "offline" ok:true! Любая ошибка или блокировка закрывает сессию
            if ("ok".equalsIgnoreCase(status) && !res.optBoolean("blocked", false)) {
                setSessionVerified(true);
                return raw;
            } else {
                invalidateSession();
                return raw;
            }
        } catch (Exception e) {
            invalidateSession();
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":true,\"message\":\"Нет связи с сервером лицензий\"}";
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
