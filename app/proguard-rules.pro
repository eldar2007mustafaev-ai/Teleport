-optimizationpasses 5
-overloadaggressively
-repackageclasses 'o'
-allowaccessmodification

# Сохраняем только точки входа WebView Bridge и Shizuku
-keepclassmembers class com.teleport.so2.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.teleport.so2.MainActivity { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
