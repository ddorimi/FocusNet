# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ==================== TENSORFLOW LITE ====================
# Keep TensorFlow Lite classes
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# Keep TensorFlow classes
-keep class org.tensorflow.** { *; }

# Keep native methods for TFLite
-keepclasseswithmembernames class * {
    native <methods>;
}

# Prevent obfuscation of TFLite model files
-keepclassmembers class * {
    @org.tensorflow.lite.annotations.UsedByGeneratedCode *;
}

# ==================== MODEL CLASSES ====================
# ✅ FIXED: Detection is now inside ScreenRecordService
-keep class com.plcoding.recordscreen.ScreenRecordService { *; }
-keep class com.plcoding.recordscreen.ScreenRecordService$Detection { *; }
-keep class com.plcoding.recordscreen.ScreenRecordService$OverlayView { *; }

# Keep data classes (outside main class)
-keep class com.plcoding.recordscreen.PerformanceMetrics { *; }
-keep class com.plcoding.recordscreen.HazardDetectionStats { *; }
-keep class com.plcoding.recordscreen.ScreenRecordConfig { *; }
-keep class com.plcoding.recordscreen.ModelComparison { *; }

# ==================== KOTLIN & COROUTINES ====================
# Keep Kotlin metadata
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep StateFlow and Flow
-keep class kotlinx.coroutines.flow.** { *; }
-keepclassmembers class kotlinx.coroutines.flow.StateFlow { *; }

# ==================== ANDROID COMPONENTS ====================
# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Service classes
-keep public class * extends android.app.Service

# Keep Views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ==================== COMPOSE ====================
# Keep Compose classes
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep interface androidx.compose.** { *; }

# Keep @Composable functions
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# ==================== SUPPRESS WARNINGS ====================
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.android.gms.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.coroutines.**

# ==================== OPTIMIZATION ====================
# Don't optimize TFLite code (can cause issues)
-keep,allowobfuscation,allowoptimization class org.tensorflow.lite.** { *; }

# General optimization settings
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

# ==================== DEBUGGING (Remove in production) ====================
# Uncomment for debugging ProGuard issues
# -printconfiguration proguard-config.txt
# -printmapping proguard-mapping.txt
# -printseeds proguard-seeds.txt
# -printusage proguard-usage.txt