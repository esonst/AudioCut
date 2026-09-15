package com.example.audiocut.asr

import android.content.Context
import com.example.audiocut.data.model.*
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线文稿转写引擎（流水线编排器）
 *
 * 处理流程：分块 -> VAD -> 文字识别 -> 机械分段 -> 返回结果
 * - 分块：AudioChunker（FFmpeg 解码为 16kHz 单声道 PCM，带前后冗余）
 * - VAD：VadSegmenter（切分人声片段，可关闭）
 * - 文字识别：SenseVoiceRecognizer（SenseVoice 离线模型，输出逐字时间戳）
 * - 机械分段：PunctuationSegmenter（按词间停顿 + 字数上限规则分句，识别期统一机械分段；
 *   punct-ct 标点模型仅在文稿页【排版优化】中用于删除并重新添加标点）
 *
 * 模型文件存放于 filesDir/models 下，由 [ModelManager] 统一管理（下载/导入/检测）。
 */
class OfflineAsrEngine(private val context: Context) {

    private val modelManager = ModelManager(context)
    private val chunker = AudioChunker(context)
    private val vadSegmenter = VadSegmenter()
    private val recognizerHolder = SenseVoiceRecognizer()
    private val punctSegmenter = PunctuationSegmenter()

    companion object {
        /**
         * 显式释放 ASR 占用的重度内存资源（识别模型、VAD、标点模型）
         */
        fun releaseResources() {
            VadSegmenter.release()
            SenseVoiceRecognizer.release()
            PunctuationSegmenter.release()
        }

        // ========== 兼容旧接口（供缓存恢复与单元测试使用） ==========
        fun parseTextToWords(text: String): List<String> =
            SenseVoiceRecognizer.parseTextToWords(text)

        fun processSenseVoiceResult(
            text: String,
            tokens: Array<String>,
            timestamps: FloatArray,
            durations: FloatArray,
            segmentBaseTimeMs: Long = 0L,
            sliceStartMs: Long = 0L,
            sliceEndMs: Long = Long.MAX_VALUE,
            totalDurationMs: Long = Long.MAX_VALUE
        ): List<TranscriptWord>? =
            SenseVoiceRecognizer.processSenseVoiceResult(
                text = text,
                tokens = tokens,
                timestamps = timestamps,
                durations = durations,
                segmentBaseTimeMs = segmentBaseTimeMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            )

        fun buildSentencesFromWords(
            words: List<TranscriptWord>,
            totalDurationMs: Long = 0L
        ): List<TranscriptSentence> =
            PunctuationSegmenter().buildMechanicalSentences(words, totalDurationMs)

        fun buildParagraphsFromSentences(sentences: List<TranscriptSentence>): List<TranscriptParagraph> =
            PunctuationSegmenter.buildParagraphsFromSentences(sentences)
    }

