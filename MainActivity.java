package com.teleport.sens;

import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int SHIZUKU_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Shizuku.addRequestPermissionResultListener(this::onRequestPermissionResult);

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new AppController(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());

        // Чистый и плавный интерфейс без лишней вибрации с мягкими CSS-анимациями
        String htmlContent = "<!DOCTYPE html>" +
            "<html lang=\"ru\"><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "<title>Teleport // Standoff 2</title>" +
            "<style>" +
            "@keyframes pulseGlow { 0% { box-shadow: 0 0 10px rgba(0,89,255,0.2); } 50% { box-shadow: 0 0 20px rgba(0,89,255,0.5); } 100% { box-shadow: 0 0 10px rgba(0,89,255,0.2); } }" +
            "* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; -webkit-tap-highlight-color: transparent; }" +
            "body { background-color: #030712; color: #ffffff; padding: 16px; padding-bottom: 50px; }" +
            
            ".top-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }" +
            ".top-card { background: linear-gradient(135deg, rgba(13, 20, 45, 0.8) 0%, rgba(3, 7, 18, 0.95) 100%); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 20px; padding: 16px; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 8px 24px rgba(0,0,0,0.5); backdrop-filter: blur(12px); }" +
            ".top-card-info b { display: block; font-size: 14px; color: #fff; margin-bottom: 2px; }" +
            ".top-card-info span { font-size: 11px; color: #7895cb; }" +
            ".icon-box { width: 36px; height: 36px; background: rgba(0,89,255,0.15); border-radius: 10px; display: flex; align-items: center; justify-content: center; color: #3b82f6; font-size: 16px; }" +

            ".card { background: linear-gradient(135deg, rgba(13, 20, 45, 0.85) 0%, rgba(3, 7, 18, 0.95) 100%); border: 1px solid rgba(0, 89, 255, 0.2); border-radius: 20px; padding: 18px; margin-bottom: 16px; box-shadow: 0 10px 30px rgba(0,0,0,0.7); backdrop-filter: blur(12px); }" +
            ".card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }" +
            ".card-title { font-size: 14px; font-weight: 700; color: #fff; text-transform: uppercase; letter-spacing: 0.5px; }" +
            ".card-desc { font-size: 12px; color: #8a9bb8; margin-bottom: 14px; line-height: 1.4; }" +
            
            // Плавная анимация нажатия без вибрации
            ".btn-primary { background: linear-gradient(135deg, #0059FF 0%, #003399 100%); color: #fff; border: none; border-radius: 14px; padding: 12px 20px; font-size: 12px; font-weight: 600; cursor: pointer; box-shadow: 0 4px 15px rgba(0, 89, 255, 0.4); text-align: center; transition: transform 0.2s ease, opacity 0.2s ease; animation: pulseGlow 3s infinite; }" +
            ".btn-primary:active { transform: scale(0.95); opacity: 0.85; }" +
            
            ".toggle-row { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; font-size: 13px; color: #e2e8f0; }" +
            ".switch { position: relative; display: inline-block; width: 48px; height: 26px; }" +
            ".switch input { opacity: 0; width: 0; height: 0; }" +
            ".slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(255,255,255,0.1); transition: .3s; border-radius: 26px; border: 1px solid rgba(255,255,255,0.15); }" +
            ".slider:before { position: absolute; content: \"\"; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .3s; border-radius: 50%; }" +
            "input:checked + .slider { background-color: #0059FF; box-shadow: 0 0 10px rgba(0,89,255,0.6); }" +
            "input:checked + .slider:before { transform: translateX(22px); }" +
            
            ".status-badge { display: flex; align-items: center; font-size: 11px; color: #4ade80; margin-top: 8px; }" +
            ".status-dot { width: 6px; height: 6px; background-color: #4ade80; border-radius: 50%; margin-right: 6px; box-shadow: 0 0 8px #4ade80; }" +
            
            "#permModal { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(3,7,18,0.92); z-index: 999; display: flex; align-items: center; justify-content: center; padding: 20px; backdrop-filter: blur(15px); }" +
            ".modal-box { background: linear-gradient(135deg, #0d142d 0%, #030712 100%); border: 1px solid rgba(0,89,255,0.4); border-radius: 24px; padding: 24px; text-align: center; box-shadow: 0 20px 50px rgba(0,0,0,0.8); }" +
            ".modal-title { font-size: 15px; font-weight: 700; color: #fff; margin-bottom: 10px; text-transform: uppercase; }" +
            ".modal-text { font-size: 12px; color: #8a9bb8; margin-bottom: 20px; line-height: 1.5; }" +
            "</style></head><body>" +

            "<div id=\"permModal\">" +
            "<div class=\"modal-box\">" +
            "<div class=\"modal-title\">Требуется доступ</div>" +
            "<div class=\"modal-text\">Для работы плавающей кнопки растяжения экрана поверх Standoff 2 необходимо выдать разрешение на отображение поверх других окон.</div>" +
            "<button class=\"btn-primary\" onclick=\"AndroidBridge.openPermissionSettings()\">РАЗРЕШИТЬ ДОСТУП</button>" +
            "</div></div>" +

            "<div class=\"top-grid\">" +
            "<div class=\"top-card\"><div class=\"top-card-info\"><b>Аккаунт</b><span>dare :)</span></div><div class=\"icon-box\">👤</div></div>" +
            "<div class=\"top-card\"><div class=\"top-card-info\"><b>Настройки</b><span>Авто-анализ</span></div><div class=\"icon-box\">⚙️</div></div>" +
            "</div>" +

            "<div class=\"card\">" +
            "<div class=\"card-header\"><div class=\"card-title\">Сенса & Микрофлик</div><button class=\"btn-primary\" onclick=\"AndroidBridge.executeCommand('settings put system pointer_speed 7'); AndroidBridge.showToast('Макс Сенса активирована :)');\">ВКЛ</button></div>" +
            "<div class=\"card-desc\">Автоматический анализ завершен. Подготовлен профиль отклика сенсора под быстрый микрофлик.</div>" +
            "</div>" +

            "<div class=\"card\">" +
            "<div class=\"card-title\">Растяжение экрана (Оверлей)</div>" +
            "<div class=\"card-desc\">Плавающая кнопка поверх игры с индикатором и прямой привязкой к Shizuku API.</div>" +
            "<div class=\"toggle-row\"><span>Плавающая кнопка оверлея</span>" +
            "<label class=\"switch\"><input type=\"checkbox\" id=\"floatToggle\" onchange=\"AndroidBridge.toggleFloatingButton(this.checked)\"><span class=\"slider\"></span></label></div>" +
            "<div class=\"status-badge\"><div class=\"status-dot\"></div>Shizuku API готов к работе</div></div>" +

            "<div class=\"card\">" +
            "<div class=\"card-title\">Бустер производительности</div>" +
            "<div class=\"card-desc\">Оптимизация нагрева, стабильные 120 FPS и отклик тачскрина.</div>" +
            "<div class=\"toggle-row\"><span>Максимальная частота (120 Гц)</span>" +
            "<label class=\"switch\"><input type=\"checkbox\" onchange=\"AndroidBridge.executeCommand('settings put system peak_refresh_rate 120.0'); AndroidBridge.showToast('120 Гц запрошено');\"><span class=\"slider\"></span></label></div>" +
            "<div class=\"toggle-row\" style=\"margin-top: 12px;\"><span>Отключение системных анимаций</span>" +
            "<label class=\"switch\"><input type=\"checkbox\" onchange=\"AndroidBridge.executeCommand('settings put global window_animation_scale 0.0'); AndroidBridge.showToast('Анимации отключены');\"><span class=\"slider\"></span></label></div>" +
            "</div>" +

            "<script>function hideModal() { let m = document.getElementById('permModal'); if(m) m.style.display = 'none'; }</script>" +
            "</body></html>";

        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
        setContentView(webView);

        checkAndRequestShizukuPermission();
    }

    private void checkAndRequestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (!Shizuku.checkSelfPermission().equals(PackageManager.PERMISSION_GRANTED)) {
                    Shizuku.requestPermission(SHIZUKU_CODE);
                }
            } else {
                Toast.makeText(this, "Запустите службу Shizuku на телефоне!", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onRequestPermissionResult(int requestCode, int grantResult) {
        if (requestCode == SHIZUKU_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Доступ Shizuku успешно получен!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            webView.evaluateJavascript("if(typeof hideModal === 'function') { hideModal(); }", null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(this::onRequestPermissionResult);
    }

    public class AppController {
        @JavascriptInterface
        public void executeCommand(String command) {
            runShizukuCommand(command);
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openPermissionSettings() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        @JavascriptInterface
        public void toggleFloatingButton(boolean show) {
            Intent serviceIntent = new Intent(MainActivity.this, OverlayService.class);
            serviceIntent.putExtra("action", show ? "START" : "STOP");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }

    public static void runShizukuCommand(String cmd) {
        new Thread(() -> {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    Process process = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
                    process.waitFor();
                } else {
                    Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    process.waitFor();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public static class OverlayService extends Service {
        private WindowManager windowManager;
        private Button floatingButton;
        private LinearLayout trashTarget;
        private boolean isStretched = false;

        @Override
        public IBinder onBind(Intent intent) { return null; }

        @Override
        public void onCreate() {
            super.onCreate();
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent != null && intent.getStringExtra("action") != null) {
                String action = intent.getStringExtra("action");
                if ("START".equals(action)) {
                    showOverlayButton();
                    showTrashTarget();
                } else if ("STOP".equals(action)) {
                    removeOverlayButton();
                    removeTrashTarget();
                    stopSelf();
                }
            }
            return START_NOT_STICKY;
        }

        private void showOverlayButton() {
            if (floatingButton != null) return;

            floatingButton = new Button(this);
            updateButtonStyle(false);
            floatingButton.setTextSize(13);
            floatingButton.setTypeface(null, Typeface.BOLD);
            // Отключаем системный звук/вибрацию при программных нажатиях на вьюху оверлея
            floatingButton.setHapticFeedbackEnabled(false);
            floatingButton.setSoundEffectsEnabled(false);

            int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    140, 105,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 120;
            params.y = 350;

            floatingButton.setOnClickListener(v -> {
                if (!isStretched) {
                    runShizukuCommand("wm size 1080x2400");
                    isStretched = true;
                    updateButtonStyle(true);
                } else {
                    runShizukuCommand("wm size reset");
                    isStretched = false;
                    updateButtonStyle(false);
                }
            });

            floatingButton.setOnTouchListener(new View.OnTouchListener() {
                private int initX, initY;
                private float touchX, touchY;
                private boolean isMoved = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initX = params.x;
                            initY = params.y;
                            touchX = event.getRawX();
                            touchY = event.getRawY();
                            isMoved = false;
                            if (trashTarget != null) trashTarget.setVisibility(View.VISIBLE);
                            return false;
                        case MotionEvent.ACTION_MOVE:
                            int deltaX = (int) (event.getRawX() - touchX);
                            int deltaY = (int) (event.getRawY() - touchY);
                            if (Math.abs(deltaX) > 5 || Math.abs(deltaY) > 5) {
                                isMoved = true;
                                params.x = initX + deltaX;
                                params.y = initY + deltaY;
                                windowManager.updateViewLayout(floatingButton, params);

                                if (params.y > 1500) {
                                    if (trashTarget != null) trashTarget.setBackgroundColor(Color.parseColor("#99FF3333"));
                                } else {
                                    if (trashTarget != null) trashTarget.setBackgroundColor(Color.parseColor("#88030712"));
                                }
                            }
                            return isMoved;
                        case MotionEvent.ACTION_UP:
                            if (trashTarget != null) {
                                trashTarget.setVisibility(View.GONE);
                                trashTarget.setBackgroundColor(Color.parseColor("#88030712"));
                            }
                            if (params.y > 1500) {
                                removeOverlayButton();
                                removeTrashTarget();
                                stopSelf();
                                return true;
                            }
                            break;
                    }
                    return false;
                }
            });

            try {
                windowManager.addView(floatingButton, params);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void updateButtonStyle(boolean active) {
            if (floatingButton == null) return;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(32f);
            if (active) {
                bg.setColor(Color.parseColor("#CC10B981"));
                bg.setStroke(3, Color.parseColor("#34D399"));
                floatingButton.setText("ON :)");
                floatingButton.setTextColor(Color.WHITE);
            } else {
                bg.setColor(Color.parseColor("#CC0059FF"));
                bg.setStroke(2, Color.parseColor("#66A3FF"));
                floatingButton.setText("16:9");
                floatingButton.setTextColor(Color.WHITE);
            }
            floatingButton.setBackground(bg);
        }

        private void showTrashTarget() {
            if (trashTarget != null) return;

            trashTarget = new LinearLayout(this);
            trashTarget.setOrientation(LinearLayout.VERTICAL);
            trashTarget.setGravity(Gravity.CENTER);
            trashTarget.setBackgroundColor(Color.parseColor("#88030712"));

            TextView trashText = new TextView(this);
            trashText.setText("Перетащите сюда для удаления [X]");
            trashText.setTextColor(Color.WHITE);
            trashText.setTextSize(12);
            trashText.setTypeface(null, Typeface.BOLD);
            trashTarget.addView(trashText);

            int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams trashParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, 220,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
            );
            trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

            try {
                windowManager.addView(trashTarget, trashParams);
                trashTarget.setVisibility(View.GONE);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void removeOverlayButton() {
            if (floatingButton != null) {
                try {
                    windowManager.removeView(floatingButton);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                floatingButton = null;
            }
        }

        private void removeTrashTarget() {
            if (trashTarget != null) {
                try {
                    windowManager.removeView(trashTarget);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                trashTarget = null;
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            removeOverlayButton();
            removeTrashTarget();
        }
    }
}
