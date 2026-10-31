# gomobile bindings are looked up from native code by name.
-keep class go.** { *; }
-keep class top.bangwu.taildeck.tsbridge.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
