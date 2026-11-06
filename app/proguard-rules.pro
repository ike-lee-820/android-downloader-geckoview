-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# GeckoView 通过 JNI 与反射调用大量类，R8 全量保留其包名空间。
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.thirdparty.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# WebExtension 与 Compose 侧回调由 Gecko 运行时按接口反射调用。
-keep class com.yuanbao.downloader.browser.** { *; }
-keep class com.yuanbao.downloader.service.** { *; }
-keep class com.yuanbao.downloader.engine.** { *; }

# Hilt / Room / WorkManager / OkHttp 常规保留。
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends androidx.hilt.work.Worker { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