    // ========== 公开方法 ==========
    /**
     * 主入口：识别音频文件
     *
     * @param audio 目标音频
     * @param startOffsetMs 起始偏移（断点续传）
     * @param existingWords 已有词语（断点续传）
     * @param config 流水线配置（分块 / VAD / 智能分句等）
     * @param onPartialResult 分片结果回调
     * @param onProgress 进度回调 (0f ~ 1f)
     * @param onLog 处理日志回调（分块/VAD/识别/智能分句各阶段）
     */
    suspend fun transcribeAudio(
        audio: AudioItem,
        startOffsetMs: Long = 0L,
        existingWords: List<TranscriptWord> = emptyList(),
        config: AsrConfig = AsrConfig(),
        startMs: Long = 0L,
        endMs: Long = 0L,
        onPartialResult: (suspend (TranscriptResult) -> Unit)? = null,
        onProgress: suspend (Float) -> Unit,
        onLog: (String) -> Unit = {}
    ): TranscriptResult = withContext(Dispatchers.Default) {
        onProgress(0.05f)

        // 1. 准备 VAD 模型
        val vadFile = if (config.useVad) modelManager.ensureVadModel() else null
        onProgress(0.10f)
        onLog(if (vadFile != null) "VAD：模型就绪，启用语音活动检测" else "VAD：未启用，整段识别")

        // 获取音频总时长
        var totalDurationMs = audio.durationMs
        if (totalDurationMs <= 0) {
            totalDurationMs = chunker.extractAudioDuration(audio)
        }
        if (totalDurationMs <= 0) totalDurationMs = 3600_000L // 保底1小时

        // 识别范围（绝对毫秒）：默认整段 [0, totalDurationMs]；startMs=0 表示音频开头，endMs=0 表示音频结尾
        val effectiveStartMs = maxOf(startMs.coerceAtLeast(0L), startOffsetMs.coerceAtLeast(0L))
            .coerceAtMost(totalDurationMs)
        val effectiveEndMs = if (endMs > 0L) endMs.coerceIn(effectiveStartMs, totalDurationMs) else totalDurationMs
        val rangeSpanMs = effectiveEndMs - effectiveStartMs
        if (rangeSpanMs <= 0L) {
            onLog("范围：无效区间 [${effectiveStartMs}ms, ${effectiveEndMs}ms]，跳过识别")
            return@withContext TranscriptResult(
                audioId = audio.id,
                fullText = "",
                words = emptyList(),
                sentences = emptyList(),
                paragraphs = emptyList(),
                durationMs = totalDurationMs,
                isCompleted = true
            )
        }

        val vad = if (vadFile != null) {
            vadSegmenter.getOrInit(
                vadFile = vadFile,
                threshold = config.vadThreshold,
                minSilenceDuration = config.vadMinSilence,
                minSpeechDuration = config.vadMinSpeech,
                maxSpeechDuration = config.vadMaxSpeech
            )
        } else null

        val recognizer = recognizerHolder.getOrInit(modelManager.senseVoiceDir, config.asrThreads)
        if (recognizer == null) {
            val message = if (!modelManager.isSenseVoiceReady()) {
                "未安装识别模型，请到设置开启文稿转写并下载或导入模型"
            } else {
                "模型初始化失败"
            }
            onLog("识别：$message")
            return@withContext TranscriptResult(
                audioId = audio.id,
                fullText = message,
                words = emptyList(),
                sentences = emptyList(),
                paragraphs = emptyList(),
                durationMs = totalDurationMs,
                isCompleted = true
            )
        }
        onLog("识别：SenseVoice 模型已加载（${config.asrThreads} 线程）")

        // 识别期统一采用机械分段（按停顿 + 字数规则分句）；
        // punct-ct 标点模型仅在文稿页【排版优化】时调用（删除标点后重新添加）
        onLog("分句方式：机械分句（识别阶段统一机械分段；标点精排请使用文稿页【排版优化】）")

        // 2. 分块 -> 3. VAD -> 4. 文字识别（循环处理各分块）
        val accumulatedWords = existingWords.toMutableList()
        val chunkTargetMs = if (config.useSlicing) config.chunkTargetMs else maxOf(1L, rangeSpanMs)
        val ranges = chunker.buildChunkRanges(
            totalMs = effectiveEndMs,
            startOffsetMs = effectiveStartMs,
            chunkTargetMs = chunkTargetMs
        )
        onLog("范围：识别 [${effectiveStartMs / 1000}s, ${effectiveEndMs / 1000}s]（共 ${rangeSpanMs / 1000}s）")
        onLog("分块：共 ${ranges.size} 个分块，每块 ${chunkTargetMs / 1000} 秒")

        for ((index, range) in ranges.withIndex()) {
            val actualStartMs = range[0]
            val actualEndMs = range[1]
            val sliceStartMs = range[2]
            val sliceEndMs = range[3]

            val samples = chunker.decodePcm(audio, actualStartMs, actualEndMs)

            // 解码失败说明已到文件末尾
            if (samples.isEmpty()) {
                totalDurationMs = sliceStartMs
                onLog("分块 ${index + 1}/${ranges.size}：解码到文件末尾，停止")
                break
            }
            onLog("分块 ${index + 1}/${ranges.size}：解码 ${(actualEndMs - actualStartMs) / 1000}s → ${samples.size / 16000}s PCM")

            val chunkWords = transcribeChunk(
                vad = vad,
                recognizer = recognizer,
                samples = samples,
                actualStartMs = actualStartMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            )
            onLog("分块 ${index + 1}/${ranges.size}：识别 ${chunkWords.size} 词（累计 ${accumulatedWords.size + chunkWords.size} 词）")

            if (chunkWords.isNotEmpty()) {
                val maxId = accumulatedWords.maxOfOrNull { it.id } ?: -1L
                var nextId = maxId + 1L
                accumulatedWords.addAll(chunkWords.map { it.copy(id = nextId++) })
            }

            val processedMs = sliceEndMs
            // 进度按识别范围跨度计算，保证自定义范围时进度条从 0 开始
            val progress = ((processedMs - effectiveStartMs).toFloat() / rangeSpanMs).coerceIn(0.1f, 0.95f)
            onProgress(progress)

            // 5. 增量返回中间识别结果（机械分段）
            if (onPartialResult != null) {
                onPartialResult(
                    buildResult(
                        audioId = audio.id,
                        words = accumulatedWords.toList(),
                        totalDurationMs = totalDurationMs,
                        isCompleted = processedMs >= totalDurationMs,
                        processedMs = processedMs,
                        onLog = onLog
                    )
                )
            }
        }

        onProgress(1.0f)

        // 5. 机械分段 -> 返回结果
        val result = buildResult(
            audioId = audio.id,
            words = accumulatedWords.toList(),
            totalDurationMs = totalDurationMs,
            isCompleted = true,
            processedMs = totalDurationMs,
            onLog = onLog
        )
        onLog("完成：共 ${result.words.size} 词 / ${result.sentences.size} 句 / ${result.paragraphs.size} 段")
        result
    }

