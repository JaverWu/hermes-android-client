# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to the default ProGuard rules.
# You can edit the include path and order by changing the proguardFiles element
# in your module's build.gradle.kts.

# OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep data classes used for JSON / Room (safety)
-keep class com.hermes.chat.data.** { *; }
