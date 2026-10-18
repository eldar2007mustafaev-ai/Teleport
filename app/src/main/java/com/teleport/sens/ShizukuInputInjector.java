package com.teleport.sens;

import android.util.Log;

public class ShizukuInputInjector {
    
    private static final String TAG = "ShizukuInjector";
    private static boolean available = false;
    
    public static boolean init() {
        Log.i(TAG, "ShizukuInputInjector инициализирован (заглушка)");
        available = false;
        return false;
    }
    
    public static boolean injectMotionEvent(Object event) {
        Log.w(TAG, "Инжекция через Shizuku не реализована");
        return false;
    }
    
    public static boolean isAvailable() {
        return available;
    }
    
    public static String getStatus() {
        return "Shizuku инжектор (заглушка)";
    }
    
    public static void reset() {
        available = false;
    }
}
