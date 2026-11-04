package com.teleport.so2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AndroidBridge {

    private final Context context;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final SharedPreferences legacyPrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();

    private WindowManager windowManager;
    private TextView floatingStretchBtn;
    private FrameLayout trashContainer;
    private BridgeTrashView trashPillView;
    private WindowManager.LayoutParams stretchParams;
    private WindowManager.LayoutParams trashParams;
    private boolean isStretchOverlayShown = false;
    private boolean isBoxBarsActive = false;
    private String selectedStretchRatio = "4:3";

    private CrosshairOverlayView crosshairView;
    private WindowManager.LayoutParams crosshairParams;
    private boolean isCrosshairShown = false;
    private boolean stealthOverlayFlag = false;

    private volatile String lastServerSubscriptionText = "АКТИВНА (PRO ДОСТУП)";

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.prefs = context.getSharedPreferences("SO2TeleportPrefs_V408", Context.MODE_PRIVATE);
        this.legacyPrefs = context.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        TeleportEngine.init(context);
        TeleportEngine.requestShizukuPermissionIfNeeded();
        startLicenseWatchdog();
    }

    /**
     * Список твоих личных и ранее выданных ключей из assets/keys.json,
     * чтобы ни один существующий покупатель не потерял доступ при обновлении APK.
     */
    private boolean isLegacyOrOwnerKey(String cleanKey) {
        String[] ownerKeys = {
                "TP90D-HHG-125841",
                "TP-EOW-371500",
                "TP-YRE-133700",
                "TP-SO2-999999",
                "TP-PRO-777888",
                "TP-VIP-100200",
                "TP-RAH-404213"
        };
        for (String k : ownerKeys) {
            if (k.equalsIgnoreCase(cleanKey)) return true;
        }
        try {
            InputStream is = context.getAssets().open("keys.json");
            Scanner s = new Scanner(is).useDelimiter("\\A");
            String jsonStr = s.hasNext() ? s.next() : "";
            is.close();
            JSONObject obj = new JSONObject(jsonStr);
            JSONArray arr = obj.optJSONArray("valid_keys");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    if (cleanKey.equalsIgnoreCase(arr.getString(i).trim())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Фоновый сторож: каждые 20 секунд проверяет на сервере ТГ-бота, не закончился ли ключ
    private void startLicenseWatchdog() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                final String savedKey = prefs.getString("saved_license_key", "");
                if (savedKey != null && !savedKey.trim().isEmpty()) {
                    networkExecutor.execute(() -> {
                        String raw = ApiManager.verifyLicense(savedKey.trim(), getDeviceHwid());
                        try {
                            JSONObject res = new JSONObject(raw);
                            String status = res.optString("status", "");
                            boolean explicitlyExpiredOrBlocked = "expired".equalsIgnoreCase(status)
                                    || "blocked".equalsIgnoreCase(status)
                                    || "device_mismatch".equalsIgnoreCase(status)
                                    || res.optBoolean("blocked", false);

                            if (explicitlyExpiredOrBlocked && !isLegacyOrOwnerKey(savedKey.trim())) {
                                forceKickExpiredUser(res.optString("message", "Срок действия вашего ключа истёк!"));
                            }
                        } catch (Exception ignored) {}
                    });
                }
                mainHandler.postDelayed(this, 20000);
            }
        }, 20000);
    }

    private void forceKickExpiredUser(String reasonToast) {
        prefs.edit().remove("saved_license_key").apply();
        ApiManager.invalidateSession();
        TeleportEngine.stopTeleport();

        mainHandler.post(() -> {
            if (floatingStretchBtn != null && windowManager != null) {
                try { windowManager.removeView(floatingStretchBtn); } catch (Exception ignored) {}
                floatingStretchBtn = null;
            }
            if (trashContainer != null && windowManager != null) {
                try { windowManager.removeView(trashContainer); } catch (Exception ignored) {}
                trashContainer = null;
                trashPillView = null;
            }
            isStretchOverlayShown = false;

            if (isCrosshairShown && crosshairView != null && windowManager != null) {
                try { windowManager.removeView(crosshairView); } catch (Exception ignored) {}
                crosshairView = null;
                isCrosshairShown = false;
            }

            if (activity instanceof MainActivity) {
                ((MainActivity) activity).toggleFloatingButton(false);
                ((MainActivity) activity).updateCrosshair("off");
            }

            Toast.makeText(context, "⛔ " + reasonToast, Toast.LENGTH_LONG).show();

            if (activity != null && !activity.isFinishing()) {
                try {
                    View content = activity.findViewById(android.R.id.content);
                    if (content instanceof android.view.ViewGroup) {
                        android.view.ViewGroup vg = (android.view.ViewGroup) content;
                        if (vg.getChildCount() > 0 && vg.getChildAt(0) instanceof android.webkit.WebView) {
                            android.webkit.WebView wv = (android.webkit.WebView) vg.getChildAt(0);
                            String safeMsg = reasonToast.replace("'", "").replace("\"", "");
                            wv.evaluateJavascript(
                                    "try { if(typeof forceShowKickScreen==='function') forceShowKickScreen('" + safeMsg + "'); } catch(e){}",
                                    null
                            );
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        mainHandler.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                try {
                    Toast.makeText(context, "Выдайте разрешение «Поверх других окон» для кнопки «TP»!", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.getPackageName())
                    );
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(fallback);
                }
            } else {
                Toast.makeText(context, "✅ Разрешение «Поверх других окон» активно!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= ПЛАВАЮЩАЯ КНОПКА «TP» С ДВУХШАГОВЫМ РАСТЯГОМ И КОРЗИНОЙ =================

    @JavascriptInterface
    public String activateStretchFloatingButton(String ratio) {
        if (getSavedKey().isEmpty()) {
            return "NEED_AUTH";
        }
        if (ratio != null && !ratio.trim().isEmpty()) {
            this.selectedStretchRatio = ratio.trim();
        }
        prefs.edit().putString("saved_stretch_ratio", this.selectedStretchRatio).apply();
        legacyPrefs.edit()
                .putString("stretch_ratio", this.selectedStretchRatio)
                .putBoolean("float_btn_enabled", true)
                .apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission();
            return "NEED_PERMISSION";
        }

        TeleportEngine.requestShizukuPermissionIfNeeded();

        mainHandler.post(() -> {
            try {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).toggleFloatingButton(false);
                }

                if (isStretchOverlayShown && floatingStretchBtn != null) {
                    updateFloatingBtnAppearance();
                    Toast.makeText(context, "⚡ Кнопка «TP» обновлена на формат " + selectedStretchRatio, Toast.LENGTH_SHORT).show();
                    return;
                }

                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                trashContainer = new FrameLayout(context);
                trashPillView = new BridgeTrashView(context);
                FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(dpToPx(250), dpToPx(52), Gravity.CENTER);
                trashContainer.addView(trashPillView, pillLp);
                trashContainer.setVisibility(View.GONE);

                int trashFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) {
                    trashFlags |= WindowManager.LayoutParams.FLAG_SECURE;
                }

                trashParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        dpToPx(86),
                        overlayType,
                        trashFlags,
                        PixelFormat.TRANSLUCENT
                );
                trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                trashParams.y = dpToPx(16);

                floatingStretchBtn = new TextView(context);
                floatingStretchBtn.setText("TP\n" + selectedStretchRatio);
                floatingStretchBtn.setTextColor(Color.WHITE);
                floatingStretchBtn.setTextSize(11.5f);
                floatingStretchBtn.setGravity(Gravity.CENTER);
                floatingStretchBtn.setIncludeFontPadding(false);

                int sizePx = dpToPx(54);
                int btnFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) {
                    btnFlags |= WindowManager.LayoutParams.FLAG_SECURE;
                }

                stretchParams = new WindowManager.LayoutParams(
                        sizePx,
                        sizePx,
                        overlayType,
                        btnFlags,
                        PixelFormat.TRANSLUCENT
                );
                stretchParams.gravity = Gravity.TOP | Gravity.START;
                stretchParams.x = dpToPx(20);
                stretchParams.y = dpToPx(150);

                isBoxBarsActive = false;
                updateFloatingBtnAppearance();

                floatingStretchBtn.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX, initialY;
                    private float initialTouchX, initialTouchY;
                    private boolean isDragging = false;
                    private final int touchSlop = dpToPx(11);

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                initialX = stretchParams.x;
                                initialY = stretchParams.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                isDragging = false;
                                v.setScaleX(0.92f);
                                v.setScaleY(0.92f);
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                int dx = (int) (event.getRawX() - initialTouchX);
                                int dy = (int) (event.getRawY() - initialTouchY);
                                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                                    isDragging = true;
                                    if (trashContainer != null && trashContainer.getVisibility() != View.VISIBLE) {
                                        trashContainer.setAlpha(0f);
                                        trashContainer.setVisibility(View.VISIBLE);
                                        trashContainer.animate().alpha(1f).setDuration(130).start();
                                    }
                                }
                                if (isDragging && windowManager != null && floatingStretchBtn != null) {
                                    stretchParams.x = initialX + dx;
                                    stretchParams.y = initialY + dy;
                                    try {
                                        windowManager.updateViewLayout(floatingStretchBtn, stretchParams);
                                    } catch (Exception ignored) {}

                                    if (trashPillView != null) {
                                        trashPillView.setHovered(isTouchOverTrashZone(event.getRawX(), event.getRawY()));
                                    }
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                v.setScaleX(1.0f);
                                v.setScaleY(1.0f);

                                boolean droppedInTrash = isDragging && isTouchOverTrashZone(event.getRawX(), event.getRawY());
                                if (trashPillView != null) {
                                    trashPillView.setHovered(false);
                                }
                                if (trashContainer != null) {
                                    trashContainer.setVisibility(View.GONE);
                                }

                                if (droppedInTrash) {
                                    removeFloatingTpButtonOnly();
                                    return true;
                                }

                                if (!isDragging) {
                                    toggleTwoStepStretchAction();
                                }
                                return true;
                        }
                        return false;
                    }
                });

                windowManager.addView(trashContainer, trashParams);
                windowManager.addView(floatingStretchBtn, stretchParams);
                isStretchOverlayShown = true;
                Toast.makeText(context, "⚡ Кнопка «TP» (" + selectedStretchRatio + ") запущена!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "Ошибка кнопки TP: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        return "OK";
    }

    private boolean isTouchOverTrashZone(float rawX, float rawY) {
        DisplayMetrics dm = new DisplayMetrics();
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            windowManager.getDefaultDisplay().getRealMetrics(dm);
        } else {
            dm = context.getResources().getDisplayMetrics();
        }
        int screenH = dm.heightPixels;
        if (trashPillView != null && trashContainer != null && trashContainer.getVisibility() == View.VISIBLE) {
            int[] loc = new int[2];
            trashPillView.getLocationOnScreen(loc);
            int left = loc[0] - dpToPx(35);
            int top = loc[1] - dpToPx(35);
            int right = loc[0] + trashPillView.getWidth() + dpToPx(35);
            int bottom = loc[1] + trashPillView.getHeight() + dpToPx(45);
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                return true;
            }
        }
        return rawY >= (screenH - dpToPx(105));
    }

    private void toggleTwoStepStretchAction() {
        if (getSavedKey().isEmpty()) {
            forceKickExpiredUser("Ключ не активен!");
            return;
        }
        boolean shizukuOk = TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted();
        if (!shizukuOk) {
            Toast.makeText(context, "⚠️ Разрешите доступ в Shizuku для работы растяга!", Toast.LENGTH_SHORT).show();
            TeleportEngine.requestShizukuPermissionIfNeeded();
            return;
        }

        if (!isBoxBarsActive) {
            boolean ok = TeleportEngine.applyAspectRatioStretch(selectedStretchRatio);
            if (ok) {
                isBoxBarsActive = true;
                Toast.makeText(context, "⬛ Шаг 1: Чёрные полосы (" + selectedStretchRatio + ") включены! Дождись загрузки и нажми TP снова", Toast.LENGTH_SHORT).show();
            }
        } else {
            TeleportEngine.releaseBoxToFullStretch();
            isBoxBarsActive = false;
            Toast.makeText(context, "🔥 Шаг 2: Экран растянут! Теперь перетяни кнопку TP вниз в корзину", Toast.LENGTH_SHORT).show();
        }
        updateFloatingBtnAppearance();
    }

    private void removeFloatingTpButtonOnly() {
        mainHandler.post(() -> {
            if (isBoxBarsActive) {
                TeleportEngine.releaseBoxToFullStretch();
                isBoxBarsActive = false;
            }
            if (floatingStretchBtn != null && windowManager != null) {
                try { windowManager.removeView(floatingStretchBtn); } catch (Exception ignored) {}
                floatingStretchBtn = null;
            }
            if (trashContainer != null && windowManager != null) {
                try { windowManager.removeView(trashContainer); } catch (Exception ignored) {}
                trashContainer = null;
                trashPillView = null;
            }
            isStretchOverlayShown = false;
            Toast.makeText(context, "🗑️ Кнопка «TP» удалена, приятной катки!", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateFloatingBtnAppearance() {
        if (floatingStretchBtn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (isBoxBarsActive) {
            bg.setColor(Color.parseColor("#E610B981"));
            bg.setStroke(dpToPx(2), Color.WHITE);
            floatingStretchBtn.setText("ON\n" + selectedStretchRatio);
        } else {
            bg.setColor(Color.parseColor("#D90B1622"));
            bg.setStroke(dpToPx(2), Color.parseColor("#00E5FF"));
            floatingStretchBtn.setText("TP\n" + selectedStretchRatio);
        }
        floatingStretchBtn.setBackground(bg);
    }

    @JavascriptInterface
    public void applyStretchDirectlyNow(String ratio) {
        activateStretchFloatingButton(ratio);
    }

    @JavascriptInterface
    public void resetResolution() {
        legacyPrefs.edit().putBoolean("float_btn_enabled", false).apply();
        mainHandler.post(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).toggleFloatingButton(false);
            }
            TeleportEngine.resetResolutionToNative();
            isBoxBarsActive = false;

            if (floatingStretchBtn != null && windowManager != null) {
                try { windowManager.removeView(floatingStretchBtn); } catch (Exception ignored) {}
                floatingStretchBtn = null;
            }
            if (trashContainer != null && windowManager != null) {
                try { windowManager.removeView(trashContainer); } catch (Exception ignored) {}
                trashContainer = null;
                trashPillView = null;
            }
            isStretchOverlayShown = false;
            Toast.makeText(context, "🔄 Кнопка «TP» убрана, родное разрешение возвращено", Toast.LENGTH_SHORT).show();
        });
    }

    @JavascriptInterface
    public boolean saveFullConfigJson(String jsonConfig) {
        if (jsonConfig == null || jsonConfig.trim().isEmpty()) return false;
        prefs.edit().putString("full_user_config_v408", jsonConfig).apply();
        return true;
    }

    @JavascriptInterface
    public String loadFullConfigJson() {
        return prefs.getString("full_user_config_v408", "");
    }

    @JavascriptInterface
    public String setWideHitboxMode(boolean enabled, String ratio) {
        if (getSavedKey().isEmpty()) return "NEED_AUTH";
        String targetRatio = (ratio != null && !ratio.isEmpty()) ? ratio : "4:3";
        prefs.edit().putBoolean("wide_hitbox_mode", enabled).apply();

        if (enabled) {
            TeleportEngine.setTouchSamplingBoost(true, 360);
            TeleportEngine.setEnhancedRecoilFix(true, 95);
            TeleportEngine.enableWideHitboxQuantization(true);
            activateStretchFloatingButton(targetRatio);
            return "OK|" + targetRatio;
        } else {
            TeleportEngine.enableWideHitboxQuantization(false);
            resetResolution();
            return "OFF";
        }
    }

    // ================= МЕТОДЫ TELEPORT ENGINE И НОВЫЕ УЛУЧШАЙЗЕРЫ =================

    @JavascriptInterface
    public boolean isShizukuReady() {
        return TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted();
    }

    @JavascriptInterface
    public void healTouch() {
        TeleportEngine.requestShizukuPermissionIfNeeded();
        TeleportEngine.healTouchAndRestart();
    }

    @JavascriptInterface
    public void startTeleport(String presetId) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.startTeleport(presetId);
    }

    @JavascriptInterface
    public void stopTeleport() {
        TeleportEngine.stopTeleport();
    }

    @JavascriptInterface
    public void updateParameters(
            double sharp, double sx, double sy,
            int chunks, int chunkFast, double straight,
            int stick, int grid, int pause, double accel
    ) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.updateParameters(sharp, sx, sy, chunks, chunkFast, straight, stick, grid, pause, accel);
    }

    @JavascriptInterface
    public void setRawInputNoSmoothing(boolean enabled) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.setRawInputNoSmoothing(enabled);
    }

    @JavascriptInterface
    public void setVulkanGpuTurbo(boolean enabled) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.setVulkanGpuTurbo(enabled);
    }

    @JavascriptInterface
    public void setNetPingStabilizer(boolean enabled) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.setNetPingStabilizer(enabled);
    }

    @JavascriptInterface
    public void setTouchSamplingBoost(boolean enabled, int hz) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.setTouchSamplingBoost(enabled, hz);
    }

    @JavascriptInterface
    public String performRamBoost() {
        return TeleportEngine.performRamBoost(context);
    }

    @JavascriptInterface
    public void setHzThermalGuard(boolean enabled) {
        TeleportEngine.setHzThermalGuard(enabled);
    }

    @JavascriptInterface
    public void setEnhancedRecoilFix(boolean enabled, int power) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.setEnhancedRecoilFix(enabled, power);
    }

    @JavascriptInterface
    public void setAutoGameDpi(boolean enabled, int dpi) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.setAutoGameDpi(enabled, dpi);
    }

    @JavascriptInterface
    public void setPixelDownscaleFactor(int pct) {
        if (getSavedKey().isEmpty()) return;
        TeleportEngine.setPixelDownscaleFactor(pct);
    }

    @JavascriptInterface
    public String runSmartDeviceScan() {
        return TeleportEngine.runSmartDeviceScan(context);
    }

    // ================= PRO КАСТОМНЫЙ ПРИЦЕЛ =================

    @JavascriptInterface
    public void updateCrosshair(String type) {
        if (getSavedKey().isEmpty() && !"off".equalsIgnoreCase(type)) return;
        mainHandler.post(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).updateCrosshair("off");
            }
            if ("off".equalsIgnoreCase(type)) {
                if (isCrosshairShown && crosshairView != null && windowManager != null) {
                    try { windowManager.removeView(crosshairView); } catch (Exception ignored) {}
                    crosshairView = null;
                    isCrosshairShown = false;
                }
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                requestOverlayPermission();
                return;
            }
            if (!isCrosshairShown || crosshairView == null) {
                crosshairView = new CrosshairOverlayView(context);
                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) {
                    flags |= WindowManager.LayoutParams.FLAG_SECURE;
                }
                crosshairParams = new WindowManager.LayoutParams(
                        dpToPx(120), dpToPx(120),
                        overlayType,
                        flags,
                        PixelFormat.TRANSLUCENT
                );
                crosshairParams.gravity = Gravity.CENTER;
                try {
                    windowManager.addView(crosshairView, crosshairParams);
                    isCrosshairShown = true;
                } catch (Exception ignored) {}
            }
            if (crosshairView != null) {
                crosshairView.setCrosshairType(type);
            }
        });
    }

    @JavascriptInterface
    public void updateCrosshairStyle(int scalePct, int opacityPct, String hexColor) {
        mainHandler.post(() -> {
            if (crosshairView != null) {
                crosshairView.setStyle(scalePct, opacityPct, hexColor);
            }
        });
    }

    @JavascriptInterface
    public void updateCrosshairAdvanced(
            String type, String hexColor, int opacityPct,
            int lengthDp, int thicknessDp, int gapDp,
            int dotSizeDp, boolean showOutline
    ) {
        if ("off".equalsIgnoreCase(type)) {
            updateCrosshair("off");
            return;
        }
        if (getSavedKey().isEmpty()) return;
        updateCrosshair(type);
        mainHandler.post(() -> {
            if (crosshairView != null) {
                crosshairView.setAdvancedConfig(type, hexColor, opacityPct, lengthDp, thicknessDp, gapDp, dotSizeDp, showOutline);
            }
        });
    }

    @JavascriptInterface
    public void updateOverlayStealthFlag(boolean stealth) {
        this.stealthOverlayFlag = stealth;
        legacyPrefs.edit().putBoolean("stealth_overlays", stealth).apply();
        mainHandler.post(() -> {
            try {
                if (isStretchOverlayShown && floatingStretchBtn != null && stretchParams != null) {
                    if (stealth) stretchParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                    else stretchParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    windowManager.updateViewLayout(floatingStretchBtn, stretchParams);
                }
                if (isCrosshairShown && crosshairView != null && crosshairParams != null) {
                    if (stealth) crosshairParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                    else crosshairParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    windowManager.updateViewLayout(crosshairView, crosshairParams);
                }
            } catch (Exception ignored) {}
        });
    }

    // ================= АВТОРИЗАЦИЯ: СЕРВЕР БОТА + ЗАЩИТА СТАРЫХ КЛЮЧЕЙ ПОКУПАТЕЛЕЙ =================

    @JavascriptInterface
    public String getDeviceHwid() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return (androidId != null ? androidId.trim().toLowerCase() : "39e220a438dedb68");
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        String model = Build.MODEL;
        int shortPx = TeleportEngine.getNativeShortSide();
        int longPx = TeleportEngine.getNativeLongSide();
        return model + "|" + shortPx + "x" + longPx + " (120 Гц)";
    }

    @JavascriptInterface
    public String getSavedKey() {
        String savedKey = prefs.getString("saved_license_key", "");
        if (savedKey == null || savedKey.trim().isEmpty()) {
            // Подхватываем ключ из старого хранилища при обновлении поверх старой версии APK
            savedKey = legacyPrefs.getString("license_key", "");
            if (savedKey != null && !savedKey.trim().isEmpty()) {
                prefs.edit().putString("saved_license_key", savedKey.trim().toUpperCase()).apply();
            }
        }
        return savedKey == null ? "" : savedKey.trim().toUpperCase();
    }

    @JavascriptInterface
    public String getSavedNickname() {
        String nick = prefs.getString("saved_player_nick", "");
        if (nick == null || nick.trim().isEmpty()) {
            nick = legacyPrefs.getString("nickname", "");
        }
        return nick == null ? "" : nick.trim();
    }

    @JavascriptInterface
    public void savePlayerNickname(String nick) {
        if (nick != null && !nick.trim().isEmpty()) {
            prefs.edit().putString("saved_player_nick", nick.trim()).apply();
            legacyPrefs.edit().putString("nickname", nick.trim()).apply();
        }
    }

    private String formatSubscriptionLabel(JSONObject res, String cleanKey) {
        String exp = res.optString("expires_in", res.optString("days_left", res.optString("time_left", "")));
        if (exp != null && !exp.isEmpty() && !exp.equalsIgnoreCase("null")) {
            if (exp.toUpperCase().contains("АКТИВНА")) return exp;
            return "АКТИВНА (" + exp + ")";
        }
        if (cleanKey.startsWith("TP-1HR") || cleanKey.contains("1H")) {
            return "АКТИВНА (ТЕСТ 1 ЧАС)";
        }
        if (cleanKey.startsWith("TP90D")) {
            return "АКТИВНА (90 ДНЕЙ)";
        }
        return "АКТИВНА (30 ДНЕЙ)";
    }

    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (key == null || key.trim().length() < 5) {
            return "ERROR|Введите лицензионный ключ из Telegram-бота";
        }
        final String cleanKey = key.trim().toUpperCase();
        final String hwid = getDeviceHwid();
        final String nick = getSavedNickname().isEmpty() ? "Player" : getSavedNickname();

        try {
            Callable<String> task = () -> ApiManager.activateLicense(cleanKey, hwid, nick);
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(4500, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            boolean ok = res.optBoolean("ok", false)
                    || "ok".equalsIgnoreCase(status)
                    || "active".equalsIgnoreCase(status);

            if (ok && !res.optBoolean("blocked", false)) {
                ApiManager.setSessionVerified(true);
                prefs.edit().putString("saved_license_key", cleanKey).apply();
                ProfileManager.saveActivatedProfile(context, nick, cleanKey);
                String label = formatSubscriptionLabel(res, cleanKey);
                lastServerSubscriptionText = label;
                return "OK|" + label;
            }

            // Если это твой ключ или ключ из старой базы покупателей, не блокируем вход
            if (isLegacyOrOwnerKey(cleanKey)) {
                ApiManager.setSessionVerified(true);
                prefs.edit().putString("saved_license_key", cleanKey).apply();
                ProfileManager.saveActivatedProfile(context, nick, cleanKey);
                String label = cleanKey.startsWith("TP90D") ? "АКТИВНА (90 ДНЕЙ)" : "АКТИВНА (30 ДНЕЙ)";
                lastServerSubscriptionText = label;
                return "OK|" + label;
            }

            return "ERROR|" + res.optString("message", "Ключ не найден или привязан к другому устройству!");
        } catch (Exception e) {
            if (isLegacyOrOwnerKey(cleanKey)) {
                ApiManager.setSessionVerified(true);
                prefs.edit().putString("saved_license_key", cleanKey).apply();
                ProfileManager.saveActivatedProfile(context, nick, cleanKey);
                return "OK|АКТИВНА (30 ДНЕЙ)";
            }
            return "ERROR|Нет связи с сервером Telegram-бота. Проверьте интернет!";
        }
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        final String cleanKey = (key != null && !key.trim().isEmpty())
                ? key.trim().toUpperCase()
                : getSavedKey();

        if (cleanKey.length() < 5) {
            forceKickExpiredUser("Введите лицензионный ключ");
            return "EXPIRED|Введите ключ";
        }

        try {
            Callable<String> task = () -> ApiManager.verifyLicense(cleanKey, getDeviceHwid());
            Future<String> future = networkExecutor.submit(task);
            String raw = future.get(4200, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(raw);
            String status = res.optString("status", "");

            if ("offline".equalsIgnoreCase(status)) {
                return "OFFLINE|" + lastServerSubscriptionText;
            }

            boolean blockedOrExpired = "expired".equalsIgnoreCase(status)
                    || "blocked".equalsIgnoreCase(status)
                    || "device_mismatch".equalsIgnoreCase(status)
                    || res.optBoolean("blocked", false);

            if (blockedOrExpired) {
                if (isLegacyOrOwnerKey(cleanKey)) {
                    ApiManager.setSessionVerified(true);
                    return "OK|" + lastServerSubscriptionText;
                }
                String msg = res.optString("message", "Срок действия вашего ключа закончился!");
                forceKickExpiredUser(msg);
                return "EXPIRED|" + msg;
            }

            boolean ok = res.optBoolean("ok", false)
                    || "ok".equalsIgnoreCase(status)
                    || "active".equalsIgnoreCase(status);

            if (ok) {
                ApiManager.setSessionVerified(true);
                String label = formatSubscriptionLabel(res, cleanKey);
                lastServerSubscriptionText = label;
                return "OK|" + label;
            }

            if (isLegacyOrOwnerKey(cleanKey)) {
                ApiManager.setSessionVerified(true);
                return "OK|" + lastServerSubscriptionText;
            }

            String msg = res.optString("message", "Срок действия ключа истёк!");
            forceKickExpiredUser(msg);
            return "EXPIRED|" + msg;
        } catch (Exception e) {
            return "OFFLINE|" + lastServerSubscriptionText;
        }
    }

    @JavascriptInterface
    public void logoutLicenseKey() {
        forceKickExpiredUser("Вы вышли из аккаунта");
    }

    @JavascriptInterface
    public void openTelegramBot() {
        mainHandler.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SO2TeleportShop_bot"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Exception ignored) {}
        });
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static class BridgeTrashView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean hovered = false;

        public BridgeTrashView(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2f * d);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * d);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void setHovered(boolean h) {
            if (this.hovered != h) {
                this.hovered = h;
                animate().scaleX(h ? 1.08f : 1.0f).scaleY(h ? 1.08f : 1.0f).setDuration(110).start();
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float pad = 6f;
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            if (hovered) {
                bgPaint.setColor(Color.parseColor("#EE4C0519"));
                borderPaint.setColor(Color.parseColor("#FF2E93"));
                textPaint.setColor(Color.parseColor("#FFE4E6"));
            } else {
                bgPaint.setColor(Color.parseColor("#E60B1622"));
                borderPaint.setColor(Color.parseColor("#00E5FF"));
                textPaint.setColor(Color.WHITE);
            }
            canvas.drawRoundRect(rect, 999f, 999f, bgPaint);
            canvas.drawRoundRect(rect, 999f, 999f, borderPaint);

            String label = hovered ? "🗑️ ОТПУСТИТЕ ДЛЯ УДАЛЕНИЯ" : "🗑️ УДАЛИТЬ КНОПКУ «TP»";
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, getWidth() / 2f, cy, textPaint);
        }
    }

    private static class CrosshairOverlayView extends View {
        private String type = "cross";
        private int opacity = 100;
        private int lengthDp = 12;
        private int thicknessDp = 3;
        private int gapDp = 4;
        private int dotSizeDp = 3;
        private boolean outline = true;
        private int color = Color.parseColor("#00E5FF");

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CrosshairOverlayView(Context context) {
            super(context);
            outlinePaint.setColor(Color.BLACK);
        }

        public void setCrosshairType(String t) {
            this.type = t;
            invalidate();
        }

        public void setStyle(int scalePct, int opacityPct, String hex) {
            this.lengthDp = Math.max(4, Math.round(4 + (scalePct / 100f) * 20f));
            this.opacity = opacityPct;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        public void setAdvancedConfig(
                String t, String hex, int op,
                int len, int thick, int gap, int dot, boolean out
        ) {
            this.type = t;
            this.opacity = op;
            this.lengthDp = len;
            this.thicknessDp = thick;
            this.gapDp = gap;
            this.dotSizeDp = dot;
            this.outline = out;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if ("off".equalsIgnoreCase(type)) return;

            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float lenPx = lengthDp * d;
            float thickPx = Math.max(1.5f, thicknessDp * 0.85f * d);
            float gapPx = gapDp * d;
            float dotRadPx = Math.max(1.5f, dotSizeDp * 0.75f * d);

            int alpha = Math.max(25, Math.min(255, Math.round(opacity * 2.55f)));
            paint.setColor(color);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(thickPx);

            outlinePaint.setAlpha(Math.min(220, alpha));
            outlinePaint.setStrokeWidth(thickPx + 2.2f * d);

            if ("dot".equalsIgnoreCase(type)) {
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, dotRadPx + 1.2f * d, outlinePaint);
                }
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dotRadPx, paint);
                return;
            }

            if ("circle".equalsIgnoreCase(type) || "circledot".equalsIgnoreCase(type)) {
                float rad = Math.max(4f * d, lenPx * 0.85f);
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(cx, cy, rad, outlinePaint);
                }
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, rad, paint);

                if ("circledot".equalsIgnoreCase(type)) {
                    if (outline) {
                        outlinePaint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy, dotRadPx + 1.2f * d, outlinePaint);
                    }
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, dotRadPx, paint);
                }
                return;
            }

            if ("box".equalsIgnoreCase(type)) {
                float half = Math.max(4f * d, lenPx * 0.75f);
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(cx - half, cy - half, cx + half, cy + half, outlinePaint);
                }
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
                return;
            }

            boolean hasTop = !"tshape".equalsIgnoreCase(type);
            if (outline) {
                outlinePaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, outlinePaint);
                canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, outlinePaint);
                canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, outlinePaint);
                if (hasTop) {
                    canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, outlinePaint);
                }
            }

            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, paint);
            canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, paint);
            canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, paint);
            if (hasTop) {
                canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, paint);
            }

            if ("crossdot".equalsIgnoreCase(type)) {
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, dotRadPx + 1.2f * d, outlinePaint);
                }
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dotRadPx, paint);
            }
        }
    }
}
