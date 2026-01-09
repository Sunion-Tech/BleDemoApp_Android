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

# ServiceLoader support
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Most of volatile fields are updated with AFU and should not be mangled
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

-keep class * extends java.util.ListResourceBundle {
    protected java.lang.Object[][] getContents();
}

# RxAndroidBle
-keep class com.polidea.rxandroidble2.** {*;}

-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# App Models
-keep class com.sunion.ble.demoapp.data.api.** {*;}
-keep class com.sunion.core.ble.entity.** {*;}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Timber
-keep class timber.log.Timber { *; }