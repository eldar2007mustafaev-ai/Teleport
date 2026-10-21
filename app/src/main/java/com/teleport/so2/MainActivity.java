package com.teleport.so2;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    public static final String APP_VERSION = "1.0.0";
    public static final String TELEGRAM_BOT_URL = "https://t.me/so2_teleport_bot";
    public static final String TELEGRAM_CHANNEL_URL = "https://t.me/so2_teleport_updates";

    private WebView webView;
    private int nativeLongSide = 2400;
    private int nativeShortSide = 1080;
    private int nativeDpi = 440;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureDisplayMetrics();
        ShizukuManager.startAutoStretchMonitor();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF03050D);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new AppJsBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void captureDisplayMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            Display d = wm.getDefaultDisplay();
            d.getRealMetrics(dm);
        } else {
            dm = getResources().getDisplayMetrics();
        }
        nativeLongSide = Math.max(dm.widthPixels, dm.heightPixels);
        nativeShortSide = Math.min(dm.widthPixels, dm.heightPixels);
        nativeDpi = dm.densityDpi;
        ProfileManager.saveNativeScreenDefaults(this, nativeLongSide, nativeShortSide, nativeDpi);
    }

    private void triggerWebViewTeleportFlash() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.onNativeTeleportSnap && window.onNativeTeleportSnap();", null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProfileManager.addTeleportCountToTotal(this, ShizukuManager.getSessionTeleports());
        ShizukuManager.stopTeleport();
        ShizukuManager.stopAutoStretchMonitor();
    }

    private boolean isServerVersionNewer(String currentVer, String serverVer) {
        if (serverVer == null || serverVer.isEmpty()) return false;
        try {
            String[] curParts = currentVer.trim().split("\\.");
            String[] srvParts = serverVer.trim().split("\\.");
            int len = Math.max(curParts.length, srvParts.length);
            for (int i = 0; i < len; i++) {
                int c = i < curParts.length ? Integer.parseInt(curParts[i].replaceAll("\\D", "")) : 0;
                int s = i < srvParts.length ? Integer.parseInt(srvParts[i].replaceAll("\\D", "")) : 0;
                if (s > c) return true;
                if (s < c) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public class AppJsBridge {

        @JavascriptInterface
        public boolean isOnboardingRequired() {
            return !ProfileManager.isOnboardingDone(MainActivity.this);
        }

        /**
         * Проверка ключа при каждом входе в приложение.
         * Если ключ удалён из keys.json (в TEST_MODE) или заблокирован на сервере — возвращает blocked = true.
         */
        @JavascriptInterface
        public String verifySavedLicenseOnStartup() {
            try {
                if (!ProfileManager.isOnboardingDone(MainActivity.this)) {
                    return "{\"status\":\"need_onboarding\",\"blocked\":false}";
                }
                String savedKey = ProfileManager.getSavedKey(MainActivity.this);
                if (savedKey == null || savedKey.isEmpty() || "-".equals(savedKey)) {
                    return "{\"status\":\"blocked\",\"blocked\":true}";
                }

                if (ApiManager.TEST_MODE) {
                    boolean validInAssets = KeyValidator.isKeyInLocalAssets(MainActivity.this, savedKey);
                    if (!validInAssets) {
                        ShizukuManager.stopTeleport();
                        return "{\"status\":\"blocked\",\"blocked\":true}";
                    }
                    return "{\"status\":\"ok\",\"blocked\":false}";
                } else {
                    String resp = ApiManager.checkKeyStatus(savedKey);
                    JSONObject json = new JSONObject(resp);
                    String st = json.optString("status", "");
                    String boundAid = json.optString("android_id", "");
                    String myAid = ProfileManager.getHardwareAndroidId(MainActivity.this);
                    if (!"active".equalsIgnoreCase(st) || (!boundAid.isEmpty() && !boundAid.equals(myAid))) {
                        ShizukuManager.stopTeleport();
                        return "{\"status\":\"blocked\",\"blocked\":true}";
                    }
                    return "{\"status\":\"ok\",\"blocked\":false}";
                }
            } catch (Exception e) {
                return "{\"status\":\"blocked\",\"blocked\":true}";
            }
        }

        /**
         * Проверка обновлений при запуске (GET /api/get_version).
         */
        @JavascriptInterface
        public String checkVersionOnStartup() {
            try {
                String raw = ApiManager.getServerVersion();
                JSONObject json = new JSONObject(raw);
                String srvVersion = json.optString("version", APP_VERSION);
                String link = json.optString("link", TELEGRAM_CHANNEL_URL);
                boolean updateNeeded = isServerVersionNewer(APP_VERSION, srvVersion);

                JSONObject out = new JSONObject();
                out.put("update_required", updateNeeded);
                out.put("current_version", APP_VERSION);
                out.put("server_version", srvVersion);
                out.put("link", link);
                return out.toString();
            } catch (Exception e) {
                return "{\"update_required\":false,\"current_version\":\"" + APP_VERSION + "\"}";
            }
        }

        @JavascriptInterface
        public void openExternalLink(String url) {
            try {
                String target = (url != null && !url.isEmpty()) ? url : TELEGRAM_BOT_URL;
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean isShizukuReady() {
            return ShizukuManager.isShizukuReady();
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            ShizukuManager.requestPermission();
        }

        @JavascriptInterface
        public String handleShizukuCardClick() {
            try {
                if (!Shizuku.pingBinder()) {
                    PackageManager pm = getPackageManager();
                    Intent launchIntent = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launchIntent);
                        return "OPENED_SHIZUKU_APP";
                    } else {
                        return "SHIZUKU_NOT_INSTALLED";
                    }
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(1001);
                    return "REQUESTING_PERMISSION";
                }
                return "ACTIVE";
            } catch (Throwable t) {
                return "ERROR";
            }
        }

        @JavascriptInterface
        public String scanDevice() {
            captureDisplayMetrics();
            return DeviceScanner.scanAndCalculateOptimal(MainActivity.this);
        }

        @JavascriptInterface
        public String authorizeAndActivate(String nickname, String key) {
            try {
                String cleanNick = nickname != null ? nickname.trim() : "";
                String cleanKey = key != null ? key.trim().toUpperCase() : "";

                if (cleanNick.length() < 2) {
                    return "{\"status\":\"error\",\"message\":\"Введите никнейм (минимум 2 символа).\"}";
                }
                if (!KeyValidator.isFormatValid(cleanKey)) {
                    return "{\"status\":\"error\",\"message\":\"Неверный формат ключа! Пример: TP-YRE-133700\"}";
                }
                if (ApiManager.TEST_MODE && !KeyValidator.isKeyInLocalAssets(MainActivity.this, cleanKey)) {
                    return "{\"status\":\"error\",\"message\":\"Ключ не найден или заблокирован в keys.json!\"}";
                }

                String checkResp = ApiManager.checkNickname(cleanNick, cleanKey);
                JSONObject checkJson = new JSONObject(checkResp);
                if (!"ok".equalsIgnoreCase(checkJson.optString("status"))) {
                    return "{\"status\":\"error\",\"message\":\"" + checkJson.optString("message", "Никнейм занят или ключ недействителен.") + "\"}";
                }

                String aid = ProfileManager.getHardwareAndroidId(MainActivity.this);
                String actResp = ApiManager.activateKey(cleanNick, cleanKey, aid);
                JSONObject actJson = new JSONObject(actResp);
                if (!"ok".equalsIgnoreCase(actJson.optString("status"))) {
                    String reason = actJson.optString("reason", "");
                    if ("bound_to_other_device".equals(reason) || "key_already_used".equals(reason)) {
                        return "{\"status\":\"error\",\"message\":\"Этот ключ уже привязан к другому устройству!\"}";
                    }
                    return "{\"status\":\"error\",\"message\":\"" + actJson.optString("message", "Ошибка активации ключа.") + "\"}";
                }

                ProfileManager.saveActivatedProfile(MainActivity.this, cleanNick, cleanKey);
                return "{\"status\":\"ok\",\"android_id\":\"" + aid + "\"}";
            } catch (Exception e) {
                return "{\"status\":\"error\",\"message\":\"Ошибка: " + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public String getProfileData() {
            return ProfileManager.getProfileJson(MainActivity.this);
        }

        @JavascriptInterface
        public void selectPreset(String presetId) {
            ProfileManager.savePreset(MainActivity.this, presetId);
            if (ShizukuManager.isTeleportRunning()) {
                int c = ProfileManager.getChunk(MainActivity.this);
                int t = ProfileManager.getThreshold(MainActivity.this);
                int z = ProfileManager.getZone(MainActivity.this);
                int h = ProfileManager.getPollHz(MainActivity.this);
                ShizukuManager.updateLiveConfig(c, t, z, h, nativeLongSide, nativeShortSide);
            }
        }

        @JavascriptInterface
        public boolean toggleTeleport() {
            captureDisplayMetrics();
            if (ShizukuManager.isTeleportRunning()) {
                ProfileManager.addTeleportCountToTotal(MainActivity.this, ShizukuManager.getSessionTeleports());
                ShizukuManager.stopTeleport();
                return false;
            } else {
                int c = ProfileManager.getChunk(MainActivity.this);
                int t = ProfileManager.getThreshold(MainActivity.this);
                int z = ProfileManager.getZone(MainActivity.this);
                int h = ProfileManager.getPollHz(MainActivity.this);
                return ShizukuManager.startTeleport(
                        c, t, z, h, nativeLongSide, nativeShortSide,
                        MainActivity.this::triggerWebViewTeleportFlash
                );
            }
        }

        @JavascriptInterface
        public boolean isTeleportRunning() {
            return ShizukuManager.isTeleportRunning();
        }

        @JavascriptInterface
        public int getSessionTeleports() {
            return ShizukuManager.getSessionTeleports();
        }

        @JavascriptInterface
        public String applyStretchRatio(String ratioCode) {
            int defLong = ProfileManager.getNativeLong(MainActivity.this, nativeLongSide);
            int defShort = ProfileManager.getNativeShort(MainActivity.this, nativeShortSide);
            return ShizukuManager.setTargetStretchRatio(ratioCode, defShort, defLong);
        }

        @JavascriptInterface
        public String resetDisplayToDefault() {
            return ShizukuManager.resetScreenToDefault();
        }

        @JavascriptInterface
        public String setCrosshairType(String type) {
            if ("off".equalsIgnoreCase(type)) {
                runOnUiThread(() -> CrosshairOverlay.hide(MainActivity.this));
                return "Прицел выключен";
            }
            if (ShizukuManager.isShizukuReady()) {
                ShizukuManager.execCommand("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            }
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return "Выдайте разрешение «Поверх других окон»";
            }
            runOnUiThread(() -> CrosshairOverlay.show(MainActivity.this, type));
            return "Прицел активирован: " + type.toUpperCase();
        }

        @JavascriptInterface
        public String boostFps(boolean aggressive) {
            return ShizukuManager.runFpsBooster(aggressive);
        }

        @JavascriptInterface
        public String cleanRam() {
            return ShizukuManager.cleanRamBeforeGame();
        }

        @JavascriptInterface
        public void setDndMode(boolean enabled) {
            ShizukuManager.setDoNotDisturb(enabled);
        }
    }
}
