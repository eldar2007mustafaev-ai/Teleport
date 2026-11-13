-optimizationpasses 5
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
-ignorewarnings
-dontwarn **

-obfuscationdictionary dict.txt
-classobfuscationdictionary dict.txt
-packageobfuscationdictionary dict.txt

-keepattributes JavascriptInterface

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
