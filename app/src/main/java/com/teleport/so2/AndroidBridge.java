package com.so2teleport.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.widget.TextView;
import android.widget.Toast;

public class AndroidBridge {

    private final Context context;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Плавающая кнопка растяга «T»
    private WindowManager windowManager;
    private TextView floatingStretchBtn;
    private WindowManager.LayoutParams stretchParams;
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
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        TeleportEngine.init(context);
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

    // ================= 2. РАБОЧАЯ ПЛАВАЮЩАЯ КНОПКА РАСТЯГА «T» =================

    @JavascriptInterface
    public String activateStretchFloatingButton(String ratio) {
        if (ratio != null && !ratio.trim().isEmpty()) {
            this.selectedStretchRatio = ratio.trim();
        }
        prefs.edit().putString("saved_stretch_ratio", this.selectedStretchRatio).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission();
            return "NEED_PERMISSION";
        }

        mainHandler.post(() -> {
            try {
                if (isStretchOverlayShown && floatingStretchBtn != null) {
                    updateFloatingBtnAppearance();
                    Toast.makeText(context, "Кнопка «T» (" + selectedStretchRatio + ") уже на экране!", Toast.LENGTH_SHORT).show();
                    return;
                }

                floatingStretchBtn = new TextView(context);
                floatingStretchBtn.setText("T\n" + selectedStretchRatio);
                floatingStretchBtn.setTextColor(Color.WHITE);
                floatingStretchBtn.setTextSize(12f);
                floatingStretchBtn.setGravity(Gravity.CENTER);
                floatingStretchBtn.setIncludeFontPadding(false);

                int sizePx = dpToPx(52);
                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

                stretchParams = new WindowManager.LayoutParams(
                        sizePx,
                        sizePx,
                        overlayType,
                        flags,
                        PixelFormat.TRANSLUCENT
                );
                stretchParams.gravity = Gravity.TOP | Gravity.START;
                stretchParams.x = dpToPx(18);
                stretchParams.y = dpToPx(140);

                updateFloatingBtnAppearance();

                // Умный обработчик: отличает перетаскивание от клика (порог 12dp)
                floatingStretchBtn.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX, initialY;
                    private float initialTouchX, initialTouchY;
                    private boolean isDragging = false;
                    private final int touchSlop = dpToPx(10);

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
                                }
                                if (isDragging && windowManager != null && floatingStretchBtn != null) {
                                    stretchParams.x = initialX + dx;
                                    stretchParams.y = initialY + dy;
                                    try {
                                        windowManager.updateViewLayout(floatingStretchBtn, stretchParams);
                                    } catch (Exception ignored) {}
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                v.setScaleX(1.0f);
                                v.setScaleY(1.0f);
                                if (!isDragging) {
                                    toggleScreenStretchAction();
                                }
                                return true;
                        }
                        return false;
                    }
                });

                windowManager.addView(floatingStretchBtn, stretchParams);
                isStretchOverlayShown = true;
                Toast.makeText(context, "⚡ Кнопка «T» (" + selectedStretchRatio + ") запущена! Нажми на неё в игре", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "Ошибка создания кнопки: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        return "OK";
    }

    private void toggleScreenStretchAction() {
        if (!TeleportEngine.isShizukuReady()) {
            Toast.makeText(context, "⚠️ Включите Shizuku для работы растяга!", Toast.LENGTH_SHORT).show();
            TeleportEngine.requestShizukuPermissionIfNeeded();
            return;
        }

        isScreenCurrentlyStretched = !isScreenCurrentlyStretched;
        if (isScreenCurrentlyStretched) {
            boolean applied = TeleportEngine.applyAspectRatioStretch(selectedStretchRatio);
            if (applied) {
                Toast.makeText(context, "🔥 Растяг " + selectedStretchRatio + " ВКЛЮЧЕН!", Toast.LENGTH_SHORT).show();
            } else {
                isScreenCurrentlyStretched = false;
                Toast.makeText(context, "❌ Ошибка применения wm size (проверьте Shizuku)", Toast.LENGTH_SHORT).show();
            }
        } else {
            TeleportEngine.resetResolutionToNative();
            Toast.makeText(context, "🔄 Экран возвращен в родное разрешение", Toast.LENGTH_SHORT).show();
        }
        updateFloatingBtnAppearance();
    }

    private void updateFloatingBtnAppearance() {
        if (floatingStretchBtn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (isScreenCurrentlyStretched) {
            bg.setColor(Color.parseColor("#D900E676")); // Ярко-зелёный когда растяг активен
            bg.setStroke(dpToPx(2), Color.WHITE);
            floatingStretchBtn.setText("ON\n" + selectedStretchRatio);
        } else {
            bg.setColor(Color.parseColor("#CC9333EA")); // Фиолетовый в режиме ожидания
            bg.setStroke(dpToPx(2), Color.parseColor("#00F0FF"));
            floatingStretchBtn.setText("T\n" + selectedStretchRatio);
        }
        floatingStretchBtn.setBackground(bg);
    }

    @JavascriptInterface
    public void applyStretchDirectlyNow(String ratio) {
        if (ratio != null && !ratio.trim().isEmpty()) {
            selectedStretchRatio = ratio.trim();
        }
        mainHandler.post(() -> {
            if (!TeleportEngine.isShizukuReady()) {
                Toast.makeText(context, "⚠️ Сначала запустите Shizuku!", Toast.LENGTH_SHORT).show();
                TeleportEngine.requestShizukuPermissionIfNeeded();
                return;
            }
            boolean ok = TeleportEngine.applyAspectRatioStretch(selectedStretchRatio);
            isScreenCurrentlyStretched = ok;
            updateFloatingBtnAppearance();
            if (ok) {
                Toast.makeText(context, "✅ Экран растянут в " + selectedStretchRatio + "!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void resetResolution() {
        mainHandler.post(() -> {
            TeleportEngine.resetResolutionToNative();
            isScreenCurrentlyStretched = false;
            if (isStretchOverlayShown && floatingStretchBtn != null && windowManager != null) {
                try {
                    windowManager.removeView(floatingStretchBtn);
                } catch (Exception ignored) {}
                floatingStretchBtn = null;
                isStretchOverlayShown = false;
            }
            Toast.makeText(context, "Экран сброшен к родному разрешению, кнопка «T» убрана", Toast.LENGTH_SHORT).show();
        });
    }

    // ================= 3. СОХРАНЕНИЕ ВСЕХ НАСТРОЕК В ПАМЯТЬ ANDROID =================

    @JavascriptInterface
    public boolean saveFullConfigJson(String jsonConfig) {
        if (jsonConfig == null || jsonConfig.trim().isEmpty()) return false;
        prefs.edit().putString("full_user_config_v408", jsonConfig).apply();
        mainHandler.post(() ->
                Toast.makeText(context, "💾 Все настройки надёжно сохранены в память!", Toast.LENGTH_SHORT).show()
        );
        return true;
    }

    @JavascriptInterface
    public String loadFullConfigJson() {
        return prefs.getString("full_user_config_v408", "");
    }

    // ================= 4. РЕЖИМ ШИРОКИХ ХИТБОКСОВ (В 1 КЛИК) =================

    @JavascriptInterface
    public String setWideHitboxMode(boolean enabled, String ratio) {
        String targetRatio = (ratio != null && !ratio.isEmpty()) ? ratio : "4:3";
        prefs.edit().putBoolean("wide_hitbox_mode", enabled).apply();

        if (enabled) {
            // 1. Включаем максимальный захват осей и 360 Гц тач
            TeleportEngine.setTouchSamplingBoost(true, 360);
            TeleportEngine.setEnhancedRecoilFix(true, 92);
            TeleportEngine.enableWideHitboxQuantization(true);
            // 2. Выводим плавающую кнопку «T» с выбранным форматом растяга
            activateStretchFloatingButton(targetRatio);
            return "OK|" + targetRatio;
        } else {
            TeleportEngine.enableWideHitboxQuantization(false);
            TeleportEngine.resetResolutionToNative();
            return "OFF";
        }
    }

    // ================= 5. ОСНОВНЫЕ МЕТОДЫ TELEPORT ENGINE И SHIZUKU =================

    @JavascriptInterface
    public boolean isShizukuReady() {
        return TeleportEngine.isShizukuReady();
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

    // ================= 6. КАСТОМНЫЙ ПРИЦЕЛ =================

    @JavascriptInterface
    public void updateCrosshair(String type) {
        mainHandler.post(() -> {
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
            if (crosshairView != null) {
                crosshairView.setStyle(scalePct, opacityPct, hexColor);
            }
        });
    }

    @JavascriptInterface
    public void updateOverlayStealthFlag(boolean stealth) {
        this.stealthOverlayFlag = stealth;
    }

    // ================= 7. АВТОРИЗАЦИЯ И ПРОФИЛЬ УСТРОЙСТВА =================

    @JavascriptInterface
    public String getDeviceHwid() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return (androidId != null ? androidId.toUpperCase() : "SO2HWID001");
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        String res = dm.widthPixels + "x" + dm.heightPixels;
        return model + "|Android " + Build.VERSION.RELEASE + "|" + res;
    }

    @JavascriptInterface
    public String getSavedKey() {
        return prefs.getString("saved_license_key", "");
    }

    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (key == null || key.trim().length() < 5) {
            return "ERROR|Неверный формат ключа";
        }
        String cleanKey = key.trim().toUpperCase();
        prefs.edit().putString("saved_license_key", cleanKey).apply();
        return "OK|PRO ДОСТУП";
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        if (key == null || key.trim().length() < 5) {
            return "ERROR|Ключ не найден";
        }
        return "OK|PRO ДОСТУП";
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

    // Внутренний класс кастомного прицела (Zero-Lag Canvas)
    private static class CrosshairOverlayView extends View {
        private String type = "dot";
        private int scale = 50;
        private int opacity = 100;
        private int color = Color.parseColor("#00F0FF");
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

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
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if ("off".equalsIgnoreCase(type)) return;
            paint.setColor(color);
            paint.setAlpha(Math.max(25, Math.min(255, (int) (opacity * 2.55f))));
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float size = 8f + (scale / 100f) * 24f;
            float gap = 4f + (scale / 100f) * 8f;

            if ("dot".equalsIgnoreCase(type)) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawCircle(cx, cy, Math.max(4f, size * 0.28f), paint);
            } else if ("circle".equalsIgnoreCase(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, size * 0.65f, paint);
            } else if ("tshape".equalsIgnoreCase(type)) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawLine(cx - size, cy, cx - gap, cy, paint);
                canvas.drawLine(cx + gap, cy, cx + size, cy, paint);
                canvas.drawLine(cx, cy - size, cx, cy - gap, paint);
                canvas.drawLine(cx, cy + gap, cx, cy + size, paint);
            }
        }
    }
}
