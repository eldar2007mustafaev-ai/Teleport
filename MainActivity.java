package com.teleport.sens;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 1234;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());

        // Загружаем интерфейс прямо из кода — папки assets больше не нужны!
        String htmlContent = "<" + "!DOCTYPE html>" +
            "<html lang=\"ru\"><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "<title>Teleport App</title>" +
            "<style>" +
            "* { box-sizing: border-box; margin: 0; padding: 0; font-family: sans-serif; }" +
            "body { background-color: #000000; color: #ffffff; padding: 16px; }" +
            "h1 { font-size: 20px; font-weight: 700; margin-bottom: 16px; color: #0059FF; text-transform: uppercase; }" +
            ".card { background: linear-gradient(135deg, rgba(3, 9, 35, 0.8) 0%, rgba(0, 0, 0, 0.9) 100%); border: 1px solid rgba(0, 89, 255, 0.25); border-radius: 16px; padding: 16px; margin-bottom: 14px; }" +
            ".card-title { font-size: 14px; font-weight: 600; color: #8da4ef; margin-bottom: 10px; text-transform: uppercase; }" +
            ".btn { background: linear-gradient(135deg, #0059FF 0%, #003399 100%); color: #fff; border: none; border-radius: 12px; padding: 12px; font-size: 14px; font-weight: 600; width: 100%; cursor: pointer; margin-top: 8px; }" +
            ".grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }" +
            ".preset-btn { background: rgba(255, 255, 255, 0.05); border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 10px; padding: 10px; color: #fff; text-align: left; }" +
            ".preset-btn b { display: block; font-size: 14px; color: #0059FF; }" +
            ".preset-btn span { font-size: 11px; color: #888; }" +
            ".toggle-row { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; font-size: 13px; }" +
            ".switch { position: relative; display: inline-block; width: 44px; height: 24px; }" +
            ".switch input { opacity: 0; width: 0; height: 0; }" +
            ".slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #222; transition: .3s; border-radius: 24px; border: 1px solid #444; }" +
            ".slider:before { position: absolute; content: \"\"; height: 16px; width: 16px; left: 3px; bottom: 3px; background-color: white; transition: .3s; border-radius: 50%; }" +
            "input:checked + .slider { background-color: #0059FF; }" +
            "input:checked + .slider:before { transform: translateX(20px); }" +
            "</style></head><body>" +
            "<h1>Teleport // Standoff 2</h1>" +
            "<div class=\"card\"><div class=\"card-title\">Турбо Сенса & Телепорт</div>" +
            "<button class=\"btn\" onclick=\"AndroidBridge.executeCommand('gain=1.8'); AndroidBridge.showToast('Макс Телепорт активен!')\">АКТИВИРОВАТЬ МАКС TELEPORT</button></div>" +
            "<div class=\"card\"><div class=\"card-title\">Растяжение экрана</div>" +
            "<div class=\"toggle-row\"><span>Плавающая кнопка</span>" +
            "<label class=\"switch\"><input type=\"checkbox\" onchange=\"AndroidBridge.toggleFloatingButton(this.checked)\"><span class=\"slider\"></span></label></div></div>" +
            "</body></html>";

        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
        setContentView(webView);

        checkOverlayPermission();
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
            }
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
        public void toggleFloatingButton(boolean show) {
            Intent serviceIntent = new Intent(MainActivity.this, OverlayService.class);
            serviceIntent.putExtra("action", show ? "START_BUTTON" : "STOP_BUTTON");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }
}
