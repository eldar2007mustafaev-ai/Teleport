package com.teleport.so2;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ApiManager {

    // Реальный IP твоего VPS-сервера в Германии
    public static final String SERVER_URL = "http://45.10.165.75:8080";
    
    // TEST_MODE выключен: теперь приложение проверяет ключи напрямую через базу бота на VPS!
    public static volatile boolean TEST_MODE = false;

    public static String checkNickname(String nickname, String key) {
        if (TEST_MODE) {
            return "{\"status\": \"ok\", \"mode\": \"test\"}";
        }
        try {
            JSONObject body = new JSONObject();
            body.put("nickname", nickname);
            body.put("key", key);
            return sendPostRequest(SERVER_URL + "/api/check_nickname", body.toString());
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"Ошибка сети: " + e.getMessage() + "\"}";
        }
    }

    public static String activateKey(String nickname, String key, String androidId) {
        if (TEST_MODE) {
            return "{\"status\": \"ok\", \"mode\": \"test\"}";
        }
        try {
            JSONObject body = new JSONObject();
            body.put("nickname", nickname);
            body.put("key", key);
            body.put("android_id", androidId);
            return sendPostRequest(SERVER_URL + "/api/activate_key", body.toString());
        } catch (Exception e) {
            return "{\"status\": \"error\", \"reason\": \"" + e.getMessage() + "\"}";
        }
    }

    public static String checkKeyStatus(String key) {
        if (TEST_MODE) {
            return "{\"status\": \"active\", \"mode\": \"test\"}";
        }
        return sendGetRequest(SERVER_URL + "/api/check_key/" + key.trim().toUpperCase());
    }

    public static String getServerVersion() {
        if (TEST_MODE) {
            return "{\"version\": \"1.0.0\", \"link\": \"https://t.me/so2_teleport_updates\"}";
        }
        return sendGetRequest(SERVER_URL + "/api/get_version");
    }

    private static String sendPostRequest(String endpoint, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            InputStream is = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "{\"status\": \"error\", \"reason\": \"empty_response\", \"message\": \"Пустой ответ от сервера\"}";

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "{\"status\": \"error\", \"reason\": \"network_error\", \"message\": \"Не удалось подключиться к серверу лицензий\"}";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String sendGetRequest(String endpoint) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            InputStream is = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "{\"status\": \"error\"}";

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "{\"status\": \"error\", \"reason\": \"network_error\"}";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
