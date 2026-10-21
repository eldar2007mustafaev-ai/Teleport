package com.teleport.so2;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class KeyValidator {

    public static boolean isFormatValid(String key) {
        if (key == null) return false;
        String clean = key.trim().toUpperCase();
        return clean.startsWith("TP-") && clean.length() >= 9;
    }

    public static boolean isKeyInLocalAssets(Context context, String key) {
        if (!isFormatValid(key)) return false;
        String cleanKey = key.trim().toUpperCase();

        try (InputStream is = context.getAssets().open("keys.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject obj = new JSONObject(sb.toString());
            JSONArray arr = obj.optJSONArray("valid_keys");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    if (cleanKey.equalsIgnoreCase(arr.getString(i).trim())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }
}
