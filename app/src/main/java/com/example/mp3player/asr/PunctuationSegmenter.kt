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
     * 【修复】不再使用字符指针匹配错乱方案，时间戳完全沿用原始words
     */
    private fun buildSmartSentences(
        words: List<TranscriptWord>,
        punct: OfflinePunctuation,
        onLog: (String) -> Unit = {}
    ): List<TranscriptSentence> {
        val cleaned = stripPunctuation(words)
        if (cleaned.isEmpty()) return emptyList()

        val plain = cleaned.joinToString("") { it.word }.replace(Regex("\\s+"), "")
        if (plain.isBlank()) return emptyList()

        onLog("智能分句：输入 ${words.size} 词 / ${plain.length} 字，开始恢复标点")
        val punctuated = addPunctuation(punct, plain, onLog)
        val merged = mergePunctuationToWordsFixed(cleaned, punctuated)
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
     * 【修复版】把模型输出带标点文本中的标点，追加到对应词语末尾。
     * 不改动任何 word 的 startMs / endMs，只修改 word 文本。
     *
     * 原理：遍历带标点文本，非标点字符计数；遇到标点时，
     * 它属于"前面最近一个非标点字符"所在的词语。
     * 不做严格字符匹配，模型改字也不会错位。
     */
    private fun mergePunctuationToWordsFixed(
        sourceWords: List<TranscriptWord>,
        punctuatedFullText: String
    ): List<TranscriptWord> {
        if (sourceWords.isEmpty()) return emptyList()

        // 1. 构建"纯文本第N个字符 → 属于第几个词"的映射
        val charToWordIndex = mutableListOf<Int>()
        for (wordIdx in sourceWords.indices) {
            val wordText = sourceWords[wordIdx].word
            for (ch in wordText) {
                if (ch.isWhitespace()) continue // 空格不占用映射位置
                charToWordIndex.add(wordIdx)
            }
        }
        val totalPlainChars = charToWordIndex.size

        // 2. 每个词待追加的标点后缀
        val punctSuffix = Array(sourceWords.size) { StringBuilder() }

        // 3. 遍历模型输出：非标点字符计数，标点归到前面的词
        var consumedPlainChars = 0
        for (c in punctuatedFullText) {
            if (c in allPunctuationSet) {
                // 标点属于它前面最近的非标点字符所在的词
                val targetIdx = if (consumedPlainChars == 0) {
                    0 // 文本最开头的标点，挂到第一个词
                } else {
                    charToWordIndex[(consumedPlainChars - 1).coerceAtMost(totalPlainChars - 1)]
                }
                punctSuffix[targetIdx].append(c)
            } else {
                consumedPlainChars++
            }
        }

        // 4. 把标点后缀拼到对应词语，时间戳完全沿用原值
        var result = sourceWords.mapIndexed { idx, word ->
            if (punctSuffix[idx].isNotEmpty()) {
                word.copy(word = word.word + punctSuffix[idx].toString())
            } else {
                word
            }

        }
        return result
    }

}
