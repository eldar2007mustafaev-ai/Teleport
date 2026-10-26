package com.teleport.so2;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiManager {

    // ⚠️ ВАЖНО: Замени на реальный IP или домен твоего сервера (где запущен bot.py на порту 8080)
    // Пример: "http://192.168.1.100:8080" или "https://api.yourdomain.com"
    public static final String SERVER_URL = "http://45.10.165.75:8080";
    public static final boolean TEST_MODE = false; // Оставь false для работы с реальным сервером

    // ==========================================
    // Методы, которые вызывает AndroidBridge (от Gemini)
    // ==========================================

    public static String activateLicense(String key, String androidId, String nickname) {
        try {
            URL url = new URL(SERVER_URL + "/activate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("key", key);
            json.put("android_id", androidId);
            json.put("nickname", nickname);

            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes());
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e("ApiManager", "activateLicense error: " + e.getMessage());
            return "{\"status\":\"error\",\"message\":\"Ошибка сети: " + e.getMessage() + "\"}";
        }
    }

    public static String verifyLicense(String key, String androidId) {
        try {
            URL url = new URL(SERVER_URL + "/verify");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("key", key);
            json.put("android_id", androidId);

            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes());
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e("ApiManager", "verifyLicense error: " + e.getMessage());
            return "{\"status\":\"error\",\"blocked\":true,\"message\":\"Ошибка сети\"}";
        }
    }

    public static void saveCloudPreset(String key, String androidId, String presetJson) {
        try {
            URL url = new URL(SERVER_URL + "/sync_preset");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("key", key);
            json.put("android_id", androidId);
            json.put("preset_json", presetJson);

            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes());
            os.close();

            // Читаем ответ, чтобы соединение закрылось корректно
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
        } catch (Exception e) {
            Log.e("ApiManager", "saveCloudPreset error: " + e.getMessage());
        }
    }

    // ==========================================
    // Заглушки для совместимости (если вдруг вызываются где-то еще)
    // ==========================================
    public static String checkKeyStatus(String key) {
        return "{\"status\":\"ok\"}";
    }
}
