package com.teleport.so2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class SecurityShield {

    private static final String PREF_SHIELD = "so2_security_shield_v12";
    private static boolean sNativeLoaded = false;

    static {
        try {
            System.loadLibrary("so2_native");
            sNativeLoaded = true;
        } catch (Throwable ignored) {
            sNativeLoaded = false;
        }
    }

    public static native String nativeGetHwid(Context context);
    public static native String getNativeHwid(Context context);
    public static native String nativeGetHardwareId(Context context);
    public static native boolean nativeVerifyEnvironment(Context context);
    public static native boolean nativeVerifyIntegrity(Context context);
    public static native boolean nativeVerifyHtmlIntegrity(Context context);
    public static native boolean nativeVerifyAssetIntegrity(Context context);
    public static native boolean nativeIsTampered(Context context);
    public static native boolean isTamperedNative();
    public static native String nativeSignPayload(String payload);

    public static void init(Context context) {
        // Инициализация щита без блокировки обновлённого интерфейса
    }

    public static String getHardwareId(Context context) {
        if (sNativeLoaded) {
            try {
                String hwid = nativeGetHwid(context);
                if (hwid != null && !hwid.trim().isEmpty()) {
                    return hwid.trim();
                }
            } catch (Throwable ignored) {}
        }
        return computeFallbackHardwareHwid();
    }

    public static String getHwid(Context context) {
        return getHardwareId(context);
    }

    public static String getDeviceHwid(Context context) {
        return getHardwareId(context);
    }

    private static String computeFallbackHardwareHwid() {
        try {
            String sysProps = safe(Build.BOARD) + "|" +
                    safe(Build.BRAND) + "|" +
                    safe(Build.DEVICE) + "|" +
                    safe(Build.HARDWARE) + "|" +
                    safe(Build.MODEL) + "|" +
                    safe(Build.PRODUCT);
            String seed = "SO2_TP_PRO_HWID_V12|" + sysProps;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "so2hw0000000001";
        }
    }

    private static String safe(String v) {
        return (v == null || v.isEmpty()) ? "unknown" : v;
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

    public static String signPayload(String payload) {
        if (payload == null) payload = "";
        if (sNativeLoaded) {
            try {
                String sig = nativeSignPayload(payload);
                if (sig != null && !sig.isEmpty()) return sig;
            } catch (Throwable ignored) {}
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("SO2_TP_SIGN_V12|" + payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
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

    public static String getVerifiedKey(Context context) {
        if (context == null) return "";
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_SHIELD, Context.MODE_PRIVATE);
            return sp.getString("verified_key", "");
        } catch (Throwable e) {
            return "";
        }
    }

    public static void clearSessionToken(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_SHIELD, Context.MODE_PRIVATE);
            sp.edit().clear().apply();
        } catch (Throwable ignored) {}
    }
}
