# Proguard rules for AirGrab
-keepattributes *Annotation*
-dontwarn com.google.mediapipe.**
-keep class com.google.mediapipe.** { *; }

# Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Nearby Connections
-keep class com.google.android.gms.nearby.** { *; }
