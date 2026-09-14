package com.example.mp3player.asr

import com.example.mp3player.data.model.TranscriptParagraph
import com.example.mp3player.data.model.TranscriptSentence
import com.example.mp3player.data.model.TranscriptWord
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.io.File

/**
 * 流水线第四阶段：添加标点符号和分段（Punctuation + Segmentation）
 *
 * - 智能分句：使用 punct-ct 模型为无标点文本恢复标点，再按句末标点分句
 * - 机械分句：按词间停顿阈值 + 字数上限规则分句（无模型时的兜底方案）
 * - 分段：按累计字数阈值拆分段落
 */
class PunctuationSegmenter {

    companion object {
        /** 句子结束标点 */
        private val endPunctuationSet = setOf('。', '！', '？', '!', '?', '…')
        /** 全部中文标点 */
        private val allPunctuationSet = setOf(
            '，', '。', '！', '？', '；', '：', '、',
            ',', '.', '!', '?', ';', ':', '…',
            '“', '”', '‘', '’'
        )

        /** 句内短停顿：超过该值加逗号（毫秒） */
        private const val COMMA_GAP_MS = 250L
        /** 句末长停顿：超过该值加句号并断句（毫秒） */
        private const val PERIOD_GAP_MS = 800L
        /** 句子内累计多少字强制加逗号（句内停顿兜底） */
        private const val COMMA_CHAR_LIMIT = 50
        /** 单句最大字符数，强制句号断句（长句兜底） */
        private const val SENTENCE_MAX_CHAR = 100
        /** 段落累计最大字符数，遇到句号则分段 */
        private const val PARAGRAPH_MAX_LENGTH = 150

        /** 标点模型单次处理的最大字符数（过长文本分片处理，控制耗时） */
        private const val PUNCT_CHUNK_CHARS = 300

        @Volatile
        private var cachedPunct: OfflinePunctuation? = null
        private val lock = Any()

        fun release() {
            synchronized(lock) {
                runCatching { cachedPunct?.release() }
                cachedPunct = null
            }
        }

        /**
         * 基于句子列表构建段落（纯排版换行，不新增标点）
         */
        fun buildParagraphsFromSentences(sentences: List<TranscriptSentence>): List<TranscriptParagraph> {
            if (sentences.isEmpty()) return emptyList()

            val paragraphs = mutableListOf<TranscriptParagraph>()
            val currentSentences = mutableListOf<TranscriptSentence>()
            var currentLength = 0

            for (sentence in sentences) {
                currentSentences.add(sentence)
                currentLength += sentence.text.length

                // 达到段落字数阈值，触发分段
                if (currentLength >= PARAGRAPH_MAX_LENGTH) {
                    paragraphs.add(TranscriptParagraph(paragraphs.size.toLong(), currentSentences.toList()))
                    currentSentences.clear()
                    currentLength = 0
                }
            }

            // 剩余不足一段的也单独成段
            if (currentSentences.isNotEmpty()) {
                paragraphs.add(TranscriptParagraph(paragraphs.size.toLong(), currentSentences.toList()))
            }

            return paragraphs
        }
    }

