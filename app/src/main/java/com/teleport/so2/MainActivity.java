package com.teleport.so2;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "SO2MainActivity";
    private static final String PREFS_NAME = "SO2TeleportPrefs";
    private static final int REQ_OVERLAY_PERMISSION = 5001;

    public static final String ACTION_EVAL_JS = "com.teleport.so2.ACTION_EVAL_JS";
    public static final String ACTION_TOGGLE_FLOAT = "com.teleport.so2.ACTION_TOGGLE_FLOAT";
    public static final String ACTION_UPDATE_CROSSHAIR = "com.teleport.so2.ACTION_UPDATE_CROSSHAIR";
    public static final String ACTION_STOP_ENGINE = "com.teleport.so2.ACTION_STOP_ENGINE";
    public static final String EXTRA_JS_CODE = "extra_js_code";
    public static final String EXTRA_SHOW_FLOAT = "extra_show_float";
    public static final String EXTRA_CROSSHAIR_TYPE = "extra_crosshair_type";

    private static MainActivity sInstance;

    private FrameLayout rootContainer;
    private TextureView bgVideoView;
    private MediaPlayer bgMediaPlayer;
    private Surface bgSurface;
    private WebView webView;
    private AndroidBridge androidBridge;
    private CrosshairOverlay crosshairOverlay;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isWebViewLoaded = false;
    private boolean isReceiverRegistered = false;
    private boolean isBgVideoEnabled = true;

    private final BroadcastReceiver internalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            try {
                if (ACTION_EVAL_JS.equals(action)) {
                    String js = intent.getStringExtra(EXTRA_JS_CODE);
                    if (js != null && !js.isEmpty()) {
                        evalJsFromNotification(js);
                    }
                } else if (ACTION_TOGGLE_FLOAT.equals(action)) {
                    boolean show = intent.getBooleanExtra(EXTRA_SHOW_FLOAT, false);
                    toggleFloatingButton(show);
                } else if (ACTION_UPDATE_CROSSHAIR.equals(action)) {
                    String type = intent.getStringExtra(EXTRA_CROSSHAIR_TYPE);
                    updateCrosshair(type != null ? type : "off");
                } else if (ACTION_STOP_ENGINE.equals(action)) {
                    toggleFloatingButton(false);
                    updateCrosshair("off");
                    evalJsFromNotification("if(window.onEngineStoppedFromNotification){window.onEngineStoppedFromNotification();}");
                }
            } catch (Throwable t) {
                Log.e(TAG, "BroadcastReceiver error", t);
            }
        }
    };

    public static MainActivity getInstance() {
        return sInstance;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 1. Сначала вызываем requestWindowFeature ДО super.onCreate / setContentView, чтобы никогда не было краша Window
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {}

        super.onCreate(savedInstanceState);
        sInstance = this;

        // 2. Безопасная инициализация модулей
        try {
            SecurityShield.init(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "SecurityShield init warning", t);
        }

        // 3. Включаем максимальную герцовку экрана (120 Гц)
        enableHighRefreshRate();

        // 4. Создаём корневой контейнер
        rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootContainer.setBackgroundColor(Color.parseColor("#040711"));

        // 5. Инициализируем фоновое видео bg.mp4 (если есть в assets)
        setupBackgroundVideo();

        // 6. Инициализируем WebView с поддержкой Жидкого Стекла и AndroidBridge
        setupWebView();

        // 7. Устанавливаем корневой View
        setContentView(rootContainer);

        // 8. Настраиваем полноэкранный режим ПОСЛЕ setContentView (безопасно для Android 11–16 / One UI)
        applySystemBarsStyling();

        // 9. Инициализируем оверлей прицела
        try {
            crosshairOverlay = new CrosshairOverlay(this);
        } catch (Throwable t) {
            Log.w(TAG, "CrosshairOverlay init skipped", t);
        }

        // 10. Регистрируем BroadcastReceiver для уведомлений и сервисов
        registerInternalReceiver();

        // 11. Загружаем интерфейс
        loadMainInterface();
    }

    private void setupBackgroundVideo() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            isBgVideoEnabled = prefs.getBoolean("bg_video_enabled", true);

            bgVideoView = new TextureView(this);
            bgVideoView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            bgVideoView.setOpaque(false);
            bgVideoView.setAlpha(0.35f);
            bgVideoView.setSurfaceTextureListener(this);
            rootContainer.addView(bgVideoView);
        } catch (Throwable t) {
            Log.w(TAG, "Background video view setup skipped", t);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        try {
            webView = new WebView(this);
            webView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            webView.setBackgroundColor(Color.TRANSPARENT);
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            webView.setHorizontalScrollBarEnabled(false);
            webView.setVerticalScrollBarEnabled(false);
            webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowContentAccess(true);
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            ws.setSupportZoom(false);
            ws.setBuiltInZoomControls(false);
            ws.setDisplayZoomControls(false);
            ws.setTextZoom(100);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            try {
                androidBridge = new AndroidBridge(this);
                webView.addJavascriptInterface(androidBridge, "AndroidBridge");
                webView.addJavascriptInterface(androidBridge, "Android");
            } catch (Throwable t) {
                Log.e(TAG, "AndroidBridge init error", t);
            }

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    isWebViewLoaded = true;
                    injectStartupContext();
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && request.getUrl() != null) {
                        return handleCustomUrl(request.getUrl().toString());
                    }
                    return false;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleCustomUrl(url);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                }
            });

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage cm) {
                    if (cm != null) {
                        Log.d("SO2WebUI", cm.message() + " [line " + cm.lineNumber() + "]");
                    }
                    return true;
                }

                @Override
                public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                    try {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                    if (result != null) result.confirm();
                    return true;
                }
            });

            rootContainer.addView(webView);
        } catch (Throwable t) {
            Log.e(TAG, "WebView setup error", t);
        }
    }

    private boolean handleCustomUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("file:///") || url.startsWith("about:")) {
            return false;
        }
        if (url.startsWith("tg:") || url.startsWith("https://t.me/") || url.startsWith("http://") || url.startsWith("https://")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void loadMainInterface() {
        if (webView == null) return;
        try {
            webView.loadUrl("file:///android_asset/index.html");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load index.html", t);
        }
    }

    private void injectStartupContext() {
        if (webView == null) return;
        try {
            String hwid = SecurityShield.getHardwareId(this);
            String model = Build.MANUFACTURER + " " + Build.MODEL;
            int sdk = Build.VERSION.SDK_INT;
            String js = "window.__SO2_NATIVE_HWID='" + escapeJs(hwid) + "';" +
                        "window.__SO2_DEVICE_MODEL='" + escapeJs(model) + "';" +
                        "window.__SO2_ANDROID_SDK=" + sdk + ";";
            evalJsFromNotification(js);
        } catch (Throwable ignored) {}
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
    }

    private void enableHighRefreshRate() {
        try {
            Window window = getWindow();
            if (window == null) return;
            WindowManager.LayoutParams params = window.getAttributes();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display display = getWindowManager().getDefaultDisplay();
                Display.Mode[] modes = display.getSupportedModes();
                Display.Mode bestMode = null;
                float maxHz = 60f;
                if (modes != null) {
                    for (Display.Mode m : modes) {
                        if (m.getRefreshRate() >= maxHz) {
                            maxHz = m.getRefreshRate();
                            bestMode = m;
                        }
                    }
                }
                if (bestMode != null) {
                    params.preferredDisplayModeId = bestMode.getModeId();
                }
            }
            params. preferredRefreshRate = 120f;
            window.setAttributes(params);
        } catch (Throwable ignored) {}
    }

    private void applySystemBarsStyling() {
        try {
            Window window = getWindow();
            if (window == null) return;
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.setStatusBarColor(Color.parseColor("#040711"));
                window.setNavigationBarColor(Color.parseColor("#040711"));
            }
            View decor = window.getDecorView();
            if (decor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController controller = decor.getWindowInsetsController();
                    if (controller != null) {
                        controller.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        );
                    }
                } else {
                    decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    );
                }
            }
        } catch (Throwable ignored) {}
    }

    private void registerInternalReceiver() {
        if (isReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_EVAL_JS);
            filter.addAction(ACTION_TOGGLE_FLOAT);
            filter.addAction(ACTION_UPDATE_CROSSHAIR);
            filter.addAction(ACTION_STOP_ENGINE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(internalReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(internalReceiver, filter);
            }
            isReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Receiver registration warning", t);
        }
    }

    private void unregisterInternalReceiver() {
        if (!isReceiverRegistered) return;
        try {
            unregisterReceiver(internalReceiver);
        } catch (Throwable ignored) {}
        isReceiverRegistered = false;
    }

    // ==================== МЕТОДЫ МОСТА И СЕРВИСОВ ====================

    public void evalJsFromNotification(final String jsCode) {
        if (jsCode == null || jsCode.isEmpty()) return;
        mainHandler.post(() -> {
            if (webView == null) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    webView.evaluateJavascript(jsCode, null);
                } else {
                    webView.loadUrl("javascript:" + jsCode);
                }
            } catch (Throwable t) {
                Log.w(TAG, "evalJs error", t);
            }
        });
    }

    public void evaluateJs(final String jsCode) {
        evalJsFromNotification(jsCode);
    }

    public WebView getWebView() {
        return webView;
    }

    public void toggleFloatingButton(final boolean show) {
        mainHandler.post(() -> {
            try {
                if (show && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    requestOverlayPermission();
                    return;
                }
                Intent serviceIntent = new Intent(this, FloatingButtonService.class);
                if (show) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                } else {
                    stopService(serviceIntent);
                }
            } catch (Throwable t) {
                Log.w(TAG, "toggleFloatingButton error", t);
            }
        });
    }

    public void updateCrosshair(final String type) {
        mainHandler.post(() -> {
            try {
                if (crosshairOverlay == null) {
                    crosshairOverlay = new CrosshairOverlay(this);
                }
                if (type == null || type.isEmpty() || "off".equalsIgnoreCase(type) || "none".equalsIgnoreCase(type) || "false".equalsIgnoreCase(type)) {
                    crosshairOverlay.hide();
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        requestOverlayPermission();
                        return;
                    }
                    crosshairOverlay.show(type);
                }
            } catch (Throwable t) {
                Log.w(TAG, "updateCrosshair error", t);
            }
        });
    }

    public boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    public void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
            } catch (Throwable t) {
                Toast.makeText(this, "Выдайте разрешение «Поверх других окон» в настройках", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void setBackgroundVideoEnabled(final boolean enabled) {
        isBgVideoEnabled = enabled;
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean("bg_video_enabled", enabled)
                    .apply();
        } catch (Throwable ignored) {}
        mainHandler.post(() -> {
            if (bgVideoView != null) {
                bgVideoView.setVisibility(enabled ? View.VISIBLE : View.GONE);
            }
            if (bgMediaPlayer != null) {
                try {
                    if (enabled && !bgMediaPlayer.isPlaying()) {
                        bgMediaPlayer.start();
                    } else if (!enabled && bgMediaPlayer.isPlaying()) {
                        bgMediaPlayer.pause();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    // ==================== ФОНОВОЕ ВИДЕО (bg.mp4) ====================

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (!isBgVideoEnabled) return;
        try {
            bgSurface = new Surface(surface);
            AssetFileDescriptor afd = getAssets().openFd("bg.mp4");
            bgMediaPlayer = new MediaPlayer();
            bgMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            bgMediaPlayer.setSurface(bgSurface);
            bgMediaPlayer.setLooping(true);
            bgMediaPlayer.setVolume(0f, 0f);
            bgMediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                } catch (Throwable ignored) {}
            });
            bgMediaPlayer.prepareAsync();
        } catch (Throwable ignored) {
            // Если bg.mp4 отсутствует или не поддерживается — просто оставляем темный фон
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        releaseBackgroundVideo();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void releaseBackgroundVideo() {
        try {
            if (bgMediaPlayer != null) {
                if (bgMediaPlayer.isPlaying()) {
                    bgMediaPlayer.stop();
                }
                bgMediaPlayer.release();
                bgMediaPlayer = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (bgSurface != null) {
                bgSurface.release();
                bgSurface = null;
            }
        } catch (Throwable ignored) {}
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    @Override
    protected void onResume() {
        super.onResume();
        sInstance = this;
        try {
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
            if (isBgVideoEnabled && bgMediaPlayer != null && !bgMediaPlayer.isPlaying()) {
                bgMediaPlayer.start();
            }
            if (isWebViewLoaded) {
                evalJsFromNotification("if(window.onAppResume){window.onAppResume();}");
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        try {
            if (bgMediaPlayer != null && bgMediaPlayer.isPlaying()) {
                bgMediaPlayer.pause();
            }
            if (webView != null) {
                webView.onPause();
            }
        } catch (Throwable ignored) {}
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterInternalReceiver();
        releaseBackgroundVideo();
        try {
            if (crosshairOverlay != null) {
                crosshairOverlay.hide();
            }
        } catch (Throwable ignored) {}
        try {
            if (webView != null) {
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) {
                    parent.removeView(webView);
                }
                webView.stopLoading();
                webView.removeAllViews();
                webView.destroy();
                webView = null;
            }
        } catch (Throwable ignored) {}
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }
}
