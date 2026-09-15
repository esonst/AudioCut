package com.example.audiocut.data.model

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
    val processedDurationMs: Long = durationMs,
    /** 是否已执行排版优化（true 时文稿每段开头显示 [hh:mm:ss] 时间戳行） */
    val isLayoutOptimized: Boolean = false
)


/**
 * 将毫秒时间格式化为 "[hh:mm:ss]"（时/分/秒均补零），用于文稿段首时间戳行
 */
fun formatTranscriptTimestamp(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "[%02d:%02d:%02d]".format(h, m, s)
}

/**
 * 时间戳行在文稿文本中的长度（时间戳字符数 + 换行符 1）
 */
fun transcriptTimestampLineLength(ms: Long): Int = formatTranscriptTimestamp(ms).length + 1
