# ProGuard rules for GPS Clientes — release minify + shrinkResources enabled (v1.1.0)
-keep class com.gpsclientes.data.local.** { *; }
-keep class com.gpsclientes.data.export.** { *; }
-keep class com.gpsclientes.domain.** { *; }
-keep class androidx.room.** { *; }
-dontwarn org.apache.poi.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
# osmdroid — keep tile provider and views
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
