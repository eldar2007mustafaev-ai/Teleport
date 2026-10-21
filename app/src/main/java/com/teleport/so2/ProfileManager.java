package com.teleport.so2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ProfileManager {

    private static final String PREF_NAME = "so2_teleport_profile_prefs";

    @SuppressLint("HardwareIds")
    public static String getHardwareAndroidId(Context context) {
        try {
            String aid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            return (aid != null && !aid.isEmpty()) ? aid : "ANDROID_HWID_001";
        } catch (Exception e) {
            return "ANDROID_HWID_001";
        }
    }

    public static void saveNativeScreenDefaults(Context context, int longSide, int shortSide, int dpi) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains("native_long")) {
            prefs.edit()
                    .putInt("native_long", longSide)
                    .putInt("native_short", shortSide)
                    .putInt("native_dpi", dpi)
                    .apply();
        }
    }

    public static int getNativeLong(Context c, int fallback) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt("native_long", fallback);
    }

    public static int getNativeShort(Context c, int fallback) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt("native_short", fallback);
    }

    public static String getSavedKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString("activated_key", "");
    }

    public static boolean isActivated(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedKey = prefs.getString("activated_key", "");
        String boundAid = prefs.getString("bound_android_id", "");
        String currentAid = getHardwareAndroidId(context);
        return !savedKey.isEmpty() && boundAid.equals(currentAid);
    }

    public static void saveActivatedProfile(Context context, String nickname, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date());
        prefs.edit()
                .putString("nickname", nickname.trim())
                .putString("activated_key", key.trim().toUpperCase())
                .putString("bound_android_id", getHardwareAndroidId(context))
                .putString("activated_date", now)
                .putBoolean("onboarding_done", true)
                .apply();
    }

    public static boolean isOnboardingDone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("onboarding_done", false) && isActivated(context);
    }

    public static void savePreset(Context context, String presetId) {
        int chunk = 30;
        int threshold = 14;
        int zone = 75;
        int hz = 240;

        switch (presetId) {
            case "sensa":
                chunk = 18;
                threshold = 10;
                hz = 180;
                break;
            case "teleport":
                chunk = 30;
                threshold = 14;
                hz = 240;
                break;
            case "sharp":
                chunk = 44;
                threshold = 10;
                hz = 240;
                break;
            case "smooth":
                chunk = 12;
                threshold = 16;
                hz = 144;
                break;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("active_preset", presetId)
                .putInt("cfg_chunk", chunk)
                .putInt("cfg_threshold", threshold)
                .putInt("cfg_zone", zone)
                .putInt("cfg_poll_hz", hz)
                .apply();
    }

    public static void saveTeleportSettings(Context context, int chunkSize, int threshold, int aimZonePct, int pollHz) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("cfg_chunk", Math.max(10, Math.min(50, chunkSize)))
                .putInt("cfg_threshold", threshold)
                .putInt("cfg_zone", aimZonePct)
                .putInt("cfg_poll_hz", pollHz)
                .apply();
    }

    public static int getChunk(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt("cfg_chunk", 30);
    }

    public static int getThreshold(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt("cfg_threshold", 14);
    }

    public static int getZone(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt("cfg_zone", 75);
    }

    public static int getPollHz(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt("cfg_poll_hz", 240);
    }

    public static void addTeleportCountToTotal(Context context, int delta) {
        if (delta <= 0) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int cur = prefs.getInt("stat_total_teleports", 0);
        prefs.edit().putInt("stat_total_teleports", cur + delta).apply();
    }

    public static String getProfileJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        JSONObject obj = new JSONObject();
        try {
            obj.put("nickname", prefs.getString("nickname", "Игрок"));
            obj.put("key", prefs.getString("activated_key", "-"));
            obj.put("android_id", getHardwareAndroidId(context));
            obj.put("active_preset", prefs.getString("active_preset", "teleport"));
            obj.put("cfg_chunk", getChunk(context));
            obj.put("cfg_threshold", getThreshold(context));
            obj.put("cfg_poll_hz", getPollHz(context));
            obj.put("total_teleports", prefs.getInt("stat_total_teleports", 0));
            obj.put("test_mode", ApiManager.TEST_MODE);
        } catch (Exception ignored) {}
        return obj.toString();
    }
}
