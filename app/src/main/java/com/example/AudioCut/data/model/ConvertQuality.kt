package com.example.audiocut.data.model

/**
 * 转换质量枚举
 */
enum class ConvertQuality {
    HIGH,   // 高质量
    LOW,    // 低质量
    EXTRACT // 复制音频：视频提取原音频流（-c:a copy，不重新编码，速度最快）
}