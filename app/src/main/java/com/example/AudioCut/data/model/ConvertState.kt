package com.example.audiocut.data.model

/**
 * 转换状态数据类
 */
data class ConvertState(
    val isConverting: Boolean = false,
    val progress: Float = 0f, // 0.0f - 1.0f
    val elapsedTimeMs: Long = 0L, // 已用时间（毫秒）
    val remainingTimeMs: Long = 0L, // 剩余时间（毫秒）
    val speed: Float = 1f, // 当前转换速度（倍速）
    val inputFilePath: String? = null,
    val outputFilePath: String? = null,
    val quality: ConvertQuality = ConvertQuality.HIGH,
    val error: String? = null
) {
    val isCompleted: Boolean get() = !isConverting && progress >= 1f && error == null
    val isFailed: Boolean get() = error != null
    val isIdle: Boolean get() = !isConverting && progress == 0f
}