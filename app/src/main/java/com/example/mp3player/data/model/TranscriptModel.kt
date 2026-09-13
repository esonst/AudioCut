package com.example.mp3player.data.model

/**
 * 语音识别字级时间戳单元
 */
data class TranscriptWord(
    val id: Long,
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val isHighlighted: Boolean = false
)

/**
 * 分句模型（每句一行，包含句子起止时间与逐字对象）
 */
data class TranscriptSentence(
    val id: Long,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<TranscriptWord>
)

/**
 * 分段模型（保留兼容）
 */
data class TranscriptParagraph(
    val id: Long,
    val sentences: List<TranscriptSentence>
) {
    val fullText: String
        get() = sentences.joinToString("") { it.text }

    val startMs: Long
        get() = sentences.firstOrNull()?.startMs ?: 0L

    val endMs: Long
        get() = sentences.lastOrNull()?.endMs ?: 0L
}

/**
 * 完整文稿识别结果
 */
data class TranscriptResult(
    val audioId: Long,
    val fullText: String,
    val words: List<TranscriptWord>,
    val sentences: List<TranscriptSentence> = emptyList(),
    val paragraphs: List<TranscriptParagraph> = emptyList(),
    val durationMs: Long,
    val isCompleted: Boolean = true,
    val processedDurationMs: Long = durationMs
)
