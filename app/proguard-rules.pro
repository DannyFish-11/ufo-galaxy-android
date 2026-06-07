# UFO Galaxy Android - ProGuard 规则
# ---------------------------------------------------------------------------
# 1. 应用类规则 —— 仅保留 AndroidManifest 声明的组件和需要反射的类
#    业务逻辑类允许混淆以增大逆向难度。
# ---------------------------------------------------------------------------

# 保留 @Keep 注解的类（显式标记需要保留的类/方法/字段）
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers @androidx.annotation.Keep class * { *; }

# 保留 Activities（在 AndroidManifest 中通过类名引用）
-keep class com.ufo.galaxy.**.**Activity { *; }
-keep class com.ufo.galaxy.**.**Activity$* { *; }

# 保留 Services（在 AndroidManifest 中通过类名引用）
-keep class com.ufo.galaxy.**.**Service { *; }
-keep class com.ufo.galaxy.**.**Service$* { *; }

# 保留 BroadcastReceivers（在 AndroidManifest 中通过类名引用）
-keep class com.ufo.galaxy.**.**Receiver { *; }
-keep class com.ufo.galaxy.**.**Receiver$* { *; }

# 保留 Application 子类
-keep class com.ufo.galaxy.**.**Application { *; }

# 保留 Parcelable 数据类（CREATOR 字段通过反射访问）
-keepclassmembers class com.ufo.galaxy.** implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 保留 Serializable 类及其成员（序列化依赖字段名一致）
-keepclassmembers class com.ufo.galaxy.** implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留枚举类（枚举名称和 values() 被反射调用）
-keepclassmembers enum com.ufo.galaxy.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Jetpack Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 保留 R 文件
-keepclassmembers class **.R$* {
    public static <fields>;
}

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 保留 Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── WebRTC (org.webrtc) ────────────────────────────────────────────────────
# WebRTC classes are NOT in com.ufo.galaxy.** package — must keep explicitly.
# Without these rules, release builds (minifyEnabled=true) will strip
# org.webrtc.* classes causing runtime crashes in WebRTC signaling.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── Native libraries (JNI) ─────────────────────────────────────────────────
# llama.cpp JNI — native methods are accessed via JNI, keep class names.
-keep class de.kherud.llama.** { *; }
-dontwarn de.kherud.llama.**

# ── Remove logs (debug only) ───────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
