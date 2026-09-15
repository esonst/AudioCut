# Sherpa-ONNX R8 Keep Rules
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }

# JNI related
-keepclasseswithmembernames class * {
    native <methods>;
}

# Data Models
-keep class com.example.AudioCut.data.model.** { *; }


# Commons-Compress（模型 tar.bz2 解压）
-keep class org.apache.commons.compress.** { *; }
