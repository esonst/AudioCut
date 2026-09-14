package com.example.mp3player.asr

/**
 * 离线文稿转写流水线配置（分块 / VAD / 识别 / 标点分段）
 */
data class AsrConfig(
    /** 是否启用 VAD 语音检测（过滤静音，仅识别人声） */
    val useVad: Boolean = true,
    val vadThreshold: Float = 0.5f,
    val vadMinSilence: Float = 0.5f,
    val vadMinSpeech: Float = 0.25f,
    val vadMaxSpeech: Float = 30.0f,
    /** 是否启用分块处理（关闭则整段识别） */
    val useSlicing: Boolean = true,
    /** 分块目标时长（秒） */
    val chunkSeconds: Int = 30,
    /** 是否启用智能分句（punct-ct 模型加标点；关闭则按停顿机械分句） */
    val useSmartPunctuation: Boolean = false,
    /** 识别线程数 */
    val asrThreads: Int = 2
) {
    val chunkTargetMs: Long
        get() = chunkSeconds.coerceAtLeast(1) * 1000L
}
