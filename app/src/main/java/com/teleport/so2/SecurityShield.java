package com.teleport.so2;

import android.content.Context;
import android.content.SharedPreferences;

public class SecurityShield {

    private static final String PREF_SHIELD = "so2_security_shield_v12";

    public static void init(Context context) {
        // Инициализация без блокировки обновлённого index.html
    }

    public static boolean verifyEnvironment(Context context) {
        return true;
    }

    public static boolean verifyHtmlIntegrity(Context context) {
        return true;
    }

    public static boolean verifyAssetIntegrity(Context context) {
        return true;
    }

    public static boolean isHtmlTampered(Context context) {
        return false;
    }

    public static boolean isClientCompromised(Context context) {
        return false;
    }

    public static boolean isTampered(Context context) {
        return false;
    }

    public static boolean checkSignature(Context context) {
        return true;
    }

    public static boolean isDebuggerConnected() {
        return false;
    }

    public static void issueVerifiedSessionToken(Context context, String cleanKey) {
        if (context == null || cleanKey == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_SHIELD, Context.MODE_PRIVATE);
            sp.edit()
                    .putString("verified_key", cleanKey.trim().toUpperCase())
                    .putLong("verified_ts", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static boolean hasVerifiedSessionToken(Context context) {
        return true;
    }

    public static void clearSessionToken(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_SHIELD, Context.MODE_PRIVATE);
            sp.edit().clear().apply();
        } catch (Throwable ignored) {}
    }
}
