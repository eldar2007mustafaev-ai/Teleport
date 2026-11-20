package com.teleport.so2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class ConfigManager {

    private static final String PREF_NAME = "RemoteConfigPrefs";
    private static final String KEY_CACHED_CONFIG = "cached_config_json";
    
    // Твой публичный ключ (уже вставлен)
    private static final String PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAVzbAYzn7RIPekeGmf2ORjruuDhXoZBXKvm1QW0w4OCM=";

    public static String getCachedConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CACHED_CONFIG, "{\"version\":0,\"payload\":{}}");
    }

    public static void updateConfig(Context context, String configUrl, String sigUrl) {
        new Thread(() -> {
            try {
                byte[] configData = downloadData(configUrl);
                byte[] sigDataHex = downloadData(sigUrl);

                if (configData != null && sigDataHex != null) {
                    String sigHex = new String(sigDataHex, StandardCharsets.UTF_8).trim();
                    byte[] sigBytes = hexStringToByteArray(sigHex);

                    if (verifySignature(configData, sigBytes)) {
                        String configJson = new String(configData, StandardCharsets.UTF_8);
                        
                        try {
                            new JSONObject(configJson); 
                            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                            prefs.edit().putString(KEY_CACHED_CONFIG, configJson).apply();
                            Log.d("ConfigManager", "Config updated successfully");
                        } catch (JSONException e) {
                            Log.e("ConfigManager", "Invalid JSON format");
                        }
                    } else {
                        Log.e("ConfigManager", "Signature verification failed!");
                    }
                }
            } catch (Exception e) {
                Log.e("ConfigManager", "Error updating config: " + e.getMessage());
            }
        }).start();
    }

    private static byte[] downloadData(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        
        if (conn.getResponseCode() != 200) return null;

        InputStream is = conn.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[4096];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    private static boolean verifySignature(byte[] data, byte[] signature) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PublicKey publicKey = kf.generatePublic(spec);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }
    
    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}
