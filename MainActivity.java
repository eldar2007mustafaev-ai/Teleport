package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;
    private WindowManager windowManager;
    private Button floatingButton;
    private boolean isStretched = false;
    private boolean isOverlayVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());

        // HTML-интерфейс (Cyberpunk / Glassmorphism)
        String htmlContent = "<!DOCTYPE html>" +
            "<html lang=\"ru\"><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "<title>Teleport // Standoff 2</title>" +
            "<style>" +
            "* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }" +
            "body { background-color: #030712; color: #ffffff; padding: 16px; padding-bottom: 40px; }" +
            
            ".header { display: flex; align-items: center; margin-bottom: 20px; }" +
            ".back-btn { background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); width: 38px; height: 38px; border-radius: 12px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 16px; margin-right: 12px; }" +
            ".title { font-size: 18px; font-weight: 700; letter-spacing: 0.5px; color: #fff; text-transform: uppercase; }" +
            
            ".card { background: linear-gradient(135deg, rgba(13, 20, 45, 0.8) 0%, rgba(3, 7, 18, 0.95) 100%); border: 1px solid rgba(0, 89, 255, 0.25); border-radius: 20px; padding: 18px; margin-bottom: 16px; box-shadow: 0 10px 30px rgba(0,0,0,0.6); backdrop-filter: blur(12px); }" +
            ".card-title { font-size: 13px; font-weight: 700; color: #7895cb; margin-bottom: 10px; text-transform: uppercase; letter-spacing: 0.5px; }" +
            ".card-desc { font-size: 12px; color: #8a9bb8; margin-bottom: 14px; line-height: 1.4; }" +
            
            ".btn { background: linear-gradient(135deg, #0059FF 0%, #003399 100%); color: #fff; border: none; border-radius: 14px; padding: 14px; font-size: 14px; font-weight: 600; width: 100%; cursor: pointer; box-shadow: 0 4px 20px rgba(0, 89, 255, 0.4); text-align: center; }" +
            
            ".grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }" +
            ".preset-card { background: rgba(255, 255, 255, 0.03); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 14px; padding: 12px; text-align: left; }" +
            ".preset-card b { display: block; font-size: 14px; color: #fff; margin-bottom: 4px; }" +
            ".preset-card span { font-size: 11px; color: #7895cb; }" +
            
            ".toggle-row { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; font-size: 13px; }" +
            ".switch { position: relative; display: inline-block; width: 48px; height: 26px; }" +
            ".switch input { opacity: 0; width: 0; height: 0; }" +
            ".slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(255,255,255,0.1); transition: .3s; border-radius: 26px; border: 1px solid rgba(255,255,255,0.15); }" +
            ".slider:before { position: absolute; content: \"\"; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .3s; border-radius: 50%; }" +
            "input:checked + .slider { background-color: #0059FF; }" +
            "input:checked + .slider:before { transform: translateX(22px); }" +
            
            ".status-badge { display: flex; align-items: center; font-size: 12px; color: #4ade80; margin-top: 10px; }" +
            ".status-dot { width: 6px; height: 6px; background-color: #4ade80; border-radius: 50%; margin-right: 6px; box-shadow: 0 0 8px #4ade80; }" +
            
            "#permModal { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(3,7,18,0.92); z-index: 999; display: flex; align-items: center; justify-content: center; padding: 20px; backdrop-filter: blur(15px); }" +
            ".modal-box { background: linear-gradient(135deg, #0d142d 0%, #030712 100%); border: 1px solid rgba(0,89,255,0.4); border-radius: 24px; padding: 24px; text-align: center; box-shadow: 0 20px 50px rgba(0,0,0,0.8); }" +
            ".modal-title { font-size: 16px; font-weight: 700; color: #fff; margin-bottom: 10px; text-transform: uppercase; }" +
            ".modal-text { font-size: 13px; color: #8a9bb8; margin-bottom: 20px; line-height: 1.5; }" +
            "</style></head><body>" +

            "<div id=\"permModal\">" +
            "<div class=\"modal-box\">" +
            "<div class=\"modal-title\">Требуется доступ</div>" +
            "<div class=\"modal-text\">Для работы плавающей кнопки растяжения экрана поверх Standoff 2 необходимо выдать разрешение на отображение поверх других окон.</div>" +
            "<button class=\"btn\" onclick=\"AndroidBridge.openPermissionSettings()\">РАЗРЕШИТЬ ДОСТУП</button>" +
            "</div></div>" +

            "<div class=\"header\"><div class=\"back-btn\">←</div><div class=\"title\">Teleport // Standoff 2</div></div>" +

            "<div class=\"card\"><div class=\"card-title\">Турбо Сенса & Телепорт</div>" +
            "<div class=\"card-desc\">Готовые варианты прошивки отклика сенсора под микрофлик.</div>" +
            "<button class=\"btn\" onclick=\"AndroidBridge.executeCommand('settings put system pointer_speed 7'); AndroidBridge.showToast('Макс Телепорт активен!');\">АКТИВИРОВАТЬ МАКС TELEPORT</button>" +
            "<div class=\"grid-2\" style=\"margin-top: 12px;\">" +
            "<div class=\"preset-card\" onclick=\"AndroidBridge.executeCommand('settings put system pointer_speed 7'); AndroidBridge.showToast('Пресет: Резкий');\"><b>Резкий</b><span>1.30 • 1 • 0.60</span></div>" +
            "<div class=\"preset-card\" onclick=\"AndroidBridge.executeCommand('settings put system pointer_speed 5'); AndroidBridge.showToast('Пресет: Чистый');\"><b>Чистый</b><span>1.00 • 3 • 0.80</span></div>" +
            "</div></div>" +

            "<div class=\"card\"><div class=\"card-title\">Растяжение экрана</div>" +
            "<div class=\"card-desc\">Плавающая кнопка поверх игры для быстрого переключения разрешений.</div>" +
            "<div class=\"toggle-row\"><span>Плавающая кнопка оверлея</span>" +
            "<label class=\"switch\"><input type=\"checkbox\" id=\"floatToggle\" onchange=\"AndroidBridge.toggleFloatingButton(this.checked)\"><span class=\"slider\"></span></label></div>" +
            "<div class=\"status-badge\"><div class=\"status-dot\"></div>Shizuku / ADB мост подключен</div></div>" +

            "<div class=\"card\"><div class=\"card-title\">Системный бустер FPS</div>" +
            "<div class=\"toggle-row\"><span>Максимальная частота (120 Гц)</span>" +
            "<label class=\"switch\"><input type=\"checkbox\" onchange=\"AndroidBridge.executeCommand('settings put system peak_refresh_rate 120.0'); AndroidBridge.showToast('120 Гц запрошено');\"><span class=\"slider\"></span></label></div>" +
            "<div class=\"toggle-row\" style=\"margin-top: 12px;\"><span>Отключение системных анимаций</span>" +
            "<label class=\"switch\"><input type=\"checkbox\" onchange=\"AndroidBridge.executeCommand('settings put global window_animation_scale 0.0'); AndroidBridge.showToast('Анимации отключены');\"><span class=\"slider\"></span></label></div>" +
            "</div>" +

            "<script>function hideModal() { let m = document.getElementById('permModal'); if(m) m.style.display = 'none'; }</script>" +
            "</body></html>";

        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
        setContentView(webView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            webView.evaluateJavascript("if(typeof hideModal === 'function') { hideModal(); }", null);
        }
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void executeCommand(String command) {
            new Thread(() -> {
                try {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
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
            runOnUiThread(() -> {
                if (show) {
                    showFloatingButtonDirectly();
                } else {
                    removeFloatingButtonDirectly();
                }
            });
        }
    }

    private void showFloatingButtonDirectly() {
        if (floatingButton != null || isOverlayVisible) return;

        floatingButton = new Button(this);
        floatingButton.setText("16:9");
        floatingButton.setTextColor(Color.WHITE);
        floatingButton.setTextSize(12);
        floatingButton.setBackgroundColor(Color.parseColor("#CC0059FF"));

        int layoutParamType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParamType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParamType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                130, 130,
                layoutParamType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        floatingButton.setOnClickListener(v -> {
            new Thread(() -> {
                try {
                    if (!isStretched) {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size 1080x2400"});
                        isStretched = true;
                        runOnUiThread(() -> floatingButton.setText("ON"));
                    } else {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "wm size reset"});
                        isStretched = false;
                        runOnUiThread(() -> floatingButton.setText("16:9"));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        });

        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingButton, params);
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(floatingButton, params);
            isOverlayVisible = true;
            Toast.makeText(this, "Плавающая кнопка активирована", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Ошибка оверлея: дайте разрешение поверх окон", Toast.LENGTH_LONG).show();
        }
    }

    private void removeFloatingButtonDirectly() {
        if (floatingButton != null) {
            try {
                windowManager.removeView(floatingButton);
            } catch (Exception e) {
                e.printStackTrace();
            }
            floatingButton = null;
            isOverlayVisible = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeFloatingButtonDirectly();
    }
}
