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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ==================== TENSORFLOW LITE ====================
# Keep TensorFlow Lite classes
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keep class org.tensorflow.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== MODEL CLASSES ====================
# Keep your Detection and metric classes
-keep class com.plcoding.recordscreen.ScreenRecordService$Detection { *; }
-keep class com.plcoding.recordscreen.PerformanceMetrics { *; }
-keep class com.plcoding.recordscreen.HazardDetectionStats { *; }
-keep class com.plcoding.recordscreen.ScreenRecordConfig { *; }

# ==================== COROUTINES ====================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ==================== GENERAL ====================
# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep service classes
-keep class com.plcoding.recordscreen.ScreenRecordService { *; }

# Suppress warnings
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.android.gms.**
```

---

## 📋 **What Changed**

✅ **Kept** all the original comments (the `#` lines at the top)
✅ **Added** TensorFlow Lite protection rules
✅ **Added** your app-specific class protection

---

## 🎯 **Quick Action Steps**

1. **Open** `app/proguard-rules.pro` in Android Studio
2. **Copy** the entire code block above
3. **Replace** everything in the file
4. **Save** (Ctrl+S / Cmd+S)
5. **Sync** Gradle

---

## ✅ **Verification**

After adding, your file structure should show:
```
app/
├── build.gradle.kts
├── proguard-rules.pro  ← Updated with new rules
└── src/