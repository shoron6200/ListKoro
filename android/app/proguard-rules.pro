# youtubedl-android (Jackson diye reflection kore)
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.apache.commons.**
-dontwarn org.w3c.dom.bootstrap.**
# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
# JS bridge
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# yt-dlp/python unzip (youtubedl_common.ZipUtils) commons-compress use kore; eta reflection diye
# zip extra-field class banay. R8 era shoriye dile init "failed to initialize" hoy.
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
