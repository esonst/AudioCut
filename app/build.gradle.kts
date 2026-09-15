plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.audiocut"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.audiocut"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 重点：把c++共享库打包进apk，补齐 __gxx_personality_v0 符号
        // sherpa-onnx AAR 支持 arm64-v8a / armeabi-v7a / x86 / x86_64，这里同时加入 x86_64 以支持模拟器与 ChromeOS
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 本地 release 测试/打包：先使用 debug 签名密钥（正式发布请替换为正式 keystore 签名配置）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += listOf(
                "**/libonnxruntime.so",
                "**/libc++_shared.so"
            )
        }
    }

    lint {
        // sherpa-onnx AAR 内置的 libonnxruntime.so 为 4KB 页面对齐，属第三方库限制，项目侧无法修复
        disable += setOf("Aligned16KB")
    }
    buildToolsVersion = "37.0.0"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // ExoPlayer / Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    // ONNX Runtime & Sherpa ASR
    implementation(libs.onnxruntime.android)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    
    // FFmpegKit
    implementation(libs.ffmpeg.kit.full)

    // 模型压缩包解压（tar.bz2 / tar.gz）
    implementation(libs.commons.compress)


    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}