package com.teleport.so2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

public class AndroidBridge {
    private final Activity activity;
    private final SharedPreferences prefs;
    private boolean isRunning = false;

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public boolean isShizukuReady() {
        return ShizukuManager.isPermissionGranted();
    }

    @JavascriptInterface
    public void requestShizukuPermission() {
        ShizukuManager.requestPermission();
    }

    @JavascriptInterface
    public void handleShizukuCardClick() {
        if (!isShizukuReady()) {
            requestShizukuPermission();
        }
    }

    @JavascriptInterface
    public boolean toggleTeleport() {
        isRunning = !isRunning;
        prefs.edit().putBoolean("teleport_running", isRunning).apply();
        int snap = prefs.getInt("tp_snap_power", 350);
        String preset = prefs.getString("selected_preset", "teleport");

        if (isRunning) {
            TeleportEngine.startTeleport(snap, preset);
        } else {
            TeleportEngine.stopTeleport();
        }
        return isRunning;
    }

    @JavascriptInterface
    public boolean isTeleportRunning() {
        return isRunning;
    }

    @JavascriptInterface
    public void selectPreset(String presetId) {
        prefs.edit().putString("selected_preset", presetId).apply();
        int snap = prefs.getInt("tp_snap_power", 350);
        if (isShizukuReady()) {
            isRunning = true;
            prefs.edit().putBoolean("teleport_running", true).apply();
            TeleportEngine.startTeleport(snap, presetId);
        }
    }

    @JavascriptInterface
    public void setTeleportSnapPower(int snapPercent) {
        prefs.edit().putInt("tp_snap_power", snapPercent).apply();
        String preset = prefs.getString("selected_preset", "teleport");
        TeleportEngine.setStepQuantization(true, snapPercent / 100.0f);
        if (isShizukuReady()) {
            TeleportEngine.startTeleport(snapPercent, preset);
        }
    }

