package com.example.AudioCut.data.model

/**
 * 标记音频片段模型（支持微调、重命名与拼接导出）
 */
data class AudioSegment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val audioId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val isSelected: Boolean = true,
    val colorIndex: Int = 0
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0)

    val formattedRange: String
        get() = "${AudioItem.formatDuration(startMs)} ~ ${AudioItem.formatDuration(endMs)}"
}
