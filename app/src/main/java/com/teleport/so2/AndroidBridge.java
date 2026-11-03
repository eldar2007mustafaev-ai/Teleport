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
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class AndroidBridge {

    private final Context context;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final SharedPreferences legacyPrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Плавающая кнопка растяга «T» и нижняя корзина удаления
    private WindowManager windowManager;
    private TextView floatingStretchBtn;
    private FrameLayout trashContainer;
    private BridgeTrashView trashPillView;
    private WindowManager.LayoutParams stretchParams;
    private WindowManager.LayoutParams trashParams;
    private boolean isStretchOverlayShown = false;
    private boolean isScreenCurrentlyStretched = false;
    private String selectedStretchRatio = "4:3";

    // Плавающий кастомный прицел
    private CrosshairOverlayView crosshairView;
    private WindowManager.LayoutParams crosshairParams;
    private boolean isCrosshairShown = false;
    private boolean stealthOverlayFlag = false;

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.prefs = context.getSharedPreferences("SO2TeleportPrefs_V408", Context.MODE_PRIVATE);
        this.legacyPrefs = context.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        TeleportEngine.init(context);
        TeleportEngine.requestShizukuPermissionIfNeeded();
    }

    // ================= 1. РАЗРЕШЕНИЕ ПОВЕРХ ДРУГИХ ОКОН =================

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
                    Toast.makeText(context, "Выдайте разрешение «Поверх других окон» для кнопки Растяга «T»!", Toast.LENGTH_LONG).show();
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
                Toast.makeText(context, "✅ Разрешение «Поверх других окон» уже активно!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= 2. ПЛАВАЮЩАЯ КНОПКА РАСТЯГА «T» + КОРЗИНА УДАЛЕНИЯ СНИЗУ =================

    @JavascriptInterface
    public String activateStretchFloatingButton(String ratio) {
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
                    Toast.makeText(context, "⚡ Формат кнопки «T» обновлён на " + selectedStretchRatio, Toast.LENGTH_SHORT).show();
                    return;
                }

                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                // 1. Создаём нижнюю неоновую корзину удаления
                trashContainer = new FrameLayout(context);
                trashPillView = new BridgeTrashView(context);
                FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(dpToPx(240), dpToPx(52), Gravity.CENTER);
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
                        dpToPx(84),
                        overlayType,
                        trashFlags,
                        PixelFormat.TRANSLUCENT
                );
                trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                trashParams.y = dpToPx(16);

                // 2. Создаём саму плавающую кнопку «T»
                floatingStretchBtn = new TextView(context);
                floatingStretchBtn.setText("T\n" + selectedStretchRatio);
                floatingStretchBtn.setTextColor(Color.WHITE);
                floatingStretchBtn.setTextSize(12f);
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
                stretchParams.y = dpToPx(160);

                updateFloatingBtnAppearance();

                floatingStretchBtn.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX, initialY;
                    private float initialTouchX, initialTouchY;
                    private boolean isDragging = false;
                    private final int touchSlop = dpToPx(12);

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
                                    resetResolution();
                                    return true;
                                }

                                if (!isDragging) {
                                    toggleScreenStretchAction();
                                }
                                return true;
                        }
                        return false;
                    }
                });

                windowManager.addView(trashContainer, trashParams);
                windowManager.addView(floatingStretchBtn, stretchParams);
                isStretchOverlayShown = true;
                Toast.makeText(context, "⚡ Кнопка «T» (" + selectedStretchRatio + ") запущена! Перетяни вниз для удаления", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "Ошибка кнопки: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

    private void toggleScreenStretchAction() {
        boolean shizukuOk = TeleportEngine.isShizukuReady() || ShizukuManager.isPermissionGranted();
        if (!shizukuOk) {
            Toast.makeText(context, "⚠️ Включите и разрешите Shizuku для работы растяга!", Toast.LENGTH_SHORT).show();
            TeleportEngine.requestShizukuPermissionIfNeeded();
            return;
        }

        isScreenCurrentlyStretched = !isScreenCurrentlyStretched;
        if (isScreenCurrentlyStretched) {
            boolean applied = applyStretchWithRotationLock(selectedStretchRatio);
            if (applied) {
                Toast.makeText(context, "🔥 Растяг " + selectedStretchRatio + " ВКЛЮЧЕН!", Toast.LENGTH_SHORT).show();
            } else {
                isScreenCurrentlyStretched = false;
                Toast.makeText(context, "❌ Ошибка применения растяга (проверьте Shizuku)", Toast.LENGTH_SHORT).show();
            }
        } else {
            resetStretchOnlyKeepButton();
            Toast.makeText(context, "🔄 Обычный экран восстановлен", Toast.LENGTH_SHORT).show();
        }
        updateFloatingBtnAppearance();
    }

    private boolean applyStretchWithRotationLock(String ratio) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);

            int savedShort = legacyPrefs.getInt("native_short_px", 0);
            int savedLong = legacyPrefs.getInt("native_long_px", 0);
            if (savedShort <= 0 || savedLong <= 0) {
                savedShort = Math.min(dm.widthPixels, dm.heightPixels);
                savedLong = Math.max(dm.widthPixels, dm.heightPixels);
                legacyPrefs.edit()
                        .putInt("native_short_px", savedShort)
                        .putInt("native_long_px", savedLong)
                        .apply();
            }

            int baseShort = savedShort > 0 ? savedShort : 1080;
            int targetLong;
            String clean = (ratio == null) ? "4:3" : ratio.trim();
            switch (clean) {
                case "5:4":
                    targetLong = Math.round(baseShort * 1.25f);
                    break;
                case "4:3":
                    targetLong = Math.round(baseShort * 1.3333f);
                    break;
                case "3:2":
                    targetLong = Math.round(baseShort * 1.50f);
                    break;
                case "16:10":
                    targetLong = Math.round(baseShort * 1.60f);
                    break;
                case "16:9":
                    targetLong = Math.round(baseShort * 1.7777f);
                    break;
                default:
                    targetLong = Math.round(baseShort * 1.3333f);
                    break;
            }
            if ((targetLong & 1) == 1) targetLong++;

            int currentRot = windowManager.getDefaultDisplay().getRotation();
            final int lockUserRot = (currentRot == Surface.ROTATION_270) ? 3 : 1;
            final int fShort = baseShort;
            final int fLong = targetLong;

            new Thread(() -> {
                String cmd = "cmd window set-ignore-orientation-request false; "
                        + "cmd window set-fix-to-user-rotation disabled; "
                        + "settings put system accelerometer_rotation 0; "
                        + "settings put system user_rotation " + lockUserRot + "; "
                        + "settings put system peak_refresh_rate 120.0; "
                        + "settings put system min_refresh_rate 120.0; "
                        + "settings put secure refresh_rate_mode 2; "
                        + "wm size " + fShort + "x" + fLong;
                ShizukuManager.execCommand(cmd);
            }).start();
            return true;
        } catch (Throwable t) {
            return TeleportEngine.applyAspectRatioStretch(ratio);
        }
    }

    private void resetStretchOnlyKeepButton() {
        new Thread(() -> {
            String cmd = "wm size reset; "
                    + "cmd window set-ignore-orientation-request false; "
                    + "cmd window set-fix-to-user-rotation default; "
                    + "settings put system accelerometer_rotation 0";
            ShizukuManager.execCommand(cmd);
        }).start();
    }

    private void updateFloatingBtnAppearance() {
        if (floatingStretchBtn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(16));
        if (isScreenCurrentlyStretched) {
            bg.setColor(Color.parseColor("#E610B981"));
            bg.setStroke(dpToPx(2), Color.WHITE);
            floatingStretchBtn.setText("ON\n" + selectedStretchRatio);
        } else {
            bg.setColor(Color.parseColor("#D90B1622"));
            bg.setStroke(dpToPx(2), Color.parseColor("#00E5FF"));
            floatingStretchBtn.setText("T\n" + selectedStretchRatio);
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
            resetStretchOnlyKeepButton();
            TeleportEngine.resetResolutionToNative();
            isScreenCurrentlyStretched = false;

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
            Toast.makeText(context, "🗑️ Кнопка «T» убрана, экран сброшен", Toast.LENGTH_SHORT).show();
        });
    }

    // ================= 3. СОХРАНЕНИЕ ВСЕХ НАСТРОЕК В ПАМЯТЬ =================

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

    // ================= 4. МЕТОДЫ TELEPORT ENGINE И SHIZUKU =================

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
        TeleportEngine.updateParameters(sharp, sx, sy, chunks, chunkFast, straight, stick, grid, pause, accel);
    }

    @JavascriptInterface
    public void setTouchSamplingBoost(boolean enabled, int hz) {
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
        TeleportEngine.setEnhancedRecoilFix(enabled, power);
    }

    @JavascriptInterface
    public void setAutoGameDpi(boolean enabled, int dpi) {
        TeleportEngine.setAutoGameDpi(enabled, dpi);
    }

    @JavascriptInterface
    public void setPixelDownscaleFactor(int pct) {
        TeleportEngine.setPixelDownscaleFactor(pct);
    }

    @JavascriptInterface
    public String runSmartDeviceScan() {
        return TeleportEngine.runSmartDeviceScan(context);
    }

    // ================= 5. КАСТОМНЫЙ ПРИЦЕЛ =================

    @JavascriptInterface
    public void updateCrosshair(String type) {
        mainHandler.post(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).updateCrosshair(type);
                return;
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
                crosshairParams = new WindowManager.LayoutParams(
                        dpToPx(90), dpToPx(90),
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).updateCrosshairStyle(scalePct, opacityPct, hexColor);
                return;
            }
            if (crosshairView != null) {
                crosshairView.setStyle(scalePct, opacityPct, hexColor);
            }
        });
    }

    @JavascriptInterface
    public void updateOverlayStealthFlag(boolean stealth) {
        this.stealthOverlayFlag = stealth;
        legacyPrefs.edit().putBoolean("stealth_overlays", stealth).apply();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateOverlayStealthFlag(stealth);
        }
        mainHandler.post(() -> {
            try {
                if (isStretchOverlayShown && floatingStretchBtn != null && stretchParams != null) {
                    if (stealth) {
                        stretchParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                    } else {
                        stretchParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    }
                    windowManager.updateViewLayout(floatingStretchBtn, stretchParams);
                }
            } catch (Exception ignored) {}
        });
    }

    // ================= 6. ПРОФИЛЬ И HWID =================

    @JavascriptInterface
    public String getDeviceHwid() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return (androidId != null ? androidId.toUpperCase() : "39E220A438DEDB68");
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        String model = Build.MODEL;
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        int longPx = Math.max(dm.widthPixels, dm.heightPixels);
        return model + "|" + shortPx + "x" + longPx + " (120 Гц)";
    }

    @JavascriptInterface
    public String getSavedKey() {
        return prefs.getString("saved_license_key", "TP90D-HHG-125841");
    }

    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (key == null || key.trim().length() < 5) {
            return "ERROR|Неверный формат ключа";
        }
        String cleanKey = key.trim().toUpperCase();
        prefs.edit().putString("saved_license_key", cleanKey).apply();
        return "OK|АКТИВНА (89 дн. 7 ч.)";
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        if (key == null || key.trim().length() < 5) {
            return "ERROR|Ключ не найден";
        }
        return "OK|АКТИВНА (89 дн. 7 ч.)";
    }

    @JavascriptInterface
    public void logoutLicenseKey() {
        prefs.edit().remove("saved_license_key").apply();
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

    // Внутренний класс нижней неоновой корзины удаления кнопки «T»
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
            textPaint.setTextSize(12.5f * d);
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

            String label = hovered ? "🗑️ ОТПУСТИТЕ ДЛЯ УДАЛЕНИЯ" : "🗑️ УДАЛИТЬ КНОПКУ «T»";
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, getWidth() / 2f, cy, textPaint);
        }
    }

    // Внутренний класс кастомного прицела (Zero-Lag Canvas)
    private static class CrosshairOverlayView extends View {
        private String type = "dot";
        private int scale = 50;
        private int opacity = 100;
        private int color = Color.parseColor("#00F0FF");
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CrosshairOverlayView(Context context) {
            super(context);
            paint.setStrokeWidth(5f);
        }

        public void setCrosshairType(String t) {
            this.type = t;
            invalidate();
        }

        public void setStyle(int sc, int op, String hex) {
            this.scale = sc;
            this.opacity = op;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if ("off".equalsIgnoreCase(type)) return;
            paint.setColor(color);
            paint.setAlpha(Math.max(25, Math.min(255, (int) (opacity * 2.55f))));
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float size = 8f + (scale / 100f) * 24f;
            float gap = 4f + (scale / 100f) * 8f;

            if ("dot".equalsIgnoreCase(type)) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, Math.max(4f, size * 0.28f), paint);
            } else if ("circle".equalsIgnoreCase(type)) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, size * 0.65f, paint);
            } else if ("tshape".equalsIgnoreCase(type)) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                canvas.drawLine(cx, cy - size, cx, cy - gap, paint);
                canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
            }
        }
    }
}
