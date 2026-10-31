# 保持 WebView 桥接与 JS 接口不被混淆
-keepclassmembers class com.gmssh.android.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.gmssh.android.** { *; }
