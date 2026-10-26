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
        return isRunning;
    }

    @JavascriptInterface
    public boolean isTeleportRunning() {
        if (!isRunning) {
            isRunning = prefs.getBoolean("teleport_running", false);
        }
        return isRunning;
    }

    @JavascriptInterface
    public void selectPreset(String presetId) {
        prefs.edit().putString("selected_preset", presetId).apply();
    }

    @JavascriptInterface
    public void setTeleportSnapPower(int snapPercent) {
        prefs.edit().putInt("tp_snap_power", snapPercent).apply();
        float mult = snapPercent / 100.0f;
        TeleportEngine.setStepQuantization(true, mult);

        if (!isShizukuReady()) return;

        new Thread(() -> {
            String batchCmd =
                "settings put system pointer_speed 7; " +
                "settings put secure long_press_timeout 140; " +
                "settings put secure multi_press_timeout 120; " +
                "settings put secure tap_duration_threshold 0.0; " +
                "settings put secure touch_blocking_period 0.0; " +
                "setprop debug.sf.latch_unsignaled 1; " +
                "setprop debug.sf.disable_backpressure 1; " +
                "setprop debug.hwui.use_buffer_age false; " +
                "setprop windowsmgr.max_events_per_sec 300; " +
                "setprop ro.min_pointer_dur 1; " +
                "setprop touch.pressure.scale 0.0001; " +
                "setprop debug.sf.high_fps_early_phase_offset_ns -6000000; " +
                "setprop debug.sf.early_phase_offset_ns -6000000";
            ShizukuManager.execCommand(batchCmd);
        }).start();
    }

    @JavascriptInterface
    public String setCrosshairType(String type) {
        prefs.edit().putString("crosshair_type", type).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateCrosshair(type);
        }
        return "Прицел изменен: " + type;
    }

    @JavascriptInterface
    public boolean isFloatingStretchButtonEnabled() {
        return prefs.getBoolean("float_btn_enabled", false);
    }

    @JavascriptInterface
    public String setFloatingStretchButton(boolean enabled) {
        prefs.edit().putBoolean("float_btn_enabled", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).toggleFloatingButton(enabled);
        }
        return enabled ? "Плавающая кнопка включена" : "Плавающая кнопка скрыта";
    }

    @JavascriptInterface
    public String applyStretchRatio(String ratio) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyResolutionRatio(ratio);
        }
        return "Разрешение " + ratio + " применено";
    }

    @JavascriptInterface
    public String resetDisplayToDefault() {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).resetResolution();
        }
        return "Разрешение сброшено по умолчанию";
    }

    @JavascriptInterface
    public String setHideOverlaysOnScreenshot(boolean enabled) {
        prefs.edit().putBoolean("stealth_overlays", enabled).apply();
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).updateOverlayStealthFlag(enabled);
            }
        });
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
                    "settings put secure long_press_timeout 140; " +
                    "settings put secure multi_press_timeout 120; " +
                    "setprop windowsmgr.max_events_per_sec 300; " +
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
        if (!isShizukuReady()) return enabled ? "Режим бюджеток включен" : "Обычный режим";

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
            String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
            return ApiManager.verifyLicense(key, androidId);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"blocked\":true}";
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
            activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
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
