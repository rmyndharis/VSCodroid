# ============================================================================
# VSCodroid ProGuard / R8 Rules
# ============================================================================

# ----------------------------------------------------------------------------
# WebView JavaScript Interface
# ----------------------------------------------------------------------------
# Methods annotated with @JavascriptInterface are called reflectively from
# JavaScript via addJavascriptInterface. R8 must not remove or rename them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ----------------------------------------------------------------------------
# Bridge classes (exposed to WebView JavaScript context)
# ----------------------------------------------------------------------------
# All classes in the bridge package are accessed reflectively from the WebView.
# Keep class names and all public members intact.
-keep class com.vscodroid.bridge.** { *; }

# ----------------------------------------------------------------------------
# Application class
# ----------------------------------------------------------------------------
# Referenced by name in AndroidManifest.xml.
-keep class com.vscodroid.VSCodroidApp { *; }

# ----------------------------------------------------------------------------
# WebKit
# ----------------------------------------------------------------------------
# Suppress warnings for optional WebKit APIs that may not be present on all
# API levels. The app performs runtime checks before using these APIs.
-dontwarn androidx.webkit.**
