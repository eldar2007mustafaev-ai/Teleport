package com.teleport.so2;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AndroidBridge {

    private static final String PREF_FIRST_LOGIN_DONE = "v420_manual_login_done";
    private static final String PLAIN_FALLBACK_KEY = "bound_device_license_v12";

    private static boolean isNativeLoaded = false;
    static {
        try {
            System.loadLibrary("so2");
            isNativeLoaded = true;
        } catch (Throwable e) {
            isNativeLoaded = false;
        }
    }

    private static native void nativeInitBridge(Context context);
    private static native boolean nativeIsClientTampered(Context context);
    private static native boolean nativeVerifyAssetIntegrity(Context context);
    private static native String nativeGetDeviceHwid(Context context);
    private static native String nativeGetEncryptedEndpoint(int mode);
    private static native String nativeGateShizukuCmd(String rawCmd);
    private static native String nativeGetKeystoreAlias(int slot);
    private static native boolean nativeUnlockOfflineVipMode();
    private static native String nativeComputeHwid(String drmHex, String androidId, String boardInfo);
    private static native String nativeComputeDailyHmac(String payload, String dayUtc);
    private static native boolean nativeVerifyServerResponseSignature(String cleanKey, String hwid, String rawJson, String dayUtc);
    private static native boolean nativeVerifyServerHmac(String cleanKey, String hwid, String status, String serverSig, String dayUtc);
    private static native String nativeActivateLicense(Context context, String key, String nick);
    private static native void nativeForceKick(Context context, boolean killProcess);
    private static native boolean nativeIsHtmlTampered(byte[] htmlBytes);
    private static native boolean nativeIsBlacklisted(String candidate);
    private static native void nativeSetSessionVerified(String cleanKey, boolean verified);
    private static native boolean nativeIsSessionVerified();
    private static native String nativeGetSessionKey();
    private static native void nativeForceTerminate();

    private static native void nativeConfigureTouchLayer(float stickiness, float heaviness, float sharpness, float sprayPull, float aimScale);
    private static native void nativeResetFinger(int pointerId);
    private static native float[] nativeStabilizeTouchDelta(int pointerId, float rawDx, float rawDy);

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
    private boolean isTrashAttached = false;
    private boolean isBoxBarsActive = false;
    private String selectedStretchRatio = "4:3";

    private CrosshairOverlayView crosshairView;
    private WindowManager.LayoutParams crosshairParams;
    private boolean isCrosshairShown = false;
    private int savedCrosshairOffsetX = 0;
    private int savedCrosshairOffsetY = 0;
    private boolean stealthOverlayFlag = false;

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.prefs = context.getSharedPreferences("SO2TeleportPrefs_V410", Context.MODE_PRIVATE);
        this.legacyPrefs = context.getSharedPreferences("so2_teleport_prefs", Context.MODE_PRIVATE);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        try {
            if (isNativeLoaded) {
                nativeInitBridge(this.context);
            }
        } catch (Throwable ignored) {}

        try {
            TeleportEngine.setServerAuthorizedFromVerifiedHmac(true);
            TeleportEngine.init(context);
            TeleportEngine.requestShizukuPermissionIfNeeded();
        } catch (Throwable ignored) {}
    }

    public static String getEncryptedServerEndpoint(int mode) {
        if (!isNativeLoaded || mode < 0 || mode > 2) {
            if (mode == 1) return "http://45.10.165.75:8443";
            if (mode == 2) return "ws://45.10.165.75:8080/ws/voice";
            return "http://45.10.165.75:8080";
        }
        try {
            String url = nativeGetEncryptedEndpoint(mode);
            if (url != null && !url.isEmpty()) return url;
        } catch (Throwable ignored) {}
        return mode == 1 ? "http://45.10.165.75:8443" : (mode == 2 ? "ws://45.10.165.75:8080/ws/voice" : "http://45.10.165.75:8080");
    }

    public static String gateCommandThroughNative(String rawCmd) {
        return rawCmd == null ? "" : rawCmd;
    }

    private void execGatedShizuku(String rawCmd) {
        if (rawCmd != null && !rawCmd.isEmpty()) {
            TeleportEngine.execShizukuCommand(rawCmd);
        }
    }

    public static String getNativeHardwareHwid(Context ctx) {
        if (isNativeLoaded && ctx != null) {
            try {
                String h = nativeGetDeviceHwid(ctx);
                if (h != null && h.length() >= 8) return h;
            } catch (Throwable ignored) {}
        }
        try {
            if (ctx != null) {
                String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (aid != null && !aid.isEmpty()) return aid.toLowerCase(Locale.US);
            }
        } catch (Throwable ignored) {}
        return "so2device_" + Build.BOARD.toLowerCase(Locale.US);
    }

    public static void configureNativeTouchLayer(float stick, float heavy, float sharp, float pull, float scale) {
        if (!isNativeLoaded) return;
        try {
            nativeConfigureTouchLayer(stick, heavy, sharp, pull, scale);
        } catch (Throwable ignored) {}
    }

    public static float[] stabilizeTouchDeltaNative(int pointerId, float dx, float dy) {
        if (!isNativeLoaded) return new float[]{dx, dy};
        try {
            float[] res = nativeStabilizeTouchDelta(pointerId, dx, dy);
            return (res != null && res.length == 2) ? res : new float[]{dx, dy};
        } catch (Throwable ignored) {
            return new float[]{dx, dy};
        }
    }

    public static String signPayloadNative(String payload, String dayUtc) {
        if (!isNativeLoaded) return "";
        try {
            String sig = nativeComputeDailyHmac(payload, dayUtc);
            return (sig != null) ? sig : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isNativeServerAuthorized() {
        return true;
    }

    public static void revokeNativeSessionFromApi() {}

    @JavascriptInterface
    public String getVoiceServerEndpoint() {
        return getEncryptedServerEndpoint(2);
    }

    private void saveBoundKey(String cleanKey) {
        if (cleanKey == null || cleanKey.isEmpty()) {
            prefs.edit().remove(PLAIN_FALLBACK_KEY).putBoolean(PREF_FIRST_LOGIN_DONE, false).apply();
            legacyPrefs.edit().remove("license_key").apply();
            return;
        }
        String k = cleanKey.trim().toUpperCase(Locale.US);
        prefs.edit().putString(PLAIN_FALLBACK_KEY, k).putBoolean(PREF_FIRST_LOGIN_DONE, true).apply();
        legacyPrefs.edit().putString("license_key", k).apply();
    }

    private String readBoundKey() {
        String k = prefs.getString(PLAIN_FALLBACK_KEY, "");
        if (k == null || k.trim().isEmpty()) {
            k = legacyPrefs.getString("license_key", "");
        }
        return k == null ? "" : k.trim().toUpperCase(Locale.US);
    }

    @JavascriptInterface
    public String getDeviceHwid() {
        return getNativeHardwareHwid(context);
    }

    @JavascriptInterface
    public String getDisplayHwid() {
        return getDeviceHwid();
    }

    @JavascriptInterface
    public String getHardwareId() {
        return getDeviceHwid();
    }

    private long getDefaultDurationMsForKey(String cleanKey) {
        if (cleanKey == null) return 30L * 24L * 3600L * 1000L;
        String u = cleanKey.toUpperCase(Locale.US);
        if (u.startsWith("TP-1HR") || u.contains("1HR")) return 3600L * 1000L;
        if (u.startsWith("TP1D") || u.contains("24H")) return 24L * 3600L * 1000L;
        if (u.startsWith("TP7D")) return 7L * 24L * 3600L * 1000L;
        if (u.startsWith("TP21D")) return 21L * 24L * 3600L * 1000L;
        if (u.startsWith("TP50D")) return 50L * 24L * 3600L * 1000L;
        if (u.startsWith("TP90D")) return 90L * 24L * 3600L * 1000L;
        if (u.startsWith("TPLIFE") || u.startsWith("UNLIM")) return -1L;
        return 30L * 24L * 3600L * 1000L;
    }

    private long getOrInitKeyDeadlineMs(String cleanKey, JSONObject serverRes) {
        if (cleanKey == null || cleanKey.isEmpty()) return 0L;
        String deadlinePrefKey = "key_deadline_ms_" + cleanKey;
        long now = System.currentTimeMillis();

        if (serverRes != null) {
            if (serverRes.optBoolean("is_lifetime", false)) {
                prefs.edit().putLong(deadlinePrefKey, -1L).apply();
                return -1L;
            }
            long remSec = serverRes.optLong("remaining_sec", serverRes.optLong("time_left_sec", -1L));
            if (remSec > 80000000L) {
                prefs.edit().putLong(deadlinePrefKey, -1L).apply();
                return -1L;
            }
            if (remSec > 0) {
                long newDeadline = now + (remSec * 1000L);
                prefs.edit().putLong(deadlinePrefKey, newDeadline).apply();
                return newDeadline;
            }
        }

        long savedDeadline = prefs.getLong(deadlinePrefKey, 0L);
        if (savedDeadline != 0L) return savedDeadline;

        long computed = now + getDefaultDurationMsForKey(cleanKey);
        prefs.edit().putLong(deadlinePrefKey, computed).apply();
        return computed;
    }

    private String buildLiveSubscriptionLabel(JSONObject serverRes, String cleanKey) {
        if (cleanKey == null || cleanKey.isEmpty()) return "АКТИВНА";
        if (serverRes != null) {
            String srvTxt = serverRes.optString("expires_in", serverRes.optString("time_left", ""));
            if (srvTxt != null && !srvTxt.isEmpty() && !srvTxt.equalsIgnoreCase("null")) {
                return srvTxt;
            }
        }
        long deadlineMs = getOrInitKeyDeadlineMs(cleanKey, serverRes);
        if (deadlineMs == -1L) return "БЕССРОЧНО 👑";

        long diffMs = deadlineMs - System.currentTimeMillis();
        if (diffMs <= 0L) return "EXPIRED";

        long totalMinutes = diffMs / (60L * 1000L);
        long days = totalMinutes / (60L * 24L);
        long hours = (totalMinutes % (60L * 24L)) / 60L;
        long mins = totalMinutes % 60L;

        if (days >= 1800) return "БЕССРОЧНО 👑";
        if (days > 0) return "АКТИВНА (" + days + " дн. " + hours + " ч.)";
        if (hours > 0) return "АКТИВНА (" + hours + " ч. " + mins + " мин.)";
        return "АКТИВНА (" + Math.max(1, mins) + " мин.)";
    }

    private void removeOverlayViewSafe(View v) {
        if (v == null || windowManager == null) return;
        try {
            v.clearAnimation();
            v.setVisibility(View.GONE);
            windowManager.removeViewImmediate(v);
        } catch (Throwable ignored) {
            try { windowManager.removeView(v); } catch (Throwable ignored2) {}
        }
    }

    private void detachTrashOverlaySafe() {
        if (trashContainer != null) removeOverlayViewSafe(trashContainer);
        isTrashAttached = false;
    }

    private void attachTrashOverlaySafe() {
        if (!isTrashAttached && trashContainer != null && trashParams != null && windowManager != null) {
            try {
                if (trashPillView != null) trashPillView.setHovered(false);
                trashContainer.setVisibility(View.VISIBLE);
                trashContainer.setAlpha(0.95f);
                windowManager.addView(trashContainer, trashParams);
                isTrashAttached = true;
            } catch (Exception ignored) {}
        }
    }

    private String sanitizeJsText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("['\"\\\\<>\\r\\n()]", "");
    }

    @JavascriptInterface
    public void configureTouchLayer(boolean enabled, int stickiness, int heaviness, int sharpness, double sprayPull, double aimScale) {
        TeleportEngine.configureGameTouchLayer(enabled, stickiness, heaviness, sharpness, (float) sprayPull, (float) aimScale);
    }

    @JavascriptInterface
    public void setTouchInjectorActive(boolean enabled, int mode, int power) {
        TeleportEngine.setTouchInjectorActive(enabled, mode, power);
    }

    @JavascriptInterface
    public void injectTouchTap(int x, int y) {
        TeleportEngine.injectTouchTap(x, y);
    }

    @JavascriptInterface
    public void injectRecoilSwipe(int startX, int startY, int dx, int dy, int durationMs) {
        TeleportEngine.injectRecoilSwipe(startX, startY, dx, dy, durationMs);
    }

    @JavascriptInterface
    public int getLiveScreenRefreshRate() {
        return TeleportEngine.getLiveRefreshRateHz(context);
    }

    @JavascriptInterface
    public int getMaxHardwareRefreshRate() {
        return TeleportEngine.getMaxHardwareRefreshRateHz();
    }

    @JavascriptInterface
    public String getDeviceHardwareInfo() {
        TeleportEngine.detectTrueHardwareScreenMetrics(context);
        return Build.MODEL + " | " + TeleportEngine.getNativeShortSide() + "x" + TeleportEngine.getNativeLongSide() + " (" + TeleportEngine.getMaxHardwareRefreshRateHz() + " Гц)";
    }

    @JavascriptInterface
    public void enforceGameAudioMode() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(AudioManager.MODE_NORMAL);
                am.setSpeakerphoneOn(true);
                am.setMicrophoneMute(false);
            }
        } catch (Throwable ignored) {}
        if (isShizukuReady()) {
            String pkg = context.getPackageName();
            execGatedShizuku(
                    "cmd appops set " + pkg + " RECORD_AUDIO allow; " +
                    "cmd appops set " + pkg + " PLAY_AUDIO allow; " +
                    "cmd appops set " + pkg + " TAKE_AUDIO_FOCUS ignore; " +
                    "cmd appops set com.axlebolt.standoff2 PLAY_AUDIO allow; " +
                    "cmd appops set com.axlebolt.standoff2 TAKE_AUDIO_FOCUS ignore;"
            );
        }
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
                    Toast.makeText(context, "Выдайте разрешение «Поверх других окон»!", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public String activateStretchFloatingButton(String ratio) {
        if (ratio != null && !ratio.trim().isEmpty()) {
            this.selectedStretchRatio = ratio.trim().toUpperCase(Locale.US);
        }
        prefs.edit().putString("saved_stretch_ratio", this.selectedStretchRatio).apply();
        TeleportEngine.startTeleport("SET_RATIO:" + this.selectedStretchRatio);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission();
            return "NEED_PERMISSION";
        }

        TeleportEngine.requestShizukuPermissionIfNeeded();
        execGatedShizuku("settings put global block_untrusted_touches 0;");

        mainHandler.post(() -> {
            try {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).toggleFloatingButton(false);
                }
                detachTrashOverlaySafe();
                if (floatingStretchBtn != null) {
                    removeOverlayViewSafe(floatingStretchBtn);
                    floatingStretchBtn = null;
                }
                isStretchOverlayShown = false;

                int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                trashContainer = new FrameLayout(context);
                trashPillView = new BridgeTrashView(context);
                FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(dpToPx(276), dpToPx(58), Gravity.CENTER);
                trashContainer.addView(trashPillView, pillLp);

                int trashFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) trashFlags |= WindowManager.LayoutParams.FLAG_SECURE;

                trashParams = new WindowManager.LayoutParams(dpToPx(300), dpToPx(84), overlayType, trashFlags, PixelFormat.TRANSLUCENT);
                trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                trashParams.y = dpToPx(22);

                floatingStretchBtn = new TextView(context);
                floatingStretchBtn.setText("T\n" + selectedStretchRatio.replace("SHRINK-", ""));
                floatingStretchBtn.setTextColor(Color.WHITE);
                floatingStretchBtn.setTextSize(11.5f);
                floatingStretchBtn.setGravity(Gravity.CENTER);
                floatingStretchBtn.setIncludeFontPadding(false);

                int sizePx = dpToPx(54);
                int btnFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (stealthOverlayFlag) btnFlags |= WindowManager.LayoutParams.FLAG_SECURE;

                stretchParams = new WindowManager.LayoutParams(sizePx, sizePx, overlayType, btnFlags, PixelFormat.TRANSLUCENT);
                stretchParams.gravity = Gravity.TOP | Gravity.START;
                stretchParams.x = dpToPx(20);
                stretchParams.y = dpToPx(140);

                isBoxBarsActive = false;
                updateFloatingBtnAppearance();

                floatingStretchBtn.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX, initialY;
                    private float initialTouchX, initialTouchY;
                    private boolean isDragging = false;
                    private boolean wasInTrashZone = false;
                    private final int touchSlop = dpToPx(8);

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                initialX = stretchParams.x;
                                initialY = stretchParams.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                isDragging = false;
                                wasInTrashZone = false;
                                v.setScaleX(0.92f);
                                v.setScaleY(0.92f);
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                int dx = Math.round(event.getRawX() - initialTouchX);
                                int dy = Math.round(event.getRawY() - initialTouchY);
                                if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                                    isDragging = true;
                                    attachTrashOverlaySafe();
                                }
                                if (isDragging && windowManager != null && floatingStretchBtn != null) {
                                    stretchParams.x = initialX + dx;
                                    stretchParams.y = initialY + dy;
                                    try { windowManager.updateViewLayout(floatingStretchBtn, stretchParams); } catch (Exception ignored) {}
                                    boolean inTrash = isTouchOverTrashZone(event.getRawX(), event.getRawY());
                                    if (trashPillView != null) trashPillView.setHovered(inTrash);
                                    if (inTrash && !wasInTrashZone) {
                                        try { floatingStretchBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) {}
                                    }
                                    wasInTrashZone = inTrash;
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                v.setScaleX(1.0f);
                                v.setScaleY(1.0f);
                                if (isDragging) {
                                    boolean droppedInTrash = wasInTrashZone || isTouchOverTrashZone(event.getRawX(), event.getRawY());
                                    if (trashPillView != null) trashPillView.setHovered(false);
                                    detachTrashOverlaySafe();
                                    isDragging = false;
                                    wasInTrashZone = false;
                                    if (droppedInTrash) removeFloatingTpButtonOnly();
                                    return true;
                                }
                                detachTrashOverlaySafe();
                                toggleTwoStepStretchAction();
                                return true;
                        }
                        return false;
                    }
                });

                windowManager.addView(floatingStretchBtn, stretchParams);
                isStretchOverlayShown = true;
                Toast.makeText(context, "⚡ Стеклянная кнопка «T» (" + selectedStretchRatio + ") запущена!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, "Ошибка оверлея: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        return "OK";
    }

    private boolean isTouchOverTrashZone(float rawX, float rawY) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                windowManager.getDefaultDisplay().getRealMetrics(dm);
            } else {
                dm = context.getResources().getDisplayMetrics();
            }
            int sw = dm.widthPixels;
            int sh = dm.heightPixels;
            boolean yNearBottom = rawY >= (sh - dpToPx(140)) || (stretchParams != null && stretchParams.y >= (sh * 0.68f));
            boolean xNearCenter = Math.abs(rawX - (sw / 2f)) <= (sw * 0.40f);
            return yNearBottom && xNearCenter;
        } catch (Throwable t) {
            return false;
        }
    }

    private void toggleTwoStepStretchAction() {
        if (!isShizukuReady()) {
            Toast.makeText(context, "⚠️ Включите Shizuku! Помощь: @talent_dare", Toast.LENGTH_LONG).show();
            TeleportEngine.requestShizukuPermissionIfNeeded();
            return;
        }
        if (!isBoxBarsActive) {
            boolean ok = TeleportEngine.applyAspectRatioStretch(selectedStretchRatio);
            if (ok) {
                isBoxBarsActive = true;
                Toast.makeText(context, "⬛ Шаг 1 (" + selectedStretchRatio + ") включен! Зайди в матч и нажми «T» снова", Toast.LENGTH_SHORT).show();
            }
        } else {
            TeleportEngine.releaseBoxToFullStretch();
            isBoxBarsActive = false;
            Toast.makeText(context, "🔥 Шаг 2: Растяг применён! Смахни «T» вниз в ведро", Toast.LENGTH_SHORT).show();
        }
        updateFloatingBtnAppearance();
    }

    private void removeFloatingTpButtonOnly() {
        mainHandler.post(() -> {
            if (isBoxBarsActive) {
                TeleportEngine.releaseBoxToFullStretch();
                isBoxBarsActive = false;
            }
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) {
                removeOverlayViewSafe(floatingStretchBtn);
                floatingStretchBtn = null;
            }
            trashContainer = null;
            trashPillView = null;
            isStretchOverlayShown = false;
            Toast.makeText(context, "🗑️ Кнопка «T» убрана с экрана!", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateFloatingBtnAppearance() {
        if (floatingStretchBtn == null) return;
        String shortLabel = selectedStretchRatio.replace("SHRINK-", "");
        GradientDrawable glassLens = new GradientDrawable();
        glassLens.setShape(GradientDrawable.OVAL);
        glassLens.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        glassLens.setOrientation(GradientDrawable.Orientation.TL_BR);

        if (isBoxBarsActive) {
            glassLens.setColors(new int[]{
                    Color.parseColor("#CC34D399"),
                    Color.parseColor("#8810B981"),
                    Color.parseColor("#AA064E3B")
            });
            glassLens.setStroke(dpToPx(2), Color.parseColor("#EEFFFFFF"));
            floatingStretchBtn.setText("2\n" + shortLabel);
        } else {
            glassLens.setColors(new int[]{
                    Color.parseColor("#B3475569"),
                    Color.parseColor("#801E293B"),
                    Color.parseColor("#990F172A")
            });
            glassLens.setStroke(dpToPx(2), Color.parseColor("#EE38BDF8"));
            floatingStretchBtn.setText("T\n" + shortLabel);
        }
        floatingStretchBtn.setBackground(glassLens);
    }

    @JavascriptInterface
    public void applyStretchDirectlyNow(String ratio) {
        activateStretchFloatingButton(ratio);
    }

    @JavascriptInterface
    public void applyDirectStretchRatio(String ratio) {
        TeleportEngine.applyAspectRatioStretch(ratio);
    }

    @JavascriptInterface
    public void resetResolution() {
        mainHandler.post(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).toggleFloatingButton(false);
            }
            TeleportEngine.resetResolutionToNative();
            isBoxBarsActive = false;
            detachTrashOverlaySafe();
            if (floatingStretchBtn != null) {
                removeOverlayViewSafe(floatingStretchBtn);
                floatingStretchBtn = null;
            }
            trashContainer = null;
            trashPillView = null;
            isStretchOverlayShown = false;
            Toast.makeText(context, "🔄 Родное разрешение экрана восстановлено", Toast.LENGTH_SHORT).show();
        });
    }

    @JavascriptInterface
    public boolean saveFullConfigJson(String jsonConfig) {
        if (jsonConfig == null) return false;
        prefs.edit().putString("full_user_config_v410", jsonConfig).apply();
        return true;
    }

    @JavascriptInterface
    public String loadFullConfigJson() {
        return prefs.getString("full_user_config_v410", "");
    }

    @JavascriptInterface
    public String setWideHitboxMode(boolean enabled, String ratio) {
        String targetRatio = (ratio != null && !ratio.isEmpty()) ? ratio : "4:3";
        prefs.edit().putBoolean("wide_hitbox_mode", enabled).apply();
        if (enabled) {
            TeleportEngine.setTouchSamplingBoost(true, 240);
            TeleportEngine.setEnhancedRecoilFix(true, 100);
            TeleportEngine.enableWideHitboxQuantization(true);
            activateStretchFloatingButton(targetRatio);
            return "OK|" + targetRatio;
        } else {
            TeleportEngine.enableWideHitboxQuantization(false);
            resetResolution();
            return "OFF";
        }
    }

    @JavascriptInterface
    public boolean isShizukuReady() {
        return TeleportEngine.isShizukuReady();
    }

    @JavascriptInterface
    public boolean isShizukuActive() {
        return isShizukuReady();
    }

    @JavascriptInterface
    public void requestShizukuPermission() {
        TeleportEngine.requestShizukuPermissionIfNeeded();
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
    public void updateParameters(double sharp, double sx, double sy, int chunks, int chunkFast, double straight, int stick, int grid, int pause, double accel) {
        TeleportEngine.updateParameters(sharp, sx, sy, chunks, chunkFast, straight, stick, grid, pause, accel);
    }

    @JavascriptInterface
    public void setRawInputNoSmoothing(boolean enabled) {
        TeleportEngine.setRawInputNoSmoothing(enabled);
    }

    @JavascriptInterface
    public void setVulkanGpuTurbo(boolean enabled) {
        TeleportEngine.setVulkanGpuTurbo(enabled);
    }

    @JavascriptInterface
    public void setNetPingStabilizer(boolean enabled) {
        TeleportEngine.setNetPingStabilizer(enabled);
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
    public void applyShooterOptimizer(int fpsLock, int gpuBoost, int frameTime, int edgeSens, int multiTouch, int netBuffer, int thermalLim) {
        TeleportEngine.applyShooterOptimizer(fpsLock, gpuBoost, frameTime, edgeSens, multiTouch, netBuffer, thermalLim);
    }

    @JavascriptInterface
    public void setEnhancedRecoilFix(boolean enabled, int power) {
        TeleportEngine.setEnhancedRecoilFix(enabled, power);
    }

    @JavascriptInterface
    public void setAutoGameDpi(boolean enabled, int dpi) {}

    @JavascriptInterface
    public void setPixelDownscaleFactor(int pct) {
        TeleportEngine.setPixelDownscaleFactor(pct);
    }

    @JavascriptInterface
    public String runSmartDeviceScan() {
        return TeleportEngine.runSmartDeviceScan(context);
    }

    @JavascriptInterface
    public void applyUltraProTouchMatrix(int modeLevel) {
        ProfileManager.addTeleportCountToTotal(context, 1);
        TeleportEngine.startTeleport("extreme");
    }

    @JavascriptInterface
    public String uploadCloudConfig(String title, String presetJson) {
        String key = getSavedKey();
        if (key.isEmpty()) return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        try {
            Callable<String> task = () -> ApiManager.uploadCloudPreset(key, getDeviceHwid(), title, presetJson);
            return networkExecutor.submit(task).get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false}";
        }
    }

    @JavascriptInterface
    public String downloadCloudConfig(String shareCode) {
        String key = getSavedKey();
        if (key.isEmpty()) return "{\"status\":\"error\",\"ok\":false,\"message\":\"Сначала активируйте ключ\"}";
        try {
            Callable<String> task = () -> ApiManager.downloadCloudPresetByCode(key, getDeviceHwid(), shareCode);
            return networkExecutor.submit(task).get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false}";
        }
    }

    private void ensureCrosshairOverlayAttached(String cleanType) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateCrosshair("off");
        }
        if ("off".equalsIgnoreCase(cleanType) || "none".equalsIgnoreCase(cleanType) || cleanType.isEmpty()) {
            if (crosshairView != null) {
                crosshairView.setCrosshairType("off");
                removeOverlayViewSafe(crosshairView);
            }
            crosshairView = null;
            isCrosshairShown = false;
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
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (stealthOverlayFlag) flags |= WindowManager.LayoutParams.FLAG_SECURE;

            int boxSize = dpToPx(140);
            crosshairParams = new WindowManager.LayoutParams(boxSize, boxSize, overlayType, flags, PixelFormat.TRANSLUCENT);
            crosshairParams.gravity = Gravity.CENTER;
            crosshairParams.x = dpToPx(savedCrosshairOffsetX);
            crosshairParams.y = dpToPx(savedCrosshairOffsetY);
            try {
                windowManager.addView(crosshairView, crosshairParams);
                isCrosshairShown = true;
            } catch (Exception ignored) {}
        }
        if (crosshairView != null) {
            crosshairView.setVisibility(View.VISIBLE);
            crosshairView.setCrosshairType(cleanType);
        }
    }

    @JavascriptInterface
    public void updateCrosshair(String type) {
        final String cleanType = (type == null) ? "off" : type.trim();
        mainHandler.post(() -> ensureCrosshairOverlayAttached(cleanType));
    }

    @JavascriptInterface
    public void setCrosshairOffset(int offsetXDp, int offsetYDp) {
        this.savedCrosshairOffsetX = offsetXDp;
        this.savedCrosshairOffsetY = offsetYDp;
        mainHandler.post(() -> {
            if (isCrosshairShown && crosshairView != null && crosshairParams != null && windowManager != null) {
                crosshairParams.x = dpToPx(offsetXDp);
                crosshairParams.y = dpToPx(offsetYDp);
                try { windowManager.updateViewLayout(crosshairView, crosshairParams); } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void updateCrosshairStyle(int scalePct, int opacityPct, String hexColor) {
        mainHandler.post(() -> {
            if (crosshairView != null) crosshairView.setStyle(scalePct, opacityPct, hexColor);
        });
    }

    @JavascriptInterface
    public void updateCrosshairAdvanced(String type, String hexColor, int opacityPct, int len, int thick, int gap, int dot, boolean out) {
        final String cleanType = (type == null) ? "off" : type.trim();
        if ("off".equalsIgnoreCase(cleanType) || cleanType.isEmpty() || opacityPct <= 0) {
            updateCrosshair("off");
            return;
        }
        mainHandler.post(() -> {
            ensureCrosshairOverlayAttached(cleanType);
            if (crosshairView != null && isCrosshairShown) {
                crosshairView.setVisibility(View.VISIBLE);
                crosshairView.setAdvancedConfig(cleanType, hexColor, opacityPct, len, thick, gap, dot, out);
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

    @JavascriptInterface
    public void setStealthOverlayMode(boolean stealth) {
        updateOverlayStealthFlag(stealth);
    }

    @JavascriptInterface
    public String getSavedKey() {
        return readBoundKey();
    }

    @JavascriptInterface
    public String getSavedNickname() {
        String nick = prefs.getString("saved_player_nick", "");
        if (nick == null || nick.trim().isEmpty()) nick = legacyPrefs.getString("nickname", "");
        return (nick == null || nick.trim().isEmpty()) ? "dare" : nick.trim();
    }

    @JavascriptInterface
    public void savePlayerNickname(String nick) {
        if (nick != null && !nick.trim().isEmpty()) {
            String s = sanitizeJsText(nick.trim());
            prefs.edit().putString("saved_player_nick", s).apply();
            legacyPrefs.edit().putString("nickname", s).apply();
        }
    }

    // ЖЕЛЕЗНАЯ АКТИВАЦИЯ И ПРОВЕРКА КЛЮЧА НА ВСЕХ ДЕВАЙСАХ
    @JavascriptInterface
    public String activateLicenseKey(String key) {
        if (key == null || key.trim().length() < 5) {
            return "ERROR|Введите лицензионный ключ из бота";
        }
        final String cleanKey = key.trim().toUpperCase(Locale.US);
        final String nick = getSavedNickname();
        final String hwid = getDeviceHwid();

        try {
            Callable<String> task = () -> ApiManager.activateLicense(cleanKey, hwid, nick);
            Future<String> future = networkExecutor.submit(task);
            String rawJson = future.get(7000, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(rawJson);

            String status = res.optString("status", "");
            boolean ok = res.optBoolean("ok", false) || "ok".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status);

            if (ok) {
                TeleportEngine.setServerAuthorizedFromVerifiedHmac(true);
                saveBoundKey(cleanKey);
                getOrInitKeyDeadlineMs(cleanKey, res);
                ProfileManager.saveActivatedProfile(context, nick, cleanKey);
                String label = buildLiveSubscriptionLabel(res, cleanKey);
                return "OK|" + label;
            }

            String errMsg = res.optString("message", "Ключ не найден или привязан к другому устройству!");
            return "ERROR|" + errMsg;
        } catch (Exception e) {
            // Если это уже привязанный ключ владельца, а мобильная сеть моргнула — не выкидываем его!
            String existing = readBoundKey();
            if (!existing.isEmpty() && existing.equalsIgnoreCase(cleanKey)) {
                return "OK|" + buildLiveSubscriptionLabel(null, cleanKey);
            }
            return "ERROR|Нет связи с сервером (проверьте интернет)";
        }
    }

    @JavascriptInterface
    public String verifyLicenseKey(String key) {
        final String cleanKey = (key != null && !key.trim().isEmpty()) ? key.trim().toUpperCase(Locale.US) : readBoundKey();
        if (cleanKey.length() < 5) {
            return "EXPIRED|Введите лицензионный ключ";
        }

        final String hwid = getDeviceHwid();
        try {
            Callable<String> task = () -> ApiManager.verifyLicense(cleanKey, hwid);
            Future<String> future = networkExecutor.submit(task);
            String rawJson = future.get(5500, TimeUnit.MILLISECONDS);
            JSONObject res = new JSONObject(rawJson);
            String status = res.optString("status", "");

            if ("device_mismatch".equalsIgnoreCase(status) || "banned".equalsIgnoreCase(status) || "blocked".equalsIgnoreCase(status)) {
                saveBoundKey("");
                return "BLOCKED|" + res.optString("message", "Ключ привязан к другому устройству!");
            }
            if ("expired".equalsIgnoreCase(status)) {
                saveBoundKey("");
                return "EXPIRED|" + res.optString("message", "Срок действия ключа истёк!");
            }
            if (res.optBoolean("ok", false) || "ok".equalsIgnoreCase(status)) {
                saveBoundKey(cleanKey);
                return "OK|" + buildLiveSubscriptionLabel(res, cleanKey);
            }
        } catch (Exception ignored) {}

        // Офлайн-поддержка для уже привязанного ключа на родном девайсе
        return "OK|" + buildLiveSubscriptionLabel(null, cleanKey);
    }

    @JavascriptInterface
    public void logoutLicenseKey() {
        saveBoundKey("");
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

    @JavascriptInterface
    public void openSupportTelegram() {
        mainHandler.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/talent_dare"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public String requestFriendsHub(String jsonPayload) {
        try {
            Callable<String> task = () -> ApiManager.callFriendsApi(jsonPayload);
            return networkExecutor.submit(task).get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"ok\":false}";
        }
    }

    @JavascriptInterface
    public void updateVoiceCallNotification(String roomName, boolean isMuted, boolean isDeafened) {}

    @JavascriptInterface
    public void cancelVoiceCallNotification() {}

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static class BridgeTrashView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private boolean hovered = false;

        public BridgeTrashView(Context context) {
            super(context);
            this.density = context.getResources().getDisplayMetrics().density;
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2f * density);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f * density);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void setHovered(boolean h) {
            if (this.hovered != h) {
                this.hovered = h;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float pad = 6f * density;
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            bgPaint.setColor(hovered ? Color.parseColor("#DDFF1744") : Color.parseColor("#CC0F172A"));
            borderPaint.setColor(hovered ? Color.WHITE : Color.parseColor("#FF2E5B"));
            canvas.drawRoundRect(rect, 999f, 999f, bgPaint);
            canvas.drawRoundRect(rect, 999f, 999f, borderPaint);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            String label = hovered ? "🔥 ОТПУСТИТЕ, ЧТОБЫ УБРАТЬ «T»" : "🗑️ СМАХНИТЕ СЮДА ДЛЯ УДАЛЕНИЯ";
            canvas.drawText(label, getWidth() / 2f, cy, textPaint);
        }
    }

    private static class CrosshairOverlayView extends View {
        private String type = "cross";
        private int opacity = 100;
        private int lengthDp = 10;
        private int thicknessDp = 3;
        private int gapDp = 3;
        private int dotSizeDp = 3;
        private boolean outline = true;
        private int color = Color.parseColor("#00E5FF");

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CrosshairOverlayView(Context context) {
            super(context);
            outlinePaint.setColor(Color.BLACK);
            paint.setStrokeCap(Paint.Cap.ROUND);
            outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        }

        public void setCrosshairType(String t) { this.type = t; invalidate(); }

        public void setStyle(int scalePct, int opacityPct, String hex) {
            this.lengthDp = Math.max(3, Math.round(3 + (scalePct / 100f) * 18f));
            this.opacity = opacityPct;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        public void setAdvancedConfig(String t, String hex, int op, int len, int thick, int gap, int dot, boolean out) {
            this.type = t; this.opacity = op; this.lengthDp = len; this.thicknessDp = thick; this.gapDp = gap; this.dotSizeDp = dot; this.outline = out;
            try { this.color = Color.parseColor(hex); } catch (Exception ignored) {}
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            if ("off".equalsIgnoreCase(type)) return;

            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float lenPx = lengthDp * d;
            float thickPx = Math.max(1.2f, thicknessDp * 0.78f * d);
            float gapPx = gapDp * d;
            float dotRadPx = Math.max(1.3f, dotSizeDp * 0.68f * d);

            int alpha = Math.max(25, Math.min(255, Math.round(opacity * 2.55f)));
            paint.setColor(color);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(thickPx);

            outlinePaint.setAlpha(Math.min(220, alpha));
            outlinePaint.setStrokeWidth(thickPx + 2.0f * d);

            if ("dot".equalsIgnoreCase(type)) {
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, dotRadPx + 1.1f * d, outlinePaint);
                }
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dotRadPx, paint);
                return;
            }

            if ("circle".equalsIgnoreCase(type) || "circledot".equalsIgnoreCase(type)) {
                float rad = Math.max(3.5f * d, lenPx * 0.75f);
                if (outline) {
                    outlinePaint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(cx, cy, rad, outlinePaint);
                }
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, rad, paint);
                if ("circledot".equalsIgnoreCase(type)) {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, dotRadPx, paint);
                }
                return;
            }

            if (outline) {
                outlinePaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, outlinePaint);
                canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, outlinePaint);
                canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, outlinePaint);
                canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, outlinePaint);
            }
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(cx - gapPx - lenPx, cy, cx - gapPx, cy, paint);
            canvas.drawLine(cx + gapPx, cy, cx + gapPx + lenPx, cy, paint);
            canvas.drawLine(cx, cy + gapPx, cx, cy + gapPx + lenPx, paint);
            canvas.drawLine(cx, cy - gapPx - lenPx, cx, cy - gapPx, paint);

            if ("crossdot".equalsIgnoreCase(type)) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dotRadPx, paint);
            }
        }
    }
}
