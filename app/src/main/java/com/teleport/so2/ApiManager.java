package com.teleport.so2;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiManager {
    // Впиши сюда IP твоего сервера
    private static final String BASE_URL = "http://YOUR_SERVER_IP:8080";

    public static String sendPostRequest(String endpoint, JSONObject jsonBody) {
        try {
            URL url = new URL(BASE_URL + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "utf-8"))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }
            return response.toString();
        } catch (Exception e) {
            Log.e("ApiManager", "Network error: " + e.getMessage());
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    public static String getServerVersion() {
        return "2.0.0";
    }

    public static String checkNickname(String nickname) {
        try {
            JSONObject json = new JSONObject();
            json.put("nickname", nickname);
            return sendPostRequest("/check_nick", json);
        } catch (Exception e) {
            return "{\"status\":\"error\"}";
        }
    }

    public static String checkNickname(String nickname, String key) {
        return checkNickname(nickname);
    }

    public static String activateKey(String nickname, String key, String androidId) {
        return activateLicense(key, androidId, nickname);
    }

    public static String activateLicense(String key, String androidId, String nickname) {
        try {
            JSONObject json = new JSONObject();
            json.put("key", key);
            json.put("android_id", androidId);
            json.put("nickname", nickname);
            return sendPostRequest("/activate", json);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"JSON error\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        try {
            JSONObject json = new JSONObject();
            json.put("key", key);
            json.put("android_id", androidId);
            return sendPostRequest("/verify", json);
        } catch (Exception e) {
            return "{\"status\":\"ok\",\"blocked\":false}";
        }
    }

    public static void saveCloudPreset(String key, String presetJson) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("key", key);
                json.put("preset_json", presetJson);
                sendPostRequest("/sync_preset", json);
            } catch (Exception ignored) {}
        }).start();
    }

    public static void saveCloudPreset(String key, String androidId, String presetJson) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("key", key);
                json.put("android_id", androidId);
                json.put("preset_json", presetJson);
                sendPostRequest("/sync_preset", json);
            } catch (Exception ignored) {}
        }).start();
    }
}
