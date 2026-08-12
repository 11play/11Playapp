# =========================================================
# 11PLAY — PROGUARD RULES
# =========================================================

# Keep Firebase / Google authentication metadata safe.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep JavaScript bridge methods exposed to WebView.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep custom WebView bridge classes.
-keep class com.elevenplay.app.bridge.** { *; }

# Keep authentication classes.
-keep class com.elevenplay.app.auth.** { *; }

# Keep WebView support classes.
-keep class com.elevenplay.app.web.** { *; }

# Suppress harmless optional dependency warnings.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**