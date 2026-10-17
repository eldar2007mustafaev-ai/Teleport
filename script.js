        cat << 'EOF' > app/src/main/java/com/teleport/sens/MainActivity.java
        package com.teleport.sens;

        import android.app.Activity;
        import android.os.Bundle;
        import android.webkit.JavascriptInterface;
        import android.webkit.WebSettings;
        import android.webkit.WebView;
        import android.webkit.WebViewClient;
        import android.widget.Toast;

        public class MainActivity extends Activity {
            private WebView webView;

            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                
                webView = new WebView(this);
                WebSettings webSettings = webView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                
                webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
                webView.setWebViewClient(new WebViewClient());
                webView.loadUrl("file:///android_asset/index.html");
                
                setContentView(webView);
            }

            public class WebAppInterface {
                @JavascriptInterface
                public void applyRatio(String ratio) {
                    runOnUiThread(() -> {
                        try {
                            String cmd = "";
                            if (ratio.equals("4:3")) {
                                // Честное уменьшение ширины для растяжения картинки в игре
                                cmd = "wm size 1080x1440 && wm density 360";
                            } else if (ratio.equals("16:10")) {
                                cmd = "wm size 1080x1728 && wm density 400";
                            } else {
                                cmd = "wm size reset && wm density reset";
                            }
                            executeShizukuCommand(cmd);
                            Toast.makeText(MainActivity.this, "Режим " + ratio + " применен!", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Ошибка применения разрешения", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @JavascriptInterface
                public void applySensBoost() {
                    runOnUiThread(() -> {
                        try {
                            // Жесткий буст отклика тача и ускорения под «тепающуюся» сенсу
                            executeShizukuCommand("settings put system pointer_speed 7 && settings put system touch_exploration_enabled 0");
                            Toast.makeText(MainActivity.this, "Телепорт сенсы активирован!", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Ошибка активации сенсы", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }

            private void executeShizukuCommand(String command) {
                try {
                    java.lang.Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "su -c '" + command + "' || sh -c '" + command + "'"});
                    process.waitFor();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        EOF
