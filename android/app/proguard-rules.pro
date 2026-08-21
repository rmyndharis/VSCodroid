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
# Exception type names that reach the user
# ----------------------------------------------------------------------------
# FirstRunSetup.describeFailure builds the setup-failure text the splash screen
# shows out of error.javaClass.simpleName, so any app exception R8 renames is
# read by the user as the obfuscated token instead of a type: a failed manifest
# rewrite reported itself as "oo: could not write ...". The rule is written over
# the shape rather than over the class that surfaced it, so an exception type
# added later is legible without anyone remembering this file. -keepnames, so
# unused classes are still removed and only the name is pinned; members stay
# renameable because none of them is ever printed.
-keepnames class com.vscodroid.** extends java.lang.Throwable

# ----------------------------------------------------------------------------
# WebKit
# ----------------------------------------------------------------------------
# Suppress warnings for optional WebKit APIs that may not be present on all
# API levels. The app performs runtime checks before using these APIs.
-dontwarn androidx.webkit.**
