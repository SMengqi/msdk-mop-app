# MSDK 相关 keep 规则。
# sample 工程 release 开了 minifyEnabled 却没有任何规则文件，这里补齐。
# aar 自带 consumer rules 能保住 SDK 自身，但保不住通过反射/JNI 触及的本工程代码。

# --- DJI SDK ---
-keep class dji.** { *; }
-keep class com.dji.** { *; }
-keep interface dji.** { *; }
-dontwarn dji.**
-dontwarn com.dji.**

# 加壳库，Application.attachBaseContext 里调用，必须保留
-keep class com.cySdkyc.** { *; }
-dontwarn com.cySdkyc.**

# --- JNI 回调 ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- 本工程中被 SDK 回调持有的类 ---
# MopChannel / MsdkManager 内部实现了 SDKManagerCallback、PipelineConnectionListener 等接口。
# 后续新增回调实现类时一并纳入。
-keep class com.bxt.mop.sdk.** { *; }
-keep class com.bxt.mop.mop.** { *; }

# --- 通用 ---
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepattributes SourceFile, LineNumberTable
