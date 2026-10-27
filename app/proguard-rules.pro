# ProGuard-Datei für die HomeMoniTor-App.
# Der Release-Build referenziert diese Datei, daher muss sie existieren.

# Keep-Klassen für CameraX / Reflection
-keep class androidx.camera.** { *; }
-keep class com.google.zxing.** { *; }

# Kotlin
-keep class kotlin.Metadata { *; }

# Nicht entfernen: Modelle für Serializable/RParcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
