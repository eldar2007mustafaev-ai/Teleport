package com.teleport.so2;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiManager {
    public static final String BASE_URL = "http://45.10.165.75:8080";
    public static boolean TEST_MODE = false;

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
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сервер недоступен или нет сети\"}";
        }
    }

    public static String getServerVersion() {
        return "2.1.0";
    }

    public static String checkNickname(String nickname) {
        try {
            JSONObject json = new JSONObject();
            json.put("nickname", nickname);
            return sendPostRequest("/check_nick", json);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false}";
        }
    }

    public static String checkNickname(String nickname, String key) {
        return checkNickname(nickname);
    }

    public static String activateKey(String nickname, String key, String androidId) {
        return activateLicense(key, androidId, nickname);
    }

    public static String activateLicense(String key, String androidId, String nickname) {
        if (key == null || key.trim().length() < 6 || nickname == null || nickname.trim().isEmpty()) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Введите логин и корректный ключ!\"}";
        }
        try {
            JSONObject json = new JSONObject();
            json.put("key", key.trim().toUpperCase());
            json.put("android_id", androidId);
            json.put("nickname", nickname.trim());
            return sendPostRequest("/activate", json);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка проверки ключа\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        if (key == null || key.trim().length() < 6) {
            return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false}";
        }
        try {
            JSONObject json = new JSONObject();
            json.put("key", key.trim().toUpperCase());
            json.put("android_id", androidId);
            return sendPostRequest("/verify", json);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"blocked\":false}";
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