    @JavascriptInterface
    public String setCrosshairType(String type) {
        prefs.edit().putString("crosshair_type", type).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateCrosshair(type);
        }
        return "off".equalsIgnoreCase(type) ? "Прицел выключен" : "Прицел включен: " + type;
    }

    @JavascriptInterface
    public void updateCrosshairParams(int scale, int opacity, String colorHex) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateCrosshairStyle(scale, opacity, colorHex);
        }
    }

    @JavascriptInterface
    public boolean isFloatingStretchButtonEnabled() {
        return prefs.getBoolean("float_btn_enabled", false);
    }

    @JavascriptInterface
    public String setFloatingStretchButton(boolean enabled) {
        if (enabled && activity instanceof MainActivity) {
            if (!((MainActivity) activity).checkAndRequestOverlayPermission()) {
                prefs.edit().putBoolean("float_btn_enabled", false).apply();
                return "Разрешите показ поверх других окон!";
            }
        }
        prefs.edit().putBoolean("float_btn_enabled", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).toggleFloatingButton(enabled);
        }
        return enabled ? "Плавающая кнопка 📐 включена" : "Плавающая кнопка скрыта";
    }

    @JavascriptInterface
    public String applyStretchRatio(String ratio) {
        prefs.edit().putString("stretch_ratio", ratio).apply();
        if (!isShizukuReady()) {
            return "Сначала активируйте Shizuku!";
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyResolutionRatio(ratio);
        }
        return "Растяг " + ratio + " применён!";
    }

    @JavascriptInterface
    public String resetDisplayToDefault() {
        if (!isShizukuReady()) {
            return "Сначала активируйте Shizuku!";
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).resetResolution();
        }
        return "Экран сброшен по умолчанию";
    }

    @JavascriptInterface
    public String setHideOverlaysOnScreenshot(boolean enabled) {
        prefs.edit().putBoolean("stealth_overlays", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateOverlayStealthFlag(enabled);
        }
        return enabled ? "Stealth-режим включен" : "Stealth-режим выключен";
    }

    @JavascriptInterface
    public String setTouchBoost(boolean enabled) {
        prefs.edit().putBoolean("touch_boost", enabled).apply();
        if (!isShizukuReady()) return "Включите Shizuku!";

        new Thread(() -> {
            if (enabled) {
                ShizukuManager.execCommand(
                    "settings put system pointer_speed 7; " +
                    "settings put secure long_press_timeout 120; " +
                    "settings put secure multi_press_timeout 100; " +
                    "setprop windowsmgr.max_events_per_sec 320; " +
                    "setprop ro.min_pointer_dur 1"
                );
            } else {
                ShizukuManager.execCommand(
                    "settings put system pointer_speed 0; " +
                    "settings put secure long_press_timeout 400; " +
                    "settings put secure multi_press_timeout 300"
                );
            }
        }).start();

        return enabled ? "Touch Boost 240 Гц активен" : "Touch Boost выключен";
    }

    @JavascriptInterface
    public String setDisableThrottling(boolean enabled) {
        prefs.edit().putBoolean("disable_throttling", enabled).apply();
        if (!isShizukuReady()) return "Включите Shizuku!";

        new Thread(() -> {
            if (enabled) {
                ShizukuManager.execCommand(
                    "cmd power set-fixed-performance-mode-enabled true; " +
                    "cmd thermalservice override-status 0"
                );
            } else {
                ShizukuManager.execCommand(
                    "cmd power set-fixed-performance-mode-enabled false; " +
                    "cmd thermalservice reset"
                );
            }
        }).start();

        return enabled ? "Троттлинг отключен" : "Стандартный режим частот";
    }

    @JavascriptInterface
    public String setBudgetDeviceBoost(boolean enabled) {
        prefs.edit().putBoolean("budget_boost", enabled).apply();
        if (!isShizukuReady()) return enabled ? "Облегченный режим включен" : "Обычный режим";

        new Thread(() -> {
            if (enabled) {
                ShizukuManager.execCommand(
                    "am kill-all; " +
                    "settings put global window_animation_scale 0.0; " +
                    "settings put global transition_animation_scale 0.0; " +
                    "settings put global animator_duration_scale 0.0; " +
                    "setprop debug.hwui.use_buffer_age false; " +
                    "setprop debug.sf.latch_unsignaled 1"
                );
            } else {
                ShizukuManager.execCommand(
                    "settings put global window_animation_scale 1.0; " +
                    "settings put global transition_animation_scale 1.0; " +
                    "settings put global animator_duration_scale 1.0"
                );
            }
        }).start();

        return enabled ? "Бустер для бюджеток активен" : "Стандартная графика";
    }

    @JavascriptInterface
    public String setPixelOptimization(boolean enabled) {
        prefs.edit().putBoolean("pixel_opt", enabled).apply();
        if (!isShizukuReady()) return "Включите Shizuku!";

        new Thread(() -> {
            if (enabled) {
                ShizukuManager.execCommand(
                    "cmd game mode performance com.axlebolt.standoff2; " +
                    "cmd game downscale 0.85 com.axlebolt.standoff2; " +
                    "setprop debug.hwui.renderer skiagl; " +
                    "setprop debug.sf.hw 1"
                );
            } else {
                ShizukuManager.execCommand(
                    "cmd game downscale disable com.axlebolt.standoff2"
                );
            }
        }).start();

        return enabled ? "Оптимизация пикселей включена" : "Стандартный рендер";
    }

    @JavascriptInterface
    public String boostFps(boolean enabled) {
        return setBudgetDeviceBoost(enabled);
    }

    @JavascriptInterface
    public void setDndMode(boolean enabled) {
        if (!isShizukuReady()) return;
        new Thread(() -> {
            ShizukuManager.execCommand(enabled ? "cmd notification set_dnd priority" : "cmd notification set_dnd off");
        }).start();
    }

    @JavascriptInterface
    public boolean isOnboardingRequired() {
        String key = prefs.getString("license_key", "");
        return key.isEmpty();
    }

    @JavascriptInterface
    public String authorizeAndActivate(String nickname, String key) {
        try {
            String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
            String res = ApiManager.activateLicense(key, androidId, nickname);
            JSONObject json = new JSONObject(res);
            if (json.optBoolean("ok", false) || "ok".equals(json.optString("status"))) {
                prefs.edit().putString("license_key", key).putString("nickname", nickname).apply();
            }
            return res;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public String verifySavedLicenseOnStartup() {
        try {
            String key = prefs.getString("license_key", "");
            if (key.isEmpty()) return "{\"status\":\"need_auth\",\"blocked\":false}";
            String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
            return ApiManager.verifyLicense(key, androidId);
        } catch (Exception e) {
            return "{\"status\":\"ok\",\"blocked\":false}";
        }
    }

    @JavascriptInterface
    public void syncPresetToServer(String presetJson) {
        String key = prefs.getString("license_key", "");
        String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
        ApiManager.saveCloudPreset(key, androidId, presetJson);
    }

    @JavascriptInterface
    public String scanDevice() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("device_name", Build.MANUFACTURER.toUpperCase() + " " + Build.MODEL);
            DisplayMetrics dm = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            obj.put("resolution", dm.widthPixels + "x" + dm.heightPixels);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity.getDisplay() != null) {
                obj.put("refresh_rate", (int) activity.getDisplay().getRefreshRate());
            } else {
                obj.put("refresh_rate", 120);
            }
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public String getProfileData() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("nickname", prefs.getString("nickname", "Игрок"));
            obj.put("android_id", Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID));
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void openExternalLink(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception ignored) {}
    }
}