    // ========== 内部方法 ==========
    /**
     * 对单个分块执行 VAD 分段 + 文字识别。
     * VAD 关闭或 VAD 异常时自动降级为整段识别。
     */
    private fun transcribeChunk(
        vad: Vad?,
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        actualStartMs: Long,
        sliceStartMs: Long,
        sliceEndMs: Long,
        totalDurationMs: Long
    ): List<TranscriptWord> {
        if (vad == null) {
            return recognizerHolder.recognize(
                recognizer = recognizer,
                samples = samples,
                baseTimeMs = actualStartMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            ) ?: emptyList()
        }

        val segments = vadSegmenter.segment(vad, samples, actualStartMs)
        if (segments.isEmpty() && samples.isNotEmpty()) {
            // VAD 未切出片段（可能异常或全是静音）：整段降级识别
            return recognizerHolder.recognize(
                recognizer = recognizer,
                samples = samples,
                baseTimeMs = actualStartMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            ) ?: emptyList()
        }

        val words = mutableListOf<TranscriptWord>()
        for (segment in segments) {
            recognizerHolder.recognize(
                recognizer = recognizer,
                samples = segment.samples,
                baseTimeMs = segment.startMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            )?.let { words.addAll(it) }
        }
        return words
    }

    /**
     * 组装识别结果：机械分段 -> 句子/段落/全文
     */
    private fun buildResult(
        audioId: Long,
        words: List<TranscriptWord>,
        totalDurationMs: Long,
        isCompleted: Boolean,
        processedMs: Long,
        onLog: (String) -> Unit = {}
    ): TranscriptResult {
        val sentences = punctSegmenter.buildSentences(
            words = words,
            totalDurationMs = totalDurationMs,
            useSmartPunctuation = false,
            punct = null,
            onLog = onLog
        )
        val paragraphs = PunctuationSegmenter.buildParagraphsFromSentences(sentences)
        onLog("分句分段：${sentences.size} 句 / ${paragraphs.size} 段")
        val fullText = paragraphs.joinToString("\n\n") { p ->
            p.sentences.joinToString("") { it.text }
        }
        return TranscriptResult(
            audioId = audioId,
            fullText = fullText,
            words = sentences.flatMap { it.words },
            sentences = sentences,
            paragraphs = paragraphs,
            durationMs = totalDurationMs,
            isCompleted = isCompleted,
            processedDurationMs = processedMs
        )
    }
}
