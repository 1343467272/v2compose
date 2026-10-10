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

## 内嵌 sing-box（libbox）通过 gobind/JNI 回调 Kotlin 实现，保留桥接类与方法名
-keep class io.github.v2compose.singbox.** { *; }

## Serialization Beans
-keep class io.github.v2compose.bean.** { *; }
-keep class io.github.v2compose.network.bean.** { *; }

## OkHttp
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

## Fruit
# keep @Slice annotated classes and @Pick fields for KSP generated adapters
-keep @io.github.fruit.annotations.Slice class * { *; }
-keepclassmembers class * {
    @io.github.fruit.annotations.Pick <fields>;
}
