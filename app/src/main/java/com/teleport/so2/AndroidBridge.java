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
        } else if (activity instanceof MainActivity) {
            ((MainActivity) activity).healBrokenTouchTimeouts();
        }
    }

    @JavascriptInterface
    public boolean isCalibrationRequired() {
        return !prefs.getBoolean("screen_calibrated_v2", false);
    }

    @JavascriptInterface
    public void completeScreenCalibration(float factor) {
        if (!ApiManager.isSessionVerified()) return;
        prefs.edit()
                .putBoolean("screen_calibrated_v2", true)
                .putFloat("screen_calib_factor", factor)
                .putBoolean("teleport_running", true)
                .apply();
        TeleportEngine.setCalibrationFactor(factor);
        int snap = prefs.getInt("tp_snap_power", 500);
        String preset = prefs.getString("selected_preset", "teleport");
        if (isShizukuReady()) {
            isRunning = true;
            TeleportEngine.startTeleport(activity, snap, preset);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).triggerCrosshairPulse();
            }
        }
    }

    @JavascriptInterface
    public boolean toggleTeleport() {
        if (!ApiManager.isSessionVerified()) {
            isRunning = false;
            return false;
        }
        isRunning = !isRunning;
        prefs.edit().putBoolean("teleport_running", isRunning).apply();
        int snap = prefs.getInt("tp_snap_power", 500);
        String preset = prefs.getString("selected_preset", "teleport");
        float calib = prefs.getFloat("screen_calib_factor", 2.10f);
        TeleportEngine.setCalibrationFactor(calib);

        if (isRunning) {
            TeleportEngine.startTeleport(activity, snap, preset);
        } else {
            TeleportEngine.stopTeleport();
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).triggerCrosshairPulse();
        }
        return isRunning;
    }

    @JavascriptInterface
    public boolean isTeleportRunning() {
        return isRunning && ApiManager.isSessionVerified();
    }

    @JavascriptInterface
    public void selectPreset(String presetId) {
        if (!ApiManager.isSessionVerified()) return;
        prefs.edit().putString("selected_preset", presetId).apply();
        int snap = prefs.getInt("tp_snap_power", 500);
        if ("awp".equalsIgnoreCase(presetId)) {
            TeleportEngine.setSmartAwpLock(true);
        }
        if (isShizukuReady()) {
            isRunning = true;
            prefs.edit().putBoolean("teleport_running", true).apply();
            TeleportEngine.startTeleport(activity, snap, presetId);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).triggerCrosshairPulse();
            }
        }
    }

    @JavascriptInterface
    public void setTeleportSnapPower(int snapPercent) {
        if (!ApiManager.isSessionVerified()) return;
        prefs.edit().putInt("tp_snap_power", snapPercent).apply();
        String preset = prefs.getString("selected_preset", "teleport");
        TeleportEngine.setStepQuantization(true, snapPercent / 18.0f);
        if (isShizukuReady()) {
            isRunning = true;
            prefs.edit().putBoolean("teleport_running", true).apply();
            TeleportEngine.startTeleport(activity, snapPercent, preset);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).triggerCrosshairPulse();
            }
        }
    }

    // === НОВЫЕ МЕТОДЫ OVERDRIVE EDITION (BUILD 401+) ===

    @JavascriptInterface
    public String setOverdriveMode(boolean enabled) {
        if (!ApiManager.isSessionVerified()) return "Сначала активируйте ключ!";
        prefs.edit().putBoolean("overdrive_unleashed", enabled).apply();
        TeleportEngine.setOverdriveUnleashed(enabled);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).triggerCrosshairPulse();
        }
        return enabled ? "OVERDRIVE 1000%: Чанки 50 + Буфер 2 Гц ВКЛ!" : "Стандартный режим телепорта";
    }

    @JavascriptInterface
    public String setVectorStraightening(boolean enabled) {
        if (!ApiManager.isSessionVerified()) return "Сначала активируйте ключ!";
        prefs.edit().putBoolean("vector_straight", enabled).apply();
        TeleportEngine.setVectorStraightening(enabled);
        return enabled ? "Выпрямление траектории (Горизонт-магнит): ВКЛ" : "Выпрямление: ВЫКЛ";
    }

    @JavascriptInterface
    public String setSmartAwpLock(boolean enabled) {
        if (!ApiManager.isSessionVerified()) return "Сначала активируйте ключ!";
        prefs.edit().putBoolean("smart_awp_lock", enabled).apply();
        TeleportEngine.setSmartAwpLock(enabled);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).triggerCrosshairPulse();
        }
        return enabled ? "Smart AWP-Lock: ВКЛ (Стоп-флик активен)" : "Smart AWP-Lock: ВЫКЛ";
    }

    @JavascriptInterface
    public String setAntiRecoil(boolean enabled, int strength) {
        if (!ApiManager.isSessionVerified()) return "Сначала активируйте ключ!";
        prefs.edit()
                .putBoolean("anti_recoil_enabled", enabled)
                .putInt("anti_recoil_strength", strength)
                .apply();
        TeleportEngine.setAntiRecoilQuantization(enabled, strength);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).triggerCrosshairPulse();
        }
        return enabled ? "Anti-Recoil Quantization (" + strength + "%): ВКЛ" : "Anti-Recoil: ВЫКЛ";
    }

    @JavascriptInterface
    public String setHzThermalGuard(boolean enabled) {
        prefs.edit().putBoolean("hz_thermal_guard", enabled).apply();
        if (!isShizukuReady()) return "Включите Shizuku!";
        TeleportEngine.setHzThermalGuard(enabled);
        return enabled ? "Hz / FPS Lock (120 Гц) + Thermal Guard: ВКЛ" : "Стандартный режим частот";
    }

    @JavascriptInterface
    public String setDynamicCrosshair(boolean enabled) {
        prefs.edit().putBoolean("dynamic_crosshair", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setDynamicCrosshairMode(enabled);
        }
        return enabled ? "Dynamic Dot Crosshair: ВКЛ" : "Динамическая индикация выключена";
    }

    @JavascriptInterface
    public String uploadCloudConfig(String title, String presetJson) {
        String key = prefs.getString("license_key", "");
        String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
        prefs.edit().putString("custom_numbers_json", presetJson).apply();
        return ApiManager.uploadCloudPreset(key, androidId, title, presetJson);
    }

    @JavascriptInterface
    public String downloadCloudConfig(String shareCode) {
        String key = prefs.getString("license_key", "");
        String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
        String res = ApiManager.downloadCloudPresetByCode(key, androidId, shareCode);
        try {
            JSONObject obj = new JSONObject(res);
            if (obj.has("preset")) {
                String presetStr = obj.optString("preset", "");
                if (!presetStr.isEmpty()) {
                    saveCustomNumbersJson(presetStr);
                }
            }
        } catch (Exception ignored) {}
        return res;
    }

    @JavascriptInterface
    public void saveCustomNumbersJson(String jsonStr) {
        try {
            prefs.edit().putString("custom_numbers_json", jsonStr).apply();
            JSONObject o = new JSONObject(jsonStr);
            TeleportEngine.setCustomNumbers(
                    (float) o.optDouble("sharpness", 5.00),
                    (float) o.optDouble("sensX", 5.00),
                    (float) o.optDouble("sensY", 4.80),
                    o.optInt("noiseCutoff", 0),
                    o.optInt("deadZone", 0),
                    o.optInt("prediction", 10),
                    o.optInt("axisStick", 5),
                    o.optInt("stickDelay", 0),
                    o.optInt("stickStrength", 4),
                    o.optInt("chunks", 50),
                    o.optInt("bypassChunks", 0),
                    o.optInt("chunkCap", 0),
                    o.optInt("chunksFaster", 0),
                    o.optInt("shotPause", 0),
                    (float) o.optDouble("accel", 0.45),
                    (float) o.optDouble("accelCurve", 0.01),
                    o.optInt("edgeRange", 0),
                    (float) o.optDouble("straightening", 0.98),
                    o.optInt("axisGrip", 6),
                    o.optInt("gripCap", 0)
            );
        } catch (Exception ignored) {}
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
    public void setSelectedStretchRatioOnly(String ratio) {
        if (ratio != null && !ratio.trim().isEmpty()) {
            prefs.edit().putString("stretch_ratio", ratio.trim()).apply();
        }
    }

    @JavascriptInterface
    public String applyStretchRatio(String ratio) {
        if (!ApiManager.isSessionVerified()) {
            return "Сначала активируйте лицензионный ключ!";
        }
        String clean = (ratio == null || ratio.trim().isEmpty()) ? "16:9" : ratio.trim();
        prefs.edit().putString("stretch_ratio", clean).apply();
        if (!isShizukuReady()) {
            return "Сначала активируйте Shizuku!";
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyResolutionRatio(clean);
        }
        return "Растяг " + clean + " + Квантовый Телепорт применён!";
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
    public boolean isOnboardingRequired() {
        String key = prefs.getString("license_key", "");
        String nick = prefs.getString("nickname", "");
        return key == null || key.trim().length() < 6 || nick == null || nick.trim().isEmpty();
    }

    @JavascriptInterface
    public String authorizeAndActivate(String nickname, String key) {
        try {
            if (nickname == null || nickname.trim().isEmpty() || key == null || key.trim().length() < 6) {
                return "{\"status\":\"error\",\"ok\":false,\"message\":\"Введите и никнейм, и лицензионный ключ!\"}";
            }
            String cleanNick = nickname.trim();
            String cleanKey = key.trim().toUpperCase();
            String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
            String res = ApiManager.activateLicense(cleanKey, androidId, cleanNick);
            JSONObject json = new JSONObject(res);
            boolean isOk = json.optBoolean("ok", false) || "ok".equalsIgnoreCase(json.optString("status"));
            if (isOk) {
                prefs.edit().putString("license_key", cleanKey).putString("nickname", cleanNick).apply();
            }
            return res;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка проверки ключа на сервере\"}";
        }
    }

    @JavascriptInterface
    public String verifySavedLicenseOnStartup() {
        try {
            String key = prefs.getString("license_key", "");
            if (key == null || key.trim().length() < 6) {
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false}";
            }
            String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
            return ApiManager.verifyLicense(key, androidId);
        } catch (Exception e) {
            return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false}";
        }
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
                obj.put("refresh_refresh", (int) activity.getDisplay().getRefreshRate());
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
