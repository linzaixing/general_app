# Add project specific ProGuard rules here.

# Keep kotlinx.serialization runtime metadata for serializers reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.robotx.app.**$$serializer { *; }
-keepclassmembers class com.robotx.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.robotx.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# CameraX
-keep class androidx.camera.** { *; }

# Kotlin
-keepclassmembers class kotlin.Metadata { *; }
