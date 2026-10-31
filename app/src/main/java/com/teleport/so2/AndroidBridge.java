package com.teleport.so2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;

public class AndroidBridge {
    private final MainActivity activity;

    public AndroidBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public boolean isShizukuReady() {
        return ShizukuManager.isPermissionGranted();
    }

    @JavascriptInterface
    public boolean isEngineActive() {
        return TeleportEngine.isActive();
    }

    @JavascriptInterface
    public void startTeleport(String presetId) {
        TeleportEngine.startTeleport(activity, presetId);
    }

    @JavascriptInterface
    public void stopTeleport() {
        TeleportEngine.stopTeleport();
    }

    @JavascriptInterface
    public void updateParameters(
            float sharp, float sx, float sy,
            int ch, int pktHz, float straight,
            int stick, int grid, int pause, float ac
    ) {
        TeleportEngine.updateParameters(sharp, sx, sy, ch, pktHz, straight, stick, grid, pause, ac);
    }

    @JavascriptInterface
    public void setSmartAwpLock(boolean enabled) {
        TeleportEngine.setSmartAwpLock(enabled);
    }

    @JavascriptInterface
    public void setAntiRecoilQuantization(boolean enabled, int strength) {
        TeleportEngine.setAntiRecoilQuantization(enabled, strength);
    }

    @JavascriptInterface
    public void setHzThermalGuard(boolean enabled) {
        TeleportEngine.setHzThermalGuard(enabled);
    }

    @JavascriptInterface
    public void setPixelDownscaleFactor(int percent) {
        TeleportEngine.setPixelDownscaleFactor(percent);
    }

    @JavascriptInterface
    public void applyResolutionRatio(String ratio) {
        activity.applyResolutionRatio(ratio);
    }

    @JavascriptInterface
    public void resetResolution() {
        activity.resetResolution();
    }

    @JavascriptInterface
    public void toggleFloatingButton(boolean enable) {
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("float_btn_enabled", enable).apply();
        activity.toggleFloatingButton(enable);
    }

    @JavascriptInterface
    public void updateCrosshair(String type) {
        activity.updateCrosshair(type);
    }

    @JavascriptInterface
    public void updateCrosshairStyle(int scale, int opacity, String colorHex) {
        activity.updateCrosshairStyle(scale, opacity, colorHex);
    }

    @JavascriptInterface
    public void updateOverlayStealthFlag(boolean enabled) {
        SharedPreferences prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("stealth_overlays", enabled).apply();
        activity.updateOverlayStealthFlag(enabled);
    }

    @JavascriptInterface
    public void setQuantumOverlayStrobe(boolean enabled) {
        activity.updateOverlayStealthFlag(enabled);
    }

    @JavascriptInterface
    public void healTouch() {
        activity.healBrokenTouchTimeouts();
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        int longPx = Math.max(dm.widthPixels, dm.heightPixels);
        return Build.MODEL + "|" + Build.HARDWARE + "|" + shortPx + "x" + longPx + "|" + dm.densityDpi;
    }
}
