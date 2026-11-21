# Preserve debugging stacktrace line numbers while stripping source paths
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Core Android Entry Points & WorkManager Reflection
-keep class com.example.models.ModelDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room Database & Entities / DAOs
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.*Entity { *; }

# Kotlinx Serialization & Type-Safe Navigation Compose Routes
-keep class com.example.ui.navigation.AppRoute** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }

# MediaPipe Tasks Audio & LiteRT-LM (Kokoro-82M) JNI bindings
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Sherpa-ONNX (Piper VITS) JNI bindings & callback signatures
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ML Kit On-Device Translation
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Optional compression codecs referenced dynamically by Commons Compress
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.apache.commons.codec.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
