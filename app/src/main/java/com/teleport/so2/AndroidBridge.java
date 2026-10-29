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
    public String handleShizukuCardClick() {
        if (!isShizukuReady()) {
            requestShizukuPermission();
            return "Откройте окно Shizuku и нажмите «Разрешить»";
        } else {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).healBrokenTouchTimeouts();
            }
            return "Shizuku уже активен и готов к работе!";
        }
    }

    @JavascriptInterface
    public boolean toggleTeleport() {
        isRunning = !isRunning;
        prefs.edit().putBoolean("teleport_running", isRunning).apply();
        String preset = prefs.getString("selected_preset", "max_teleport");

        if (isRunning) {
            TeleportEngine.startTeleport(activity, preset);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setQuantumOverlayStrobe(true);
            }
        } else {
            TeleportEngine.stopTeleport();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setQuantumOverlayStrobe(false);
            }
        }
        return isRunning;
    }

    @JavascriptInterface
    public boolean isTeleportRunning() {
        return isRunning;
    }

    @JavascriptInterface
    public String applySensPreset(String presetId) {
        prefs.edit().putString("selected_preset", presetId).apply();
        isRunning = true;
        prefs.edit().putBoolean("teleport_running", true).apply();

        if ("awp".equalsIgnoreCase(presetId)) {
            TeleportEngine.setSmartAwpLock(true);
            TeleportEngine.updateParameters(4.60f, 4.70f, 4.40f, 44, 9, 0.96f, 8, 0, 14, 0.00f);
        } else if ("sharp".equalsIgnoreCase(presetId)) {
            TeleportEngine.setSmartAwpLock(false);
            TeleportEngine.updateParameters(5.00f, 5.00f, 5.00f, 48, 10, 0.90f, 2, 0, 0, 0.00f);
        } else if ("sticky".equalsIgnoreCase(presetId)) {
            TeleportEngine.setSmartAwpLock(false);
            TeleportEngine.updateParameters(4.80f, 4.90f, 4.70f, 45, 9, 0.99f, 9, 0, 4, 0.00f);
        } else {
            // max_teleport
            TeleportEngine.setSmartAwpLock(false);
            TeleportEngine.updateParameters(5.00f, 5.00f, 4.80f, 50, 10, 0.98f, 6, 0, 0, 0.00f);
        }

        TeleportEngine.startTeleport(activity, presetId);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setQuantumOverlayStrobe(true);
        }
        return "Пресет активирован! Телепорт включён.";
    }

    @JavascriptInterface
    public void saveCustomSlidersJson(String jsonStr) {
        try {
            prefs.edit().putString("custom_numbers_json", jsonStr).apply();
            JSONObject o = new JSONObject(jsonStr);
            TeleportEngine.updateParameters(
                    (float) o.optDouble("sharpness", 5.00),
                    (float) o.optDouble("sensX", 5.00),
                    (float) o.optDouble("sensY", 4.80),
                    o.optInt("chunks", 50),
                    o.optInt("strobeSpeed", 10),
                    (float) o.optDouble("straightening", 0.98),
                    o.optInt("stickStrength", 6),
                    o.optInt("deadZone", 0),
                    o.optInt("shotPause", 0),
                    (float) o.optDouble("accel", 0.00)
            );
            if (!isRunning) {
                isRunning = true;
                TeleportEngine.startTeleport(activity, "custom");
            }
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setQuantumOverlayStrobe(true);
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String setSmartAwpLock(boolean enabled) {
        prefs.edit().putBoolean("smart_awp_lock", enabled).apply();
        TeleportEngine.setSmartAwpLock(enabled);
        return enabled ? "Стоп-флик для AWM: ВКЛ" : "Стоп-флик: ВЫКЛ";
    }

    @JavascriptInterface
    public String setAntiRecoil(boolean enabled, int strength) {
        prefs.edit().putBoolean("anti_recoil_enabled", enabled).putInt("anti_recoil_strength", strength).apply();
        TeleportEngine.setAntiRecoilQuantization(enabled, strength);
        return enabled ? "Гашение отдачи (" + strength + "%): ВКЛ" : "Гашение отдачи: ВЫКЛ";
    }

    @JavascriptInterface
    public String setHzThermalGuard(boolean enabled) {
        prefs.edit().putBoolean("hz_thermal_guard", enabled).apply();
        if (!isShizukuReady()) return "Сначала активируйте Shizuku!";
        TeleportEngine.setHzThermalGuard(enabled);
        return enabled ? "Фиксация 120 Гц и защита от троттлинга: ВКЛ" : "Стандартный режим частот";
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
                    saveCustomSlidersJson(presetStr);
                }
            }
        } catch (Exception ignored) {}
        return res;
    }

    @JavascriptInterface
    public String setCrosshairType(String type) {
        prefs.edit().putString("crosshair_type", type).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateCrosshair(type);
        }
        return "off".equalsIgnoreCase(type) ? "Прицел выключен" : "Прицел поверх игры включён";
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
                return "Разрешите отображение поверх других окон!";
            }
        }
        prefs.edit().putBoolean("float_btn_enabled", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).toggleFloatingButton(enabled);
        }
        return enabled ? "Плавающая кнопка TP включена" : "Плавающая кнопка скрыта";
    }

    @JavascriptInterface
    public void setSelectedStretchRatioOnly(String ratio) {
        if (ratio != null && !ratio.trim().isEmpty()) {
            prefs.edit().putString("stretch_ratio", ratio.trim()).apply();
        }
    }

    @JavascriptInterface
    public String applyStretchRatio(String ratio) {
        String clean = (ratio == null || ratio.trim().isEmpty()) ? "4:3" : ratio.trim();
        prefs.edit().putString("stretch_ratio", clean).apply();
        if (!isShizukuReady()) {
            return "Кликните на Shizuku вверху справа для активации!";
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyResolutionRatio(clean);
        }
        return "Растяг " + clean + " успешно применён!";
    }

    @JavascriptInterface
    public String resetDisplayToDefault() {
        if (!isShizukuReady()) {
            return "Сначала активируйте Shizuku!";
        }
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).resetResolution();
        }
        return "Разрешение экрана сброшено по умолчанию";
    }

    @JavascriptInterface
    public String setHideOverlaysOnScreenshot(boolean enabled) {
        prefs.edit().putBoolean("stealth_overlays", enabled).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateOverlayStealthFlag(enabled);
        }
        return enabled ? "Защита от записи экрана (Stealth): ВКЛ" : "Stealth-режим: ВЫКЛ";
    }

    @JavascriptInterface
    public String setPixelOptimization(int percent) {
        prefs.edit().putInt("pixel_scale_pct", percent).apply();
        if (!isShizukuReady()) return "Сначала активируйте Shizuku!";
        TeleportEngine.setPixelDownscaleFactor(percent);
        return percent >= 100 ? "Пиксели: 100% (Стандарт)" : "Оптимизация пикселей: " + percent + "%";
    }

    @JavascriptInterface
    public String authorizeAndActivate(String nickname, String key) {
        try {
            String cleanNick = (nickname == null || nickname.trim().isEmpty()) ? "Player" : nickname.trim();
            String cleanKey = (key == null) ? "" : key.trim().toUpperCase();
            if (cleanKey.length() < 4) {
                return "{\"status\":\"error\",\"ok\":false,\"message\":\"Введите ваш логин и корректный ключ!\"}";
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
            return "{\"status\":\"error\",\"ok\":false,\"message\":\"Ошибка соединения с сервером\"}";
        }
    }

    @JavascriptInterface
    public void forceLogoutKey() {
        prefs.edit().remove("license_key").apply();
        ApiManager.invalidateSession();
        isRunning = false;
        TeleportEngine.stopTeleport();
    }

    @JavascriptInterface
    public String verifySavedLicenseOnStartup() {
        try {
            String key = prefs.getString("license_key", "");
            if (key == null || key.trim().length() < 4) {
                return "{\"status\":\"need_auth\",\"ok\":false,\"blocked\":false,\"message\":\"Введите ваш логин и ключ доступа\"}";
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
            int s = Math.min(dm.widthPixels, dm.heightPixels);
            int l = Math.max(dm.widthPixels, dm.heightPixels);
            obj.put("resolution", s + "x" + l);
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
            obj.put("license_key", prefs.getString("license_key", "АКТИВЕН"));
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
