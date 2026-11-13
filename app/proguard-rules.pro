-optimizationpasses 5
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile

-obfuscationdictionary dict.txt
-classobfuscationdictionary dict.txt
-packageobfuscationdictionary dict.txt

-keepattributes JavascriptInterface

# Оставляем имена ТОЛЬКО у точек входа из JS, все остальные методы (isClientTampered, verifyLicense, ApiManager) переименуются в a, A00, b!
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep public class com.teleport.so2.MainActivity {
    public <init>();
    protected void onCreate(android.os.Bundle);
}
-keep public class com.teleport.so2.FloatingButtonService {
    public <init>();
}

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn kotlin.**
