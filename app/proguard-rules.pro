# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# WebRTC
-keep class org.webrtc.** { *; }

# nv-websocket-client
-keep class com.neovisionaries.ws.client.** { *; }

# Keep data classes used for serialization
-keep class com.ufo.galaxy.api.** { *; }
-keep class com.ufo.galaxy.communication.** { *; }
-keep class com.ufo.galaxy.protocol.** { *; }

# Keep accessibility services
-keep class * extends android.accessibilityservice.AccessibilityService

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
