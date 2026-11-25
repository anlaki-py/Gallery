-keep class org.fossify.** { *; }
-dontwarn android.graphics.Canvas
-dontwarn org.fossify.**
-dontwarn org.apache.**

# Picasso
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# RenderScript
-keepclasseswithmembernames class * {
native <methods>;
}
-keep class androidx.renderscript.** { *; }

# Reprint
-keep class com.github.ajalt.reprint.module.** { *; }

# Protobuf Lite builds schemas by reflecting on generated field names, including MediaPipe SystemInfo.
# Upstream rule: https://github.com/protocolbuffers/protobuf/blob/main/java/lite/proguard.pgcfg
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
