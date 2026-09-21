# ExoPlayer 2 官方混淆规则基线（旧包名，非 Media3）
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# Gson：保留 DTO 与领域模型字段（按注解与包名双保险）
-keep class com.tvbox.android44.data.remote.dto.** { *; }
-keep class com.tvbox.android44.domain.model.** { *; }
-keep class com.tvbox.android44.data.local.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }
-keep class com.tvbox.android44.common.imageloader.** { *; }
-dontwarn com.bumptech.glide.**

# OkHttp / Okio（3.12 冻结分支）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ZXing
-keep class com.google.zxing.** { *; }