    // ========== 标点模型 ==========
    /**
     * 获取或初始化 punct-ct 标点模型单例
     *
     * 模型文件命名兼容两种发布包：
     * - 实际下载的 sherpa-onnx-punct-ct-...-int8 包：model.int8.onnx + tokens.json
     * - 部分旧版包：ct-transformer.onnx
     */
    fun getOrInit(modelDir: File, threads: Int = 2): OfflinePunctuation? = synchronized(lock) {
        if (cachedPunct != null) return cachedPunct
        val modelFile = listOf("ct-transformer.onnx", "model.int8.onnx")
            .map { File(modelDir, it) }
            .firstOrNull { it.exists() }
            ?: return null

        return runCatching {
            val config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = modelFile.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                )
            )
            cachedPunct = OfflinePunctuation(
                assetManager = null,
                config = config
            )
            cachedPunct
        }.getOrNull()
    }

    /**
     * 为无标点文本添加标点（分片处理）
     * @param onLog 处理日志回调
     */
    fun addPunctuation(
        punct: OfflinePunctuation,
        text: String,
        onLog: (String) -> Unit = {}
    ): String {
        if (text.isBlank()) return text
        val sb = StringBuilder(text.length + text.length / 10)
        var start = 0
        var chunkIndex = 0
        var totalAdded = 0
        val totalChunks = (text.length + PUNCT_CHUNK_CHARS - 1) / PUNCT_CHUNK_CHARS
        while (start < text.length) {
            chunkIndex++
            val end = minOf(start + PUNCT_CHUNK_CHARS, text.length)
            val chunk = text.substring(start, end)
            val punctuated = runCatching { punct.addPunctuation(chunk) }.getOrNull()
            val output = punctuated ?: chunk
            val added = output.count { it in allPunctuationSet } - chunk.count { it in allPunctuationSet }
            totalAdded += added
            onLog("智能分句：第${chunkIndex}/${totalChunks}片 ${chunk.length}字→${output.length}字，新增标点 $added 个" +
                if (punctuated == null) "（模型调用失败，保留原文）" else "")
            sb.append(output)
            start = end
        }
        onLog("智能分句：共恢复标点 $totalAdded 个")
        return sb.toString()
    }

    // ========== 分句 ==========
    /**
     * 基于词语列表构建句子。
     * @param useSmartPunctuation 是否使用标点模型智能分句（模型缺失时自动降级为机械分句）
     * @param onLog 处理日志回调，明确输出当前使用的分句方式（punct 智能分句 / 机械分句）
     */
    fun buildSentences(
        words: List<TranscriptWord>,
        totalDurationMs: Long = 0L,
        useSmartPunctuation: Boolean = false,
        punct: OfflinePunctuation? = null,
        onLog: (String) -> Unit = {}
    ): List<TranscriptSentence> {
        if (words.isEmpty()) return emptyList()
        if (useSmartPunctuation) {
            if (punct != null) {
                onLog("【分句方式】punct 智能分句（标点模型恢复标点）")
                return buildSmartSentences(words, punct, onLog)
            }
            onLog("【分句方式】机械分句（智能分句已开启但标点模型未加载）")
            return buildMechanicalSentences(words, totalDurationMs)
        }
        onLog("【分句方式】机械分句（未启用智能分句）")
        return buildMechanicalSentences(words, totalDurationMs)
    }

    /**
     * 智能分句：标点模型恢复标点 → 标点合并回词语 → 按句末标点切句
     */
    private fun buildSmartSentences(
        words: List<TranscriptWord>,
        punct: OfflinePunctuation,
        onLog: (String) -> Unit = {}
    ): List<TranscriptSentence> {
        val cleaned = stripPunctuation(words)
        if (cleaned.isEmpty()) return emptyList()

        val plain = cleaned.joinToString("") { it.word }
        if (plain.isBlank()) return emptyList()

        onLog("智能分句：输入 ${words.size} 词 / ${plain.length} 字，开始恢复标点")
        val punctuated = addPunctuation(punct, plain, onLog)
        val merged = mergePunctuationIntoWords(cleaned, punctuated)
        if (merged.isEmpty()) return emptyList()

        val sentences = mutableListOf<TranscriptSentence>()
        val current = mutableListOf<TranscriptWord>()

        for (w in merged) {
            current.add(w)
            if (w.word.lastOrNull() in endPunctuationSet) {
                sentences.add(
                    TranscriptSentence(
                        id = sentences.size.toLong(),
                        text = current.joinToString("") { it.word },
                        startMs = current.first().startMs,
                        endMs = current.last().endMs,
                        words = current.toList()
                    )
                )
                current.clear()
            }
        }

        // 剩余未闭合句子（模型未给句末标点）强制成句
        if (current.isNotEmpty()) {
            sentences.add(
                TranscriptSentence(
                    id = sentences.size.toLong(),
                    text = current.joinToString("") { it.word },
                    startMs = current.first().startMs,
                    endMs = current.last().endMs,
                    words = current.toList()
                )
            )
        }
        onLog("智能分句：完成，共 ${sentences.size} 句")
        return sentences
    }

    /**
     * 机械分句：词间停顿 + 字数规则（不依赖模型）
     * 断句规则（满足任一即可）：
     * 1. 词语末尾已带句末标点
     * 2. 词间间隔 ≥ 800ms（长停顿）
     * 3. 单句累计字符 ≥ 100（长句兜底）
     * 4. 最后一个词语
     * 句内逗号规则（满足任一即可）：
     * 1. 词间间隔 ≥ 250ms 且 < 800ms
     * 2. 单句累计字符 ≥ 50（句内停顿兜底）
     */
    fun buildMechanicalSentences(
        words: List<TranscriptWord>,
        totalDurationMs: Long
    ): List<TranscriptSentence> {
        if (words.isEmpty()) return emptyList()

        val sentences = mutableListOf<TranscriptSentence>()
        val currentSentenceWords = mutableListOf<TranscriptWord>()
        var currentCharCount = 0

        for (i in words.indices) {
            val word = words[i]
            val isLastWord = i == words.size - 1

            // 先把词加入当前句子
            currentSentenceWords.add(word)
            currentCharCount += word.word.length

            // 判断是否需要加标点、是否断句
            val shouldBreak: Boolean
            val punctuation: String?

            if (word.word.lastOrNull() in endPunctuationSet) {
                // 词语已带句末标点（模型原生输出或断点续传），直接断句，不重复加标点
                shouldBreak = true
                punctuation = null
            } else if (isLastWord) {
                // 最后一个词强制加句号、断句
                shouldBreak = true
                punctuation = "。"
            } else {
                val nextWord = words[i + 1]
                val gap = nextWord.startMs - word.endMs

                when {
                    // 长停顿：句号 + 断句
                    gap >= PERIOD_GAP_MS -> {
                        shouldBreak = true
                        punctuation = "。"
                    }
                    // 短停顿 或 达到逗号字数：加逗号，不断句
                    gap >= COMMA_GAP_MS || currentCharCount >= COMMA_CHAR_LIMIT -> {
                        shouldBreak = false
                        punctuation = "，"
                        currentCharCount = 0 // 加逗号后重置计数
                    }
                    // 达到句子最大长度：强制句号断句
                    currentCharCount >= SENTENCE_MAX_CHAR -> {
                        shouldBreak = true
                        punctuation = "。"
                    }
                    else -> {
                        shouldBreak = false
                        punctuation = null
                    }
                }
            }

            // 给当前词追加标点（避免重复追加）
            if (punctuation != null) {
                val lastIndex = currentSentenceWords.lastIndex
                val lastWord = currentSentenceWords[lastIndex]
                if (!allPunctuationSet.contains(lastWord.word.lastOrNull())) {
                    currentSentenceWords[lastIndex] = lastWord.copy(
                        word = lastWord.word + punctuation
                    )
                }
            }

            // 断句：生成句子对象，开启新句子
            if (shouldBreak && currentSentenceWords.isNotEmpty()) {
                sentences.add(
                    TranscriptSentence(
                        id = sentences.size.toLong(),
                        text = currentSentenceWords.joinToString("") { it.word },
                        startMs = currentSentenceWords.first().startMs,
                        endMs = currentSentenceWords.last().endMs,
                        words = currentSentenceWords.toList()
                    )
                )
                currentSentenceWords.clear()
                currentCharCount = 0
            }
        }

        return sentences
    }

    // ========== 内部工具 ==========
    /**
     * 去掉词语末尾/纯标点，避免智能标点重复叠加
     */
    private fun stripPunctuation(words: List<TranscriptWord>): List<TranscriptWord> {
        val result = mutableListOf<TranscriptWord>()
        for (w in words) {
            var text = w.word
            while (text.isNotEmpty() && text.last() in allPunctuationSet) {
                text = text.dropLast(1)
            }
            if (text.isBlank()) continue
            result.add(w.copy(word = text))
        }
        return result
    }

    /**
     * 将标点模型输出的标点合并回词语时间戳
     */
    private fun mergePunctuationIntoWords(
        words: List<TranscriptWord>,
        punctuated: String
    ): List<TranscriptWord> {
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<TranscriptWord>()
        val pending = StringBuilder()
        var p = 0
        val len = punctuated.length

        fun attachPending() {
            if (pending.isEmpty() || result.isEmpty()) {
                pending.clear()
                return
            }
            val last = result.last()
            result[result.size - 1] = last.copy(word = last.word + pending.toString())
            pending.clear()
        }

        for (word in words) {
            if (word.word.isEmpty()) continue

            // 1. 消费当前词之前的标点 → 附加到上一个词末尾
            while (p < len && punctuated[p] != word.word[0]) {
                pending.append(punctuated[p])
                p++
            }
            attachPending()

            // 2. 匹配当前词字符
            var idx = 0
            while (idx < word.word.length && p < len) {
                if (punctuated[p] == word.word[idx]) {
                    p++
                    idx++
                } else {
                    p++
                }
            }
            result.add(word)
        }

        // 3. 剩余尾部标点附加到最后一个词
        while (p < len) {
            pending.append(punctuated[p])
            p++
        }
        attachPending()

        return result
    }
}
