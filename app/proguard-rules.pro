# Rust engine: JNI looks these natives up by name.
-keepclassmembers class com.arivna.browser.ShieldNative { native <methods>; }
-keep class com.arivna.browser.ShieldNative
# Methods the page's JavaScript calls through addJavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
