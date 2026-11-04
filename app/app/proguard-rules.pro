# Keep accessibility service
-keep class com.vivonoads.core.service.** { *; }
-keep class * extends android.accessibilityservice.AccessibilityService

# Rule models
-keep class com.vivonoads.core.rule.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
