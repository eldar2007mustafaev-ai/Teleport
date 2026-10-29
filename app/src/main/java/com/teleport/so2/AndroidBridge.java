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
    private boolean isRunning = true;

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);

        // Принудительный сброс старого кэша один раз при переходе на Build 402, чтобы запросило ключ
        int lastAuthBuild = prefs.getInt("auth_build_ver", 0);
        if (lastAuthBuild < ApiManager.CLIENT_BUILD_VERSION) {
            prefs.edit()
                    .remove("license_key")
                    .putInt("auth_build_ver", ApiManager.CLIENT_BUILD_VERSION)
                    .apply();
            ApiManager.invalidateSession();
        }
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
                ((MainActivity) activity).setQuantumOverlayStrobe(true);
                ((MainActivity) activity).triggerCrosshairPulse();
            }
        }
    }

    @JavascriptInterface
    public boolean toggleTeleport() {
        isRunning = !isRunning;
        prefs.edit().putBoolean("teleport_running", isRunning).apply();
        int snap = prefs.getInt("tp_snap_power", 500);
        String preset = prefs.getString("selected_preset", "teleport");
        float calib = prefs.getFloat("screen_calib_factor", 2.40f);
        TeleportEngine.setCalibrationFactor(calib);

        if (isRunning) {
            TeleportEngine.startTeleport(activity, snap, preset);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setQuantumOverlayStrobe(true);
            }
        } else {
            TeleportEngine.stopTeleport();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setQuantumOverlayStrobe(false);
            }
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).triggerCrosshairPulse();
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
        int snap = prefs.getInt("tp_snap_power", 500);
        if ("awp".equalsIgnoreCase(presetId)) {
            TeleportEngine.setSmartAwpLock(true);
        } else {
            TeleportEngine.setSmartAwpLock(false);
        }
        if (isShizukuReady()) {
            isRunning = true;
            prefs.edit().putBoolean("teleport_running", true).apply();
            TeleportEngine.startTeleport(activity, snap, presetId);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setQuantumOverlayStrobe(!"smooth".equalsIgnoreCase(presetId));
                ((MainActivity) activity).triggerCrosshairPulse();
            }
        }
    }

    @JavascriptInterface
    public void setTeleportSnapPower(int snapPercent) {
        prefs.edit().putInt("tp_snap_power", snapPercent).apply();
        String preset = prefs.getString("selected_preset", "teleport");
        TeleportEngine.setStepQuantization(true, snapPercent / 15.0f);
        if (isShizukuReady()) {
            isRunning = true;
            prefs.edit().putBoolean("teleport_running", true).apply();
            TeleportEngine.startTeleport(activity, snapPercent, preset);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setQuantumOverlayStrobe(true);
                ((MainActivity) activity).triggerCrosshairPulse();
            }
        }
    }

    @JavascriptInterface
    public String setOverdriveMode(boolean enabled) {
        prefs.edit().putBoolean("overdrive_unleashed", enabled).apply();
        TeleportEngine.setOverdriveUnleashed(enabled);
        if (isShizukuReady()) {
            TeleportEngine.startTeleport(activity, 500, "teleport");
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setQuantumOverlayStrobe(enabled);
            ((MainActivity) activity).triggerCrosshairPulse();
        }
        return enabled ? "⚡ ТЕЛЕПОРТ 1000%: Чанки 50 + Строб 1 Гц ВКЛ!" : "Стандартный режим";
    }

    @JavascriptInterface
    public String setVectorStraightening(boolean enabled) {
        prefs.edit().putBoolean("vector_straight", enabled).apply();
        TeleportEngine.setVectorStraightening(enabled);
        return enabled ? "📏 Выпрямление (Горизонт-магнит): ВКЛ" : "Выпрямление: ВЫКЛ";
    }

    @JavascriptInterface
    public String setSmartAwpLock(boolean enabled) {
        prefs.edit().putBoolean("smart_awp_lock", enabled).apply();
        TeleportEngine.setSmartAwpLock(enabled);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).triggerCrosshairPulse();
        }
        return enabled ? "🎯 Smart AWP-Lock (Стоп-флик): ВКЛ" : "Smart AWP-Lock: ВЫКЛ";
    }

    @JavascriptInterface
    public String setAntiRecoil(boolean enabled, int strength) {
        prefs.edit()
                .putBoolean("anti_recoil_enabled", enabled)
                .putInt("anti_recoil_strength", strength)
                .apply();
        TeleportEngine.setAntiRecoilQuantization(enabled, strength);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).triggerCrosshairPulse();
        }
        return enabled ? "🌊 Anti-Recoil (" + strength + "%): ВКЛ" : "Anti-Recoil: ВЫКЛ";
    }

    @JavascriptInterface
    public String setHzThermalGuard(boolean enabled) {
        prefs.edit().putBoolean("hz_thermal_guard", enabled).apply();
        if (!isShizukuReady()) return "Включите Shizuku!";
        TeleportEngine.setHzThermalGuard(enabled);
        return enabled ? "🚀 120 Гц Lock + Разгон ядер: ВКЛ" : "Стандартные частоты";
    }

    @JavascriptInterface
    public String setDynamicCrosshair(boolean enabled) {
        prefs.edit().putBoolean("dynamic_crosshair", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setDynamicCrosshairMode(enabled);
        }
        return enabled ? "👁️ Dynamic Crosshair: ВКЛ" : "Динамический импульс выключен";
    }

    @JavascriptInterface
    public String uploadCloudConfig(String title, String presetJson) {
        String key = prefs.getString("license_key", "PRO");
        String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
        prefs.edit().putString("custom_numbers_json", presetJson).apply();
        return ApiManager.uploadCloudPreset(key, androidId, title, presetJson);
    }

    @JavascriptInterface
    public String downloadCloudConfig(String shareCode) {
        String key = prefs.getString("license_key", "PRO");
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
                    o.optInt("prediction", 12),
                    o.optInt("axisStick", 6),
                    o.optInt("stickDelay", 0),
                    o.optInt("stickStrength", 5),
                    o.optInt("chunks", 50),
                    o.optInt("bypassChunks", 0),
                    o.optInt("chunkCap", 0),
                    o.optInt("chunksFaster", 10),
                    o.optInt("shotPause", 0),
                    (float) o.optDouble("accel", 0.65),
                    (float) o.optDouble("accelCurve", 0.01),
                    o.optInt("edgeRange", 0),
                    (float) o.optDouble("straightening", 0.99),
                    o.optInt("axisGrip", 7),
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
        return "off".equalsIgnoreCase(type) ? "Прицел выключен" : "Прицел поверх игры: " + type.toUpperCase();
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
        return enabled ? "Плавающая кнопка Со2 включена" : "Кнопка Со2 скрыта";
    }

    @JavascriptInterface
    public void setSelectedStretchRatioOnly(String ratio) {
        if (ratio != null && !ratio.trim().isEmpty()) {
            prefs.edit().putString("stretch_ratio", ratio.trim()).apply();
        }
    }

    @JavascriptInterface
    public String applyStretchRatio(String ratio) {
        String clean = (ratio == null || ratio.trim().isEmpty()) ? "16:9" : ratio.trim();
        prefs.edit().putString("stretch_ratio", clean).apply();
        if (!isShizukuReady()) {
            return "Сначала включите Shizuku!";
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyResolutionRatio(clean);
        }
        return "Растяг " + clean + " + Квантовый Телепорт ВКЛ!";
    }

    @JavascriptInterface
    public String resetDisplayToDefault() {
        if (!isShizukuReady()) {
            return "Сначала включите Shizuku!";
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).resetResolution();
        }
        return "Размер экрана сброшен по умолчанию";
    }

    @JavascriptInterface
    public String setHideOverlaysOnScreenshot(boolean enabled) {
        prefs.edit().putBoolean("stealth_overlays", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateOverlayStealthFlag(enabled);
        }
        return enabled ? "Stealth-защита от записи: ВКЛ" : "Stealth-защита: ВЫКЛ";
    }

    @JavascriptInterface
    public String setPixelOptimization(int percent) {
        prefs.edit().putInt("pixel_scale_pct", percent).apply();
        if (!isShizukuReady()) return "Включите Shizuku для оптимизации пикселей!";
        TeleportEngine.setPixelDownscaleFactor(percent);
        return percent >= 100 ? "Пиксели: 100% (Стандарт)" : "Оптимизация пикселей: " + percent + "% (Буст FPS + Резкость)";
    }

    @JavascriptInterface
    public String setTouchBoost(boolean enabled) {
        prefs.edit().putBoolean("touch_boost", enabled).apply();
        if (!isShizukuReady()) return "Включите Shizuku!";
        if (enabled) {
            TeleportEngine.startTeleport(activity, 500, "teleport");
        }
        return enabled ? "Touch Boost (Zero-Delay): ВКЛ" : "Touch Boost: ВЫКЛ";
    }

    @JavascriptInterface
    public String setBudgetDeviceBoost(boolean enabled) {
        prefs.edit().putBoolean("budget_boost", enabled).apply();
        if (!isShizukuReady()) return "Включите Shizuku!";
        TeleportEngine.setHzThermalGuard(enabled);
        return enabled ? "Бустер FPS и разгон: ВКЛ" : "Бустер: ВЫКЛ";
    }

    @JavascriptInterface
    public void setDndMode(boolean enabled) {
        if (!isShizukuReady()) return;
        new Thread(() -> {
            ShizukuManager.execCommand(enabled ? "cmd notification set_dnd priority" : "cmd notification set_dnd off");
        }).start();
    }

    @JavascriptInterface
    public String authorizeAndActivate(String nickname, String key) {
        try {
            String cleanNick = (nickname == null || nickname.trim().isEmpty()) ? "ProPlayer" : nickname.trim();
            String cleanKey = (key == null) ? "" : key.trim().toUpperCase();
            if (cleanKey.length() < 4) {
                return "{\"status\":\"error\",\"ok\":false,\"message\":\"Введите корректный ключ из бота!\"}";
            }
            String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
            String res = ApiManager.activateLicense(cleanKey, androidId, cleanNick);
            JSONObject json = new JSONObject(res);
            boolean isOk = json.optBoolean("ok", false) || "ok".equalsIgnoreCase(json.optString("status"));
            if (isOk) {
                prefs.edit()
                        .putString("license_key", cleanKey)
                        .putString("nickname", cleanNick)
                        .putInt("auth_build_ver", ApiManager.CLIENT_BUILD_VERSION)
                        .apply();
                ApiManager.setSessionVerified(true);
            }
            return res;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка связи с сервером ключа\"}";
        }
    }

    @JavascriptInterface
    public void forceLogoutKey() {
        prefs.edit().remove("license_key").apply();
        ApiManager.invalidateSession();
        TeleportEngine.stopTeleport();
    }

    @JavascriptInterface
    public String verifySavedLicenseOnStartup() {
        try {
            String key = prefs.getString("license_key", "");
            if (key == null || key.trim().length() < 4) {
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Введите лицензионный ключ для запуска\"}";
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
