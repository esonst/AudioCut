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
# Commons-Compress 可选压缩器依赖（zstd/brotli/xz/pack200）未引入，项目仅用 tar 解压，忽略缺失类告警
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
