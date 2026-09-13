# Sherpa-ONNX R8 Keep Rules
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }

# JNI related
-keepclasseswithmembernames class * {
    native <methods>;
}

# Data Models
-keep class com.example.mp3player.data.model.** { *; }
