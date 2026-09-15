package com.example.audiocut.asr

import com.example.audiocut.data.model.TranscriptWord
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File

/**
 * 流水线第三阶段：文字识别（SenseVoice 离线 ASR）
 * 负责将 PCM 送入 SenseVoice 模型识别，并将 token/时间戳对齐为逐字对象。
 * 识别器为全局单例，避免重复加载。
 */
class SenseVoiceRecognizer {

    companion object {
        /** 全部中文标点 */
        private val allPunctuationSet = setOf(
            '，', '。', '！', '？', '；', '：', '、',
            ',', '.', '!', '?', ';', ':', '…',
            '“', '”', '‘', '’'
        )

        @Volatile
        private var cachedRecognizer: OfflineRecognizer? = null
        private val lock = Any()

        fun release() {
            synchronized(lock) {
                runCatching { cachedRecognizer?.release() }
                cachedRecognizer = null
            }
        }

        private fun isSpecialTag(token: String): Boolean {
            val t = token.trim()
            return t.startsWith("<") || t.startsWith("[") || t.startsWith("#")
        }

        private fun isPunctuationToken(token: String): Boolean {
            val t = token.trim()
            return t.isNotBlank() && t.all { it in allPunctuationSet } && !isSpecialTag(t)
        }

        /**
         * 纯文本切词（兼容旧接口/测试）：按空白切分，中文按单字拆分，非中文保持词
         */
        fun parseTextToWords(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val result = mutableListOf<String>()
            for (token in text.split(Regex("\\s+"))) {
                if (token.isEmpty()) continue
                if (token.any { it.code in 0x4E00..0x9FFF }) {
                    // 中文（含中文标点）：按字符拆分；连续的非中文字符成组
                    val group = StringBuilder()
                    fun flush() {
                        if (group.isNotEmpty()) {
                            result.add(group.toString())
                            group.clear()
                        }
                    }
                    for (c in token) {
                        if (c.code in 0x4E00..0x9FFF || c in allPunctuationSet) {
                            flush()
                            result.add(c.toString())
                        } else {
                            group.append(c)
                        }
                    }
                    flush()
                } else {
                    result.add(token)
                }
            }
            return result
        }

        /**
         * 处理 SenseVoice 识别结果，将 token 与时间戳对齐。
         * 模型输出的标点 token 会合并到前一个词语末尾，不单独成词。
         */
        fun processSenseVoiceResult(
            text: String,
            tokens: Array<String>,
            timestamps: FloatArray,
            durations: FloatArray,
            segmentBaseTimeMs: Long = 0L,
            sliceStartMs: Long = 0L,
            sliceEndMs: Long = Long.MAX_VALUE,
            totalDurationMs: Long = Long.MAX_VALUE
        ): List<TranscriptWord>? {
            val words = mutableListOf<TranscriptWord>()
            var wordId = 0L

            // 优先使用 token + 时间戳精确对齐
            if (tokens.isNotEmpty() && timestamps.isNotEmpty() && tokens.size == timestamps.size) {
                for (i in tokens.indices) {
                    var tok = tokens[i]
                        .replace("@@", "")
                        .replace("\u2581", " ")
                        .replace("\u2585", " ")

                    // 中文 token 内去除多余空格
                    if (tok.any { it.code in 0x4E00..0x9FFF }) {
                        tok = tok.replace(" ", "")
                    }

                    if (tok.isBlank() || isSpecialTag(tok)) continue

                    val start = (segmentBaseTimeMs + (timestamps[i] * 1000L).toLong())
                        .coerceIn(0L, totalDurationMs)
                    val dur = if (durations.getOrNull(i) ?: 0f > 0.01f) durations[i] else 0.2f
                    val end = minOf(start + (dur * 1000L).toLong(), maxOf(totalDurationMs, start + 50L))


                    // 过滤超出当前切片范围的内容
                    if (sliceStartMs > 0) {
                        if (end <= sliceStartMs) {
                            continue
                        }
                        // 词跨 sliceStart 左边界时丢弃：该词在上一块已按 sliceEnd 边界保留，
                        // 若在此裁剪保留会造成同一词在相邻两块重复出现
                        if (start < sliceStartMs) {
                            continue
                        }
                    }
                    if (sliceEndMs < totalDurationMs && start >= sliceEndMs) {
                        continue
                    }

                    // 兼容模型输出的标点：合并到上一个词语末尾
                    if (isPunctuationToken(tok)) {
                        words.lastOrNull()?.let { last ->
                            words[words.size - 1] = last.copy(
                                word = last.word + tok,
                                endMs = maxOf(last.endMs, end)
                            )
                        }
                        continue
                    }

                    words.add(TranscriptWord(wordId++, tok, start, end))
                }
            }

            // Fallback：无 token 时从纯文本均匀分配时间戳
            if (words.isEmpty() && text.isNotBlank()) {
                val filteredText = text.filter { !isSpecialTag(it.toString()) }
                val chars = filteredText.toList()
                    .filter { it.code in 0x4E00..0x9FFF || it in allPunctuationSet }
                if (chars.isEmpty()) return null

                val effectiveDuration =
                    (if (sliceEndMs == Long.MAX_VALUE) totalDurationMs else sliceEndMs) - sliceStartMs
                val avgDuration = (effectiveDuration / maxOf(1, chars.size)).coerceIn(100L, 400L)

                chars.forEachIndexed { index, c ->
                    val start = (segmentBaseTimeMs + index * avgDuration).coerceIn(0L, totalDurationMs)
                    val end = (start + avgDuration).coerceAtMost(totalDurationMs)
                    if (!(sliceStartMs > 0 && end <= sliceStartMs)) {
                        words.add(TranscriptWord(wordId++, c.toString(), start, end))
                    }
                }
            }

            return words.ifEmpty { null }
        }
    }

    /**
     * 获取或初始化识别器单例
     */
    fun getOrInit(modelDir: File, threads: Int = 2): OfflineRecognizer? = synchronized(lock) {
        if (cachedRecognizer != null) return cachedRecognizer
        val modelFile = File(modelDir, "model.int8.onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        if (!modelFile.exists() || !tokensFile.exists()) return null

        return runCatching {
            val senseVoiceConfig = OfflineSenseVoiceModelConfig(
                model = modelFile.absolutePath,
                language = "zh",
                useInverseTextNormalization = true
            )
            cachedRecognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = AudioChunker.SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = senseVoiceConfig,
                        tokens = tokensFile.absolutePath,
                        numThreads = threads,
                        debug = false
                    )
                )
            )
            cachedRecognizer
        }.getOrNull()
    }

    /**
     * 执行单段语音识别
     */
    fun recognize(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        baseTimeMs: Long,
        sliceStartMs: Long,
        sliceEndMs: Long,
        totalDurationMs: Long
    ): List<TranscriptWord>? {
        var stream: OfflineStream? = null
        return runCatching {
            stream = recognizer.createStream()
            stream?.acceptWaveform(samples, AudioChunker.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            android.util.Log.d("ASR_DEBUG", "原始文本: ${result.text}")
            android.util.Log.d("ASR_DEBUG", "tokens: ${result.tokens.joinToString("|")}")
            processSenseVoiceResult(
                text = result.text,
                tokens = result.tokens,
                timestamps = result.timestamps,
                durations = result.durations,
                segmentBaseTimeMs = baseTimeMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            )
        }.getOrNull().also {
            runCatching { stream?.release() }
        }
    }
}
