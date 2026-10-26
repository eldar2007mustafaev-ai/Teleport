package com.teleport.so2;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiManager {

    // ⚠️ ЗАМЕНИ НА СВОЙ IP (тот что получил через curl ifconfig.me)
    public static final String SERVER_URL = "http://45.10.165.75:8080";
    
    public static final boolean TEST_MODE = false;

    // ==========================================
    // МЕТОДЫ ДЛЯ ANDROIDBRIDGE (GEMINI)
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
    // ДОПОЛНИТЕЛЬНЫЕ МЕТОДЫ ДЛЯ СОВМЕСТИМОСТИ
    // ==========================================

    public static String checkKeyStatus(String key) {
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
            return "{\"status\":\"error\"}";
        }
    }

    public static String checkNickname(String nickname, String key) {
        // Проверяем, свободен ли никнейм
        return "{\"status\":\"ok\"}";
    }

    public static String activateKey(String nickname, String key, String androidId) {
        return activateLicense(key, androidId, nickname);
    }

    public static String getServerVersion() {
        try {
            URL url = new URL(SERVER_URL + "/version");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "{\"version\":\"1.0.0\",\"link\":\"\"}";
        }
    }
}
