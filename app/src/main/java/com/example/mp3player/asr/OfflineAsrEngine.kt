package com.example.mp3player.asr

import android.content.Context
import com.example.mp3player.data.model.AudioItem
import com.example.mp3player.data.model.TranscriptParagraph
import com.example.mp3player.data.model.TranscriptResult
import com.example.mp3player.data.model.TranscriptSentence
import com.example.mp3player.data.model.TranscriptWord
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线文稿转写引擎（流水线编排器）
 *
 * 处理流程：分块 -> VAD -> 文字识别 -> 添加标点符号和分段 -> 返回结果
 * - 分块：AudioChunker（FFmpeg 解码为 16kHz 单声道 PCM，带前后冗余）
 * - VAD：VadSegmenter（切分人声片段，可关闭）
 * - 文字识别：SenseVoiceRecognizer（SenseVoice 离线模型，输出逐字时间戳）
 * - 添加标点符号和分段：PunctuationSegmenter
 *   （智能分句使用 punct-ct 模型恢复标点；关闭或模型缺失时按停顿机械分句）
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
        onPartialResult: (suspend (TranscriptResult) -> Unit)? = null,
        onProgress: suspend (Float) -> Unit,
        onLog: (String) -> Unit = {}
    ): TranscriptResult = withContext(Dispatchers.Default) {
        onProgress(0.05f)

        // 1. 准备 VAD 模型（assets 仅保留 silero_vad.int8.onnx）
        val vadFile = if (config.useVad) modelManager.ensureVadModel() else null
        onProgress(0.10f)
        onLog(if (vadFile != null) "VAD：模型就绪，启用语音活动检测" else "VAD：未启用，整段识别")

        // 获取音频总时长
        var totalDurationMs = audio.durationMs
        if (totalDurationMs <= 0) {
            totalDurationMs = chunker.extractAudioDuration(audio)
        }
        if (totalDurationMs <= 0) totalDurationMs = 3600_000L // 保底1小时

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

        val punct = if (config.useSmartPunctuation) {
            punctSegmenter.getOrInit(modelManager.punctDir, config.asrThreads)
        } else null
        if (config.useSmartPunctuation) {
            onLog(
                when {
                    punct != null -> "智能分句：punct-ct 模型已加载（${modelManager.punctDir.name}）"
                    modelManager.isPunctReady() -> "智能分句：模型文件存在但加载失败，将降级为机械分句"
                    else -> "智能分句：模型缺失，将降级为机械分句"
                }
            )
        }

        // 2. 分块 -> 3. VAD -> 4. 文字识别（循环处理各分块）
        val accumulatedWords = existingWords.toMutableList()
        val chunkTargetMs = if (config.useSlicing) config.chunkTargetMs else maxOf(1L, totalDurationMs)
        val ranges = chunker.buildChunkRanges(
            totalMs = totalDurationMs,
            startOffsetMs = startOffsetMs,
            chunkTargetMs = chunkTargetMs
        )
        onLog("分块：共 ${ranges.size} 个分块，每块 ${chunkTargetMs / 1000} 秒" +
            if (startOffsetMs > 0) "，从 ${startOffsetMs / 1000}s 断点续传" else "")

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
            val progress = (processedMs.toFloat() / totalDurationMs).coerceIn(0.1f, 0.95f)
            onProgress(progress)

            // 5. 增量返回中间识别结果（含标点与分段）
            if (onPartialResult != null) {
                onPartialResult(
                    buildResult(
                        audioId = audio.id,
                        words = accumulatedWords.toList(),
                        totalDurationMs = totalDurationMs,
                        useSmartPunctuation = config.useSmartPunctuation,
                        punct = punct,
                        isCompleted = processedMs >= totalDurationMs,
                        processedMs = processedMs,
                        onLog = onLog
                    )
                )
            }
        }

        onProgress(1.0f)

        // 5. 添加标点符号和分段 -> 返回结果
        val result = buildResult(
            audioId = audio.id,
            words = accumulatedWords.toList(),
            totalDurationMs = totalDurationMs,
            useSmartPunctuation = config.useSmartPunctuation,
            punct = punct,
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
     * 组装识别结果：添加标点符号和分段 -> 句子/段落/全文
     */
    private fun buildResult(
        audioId: Long,
        words: List<TranscriptWord>,
        totalDurationMs: Long,
        useSmartPunctuation: Boolean,
        punct: OfflinePunctuation?,
        isCompleted: Boolean,
        processedMs: Long,
        onLog: (String) -> Unit = {}
    ): TranscriptResult {
        val sentences = punctSegmenter.buildSentences(
            words = words,
            totalDurationMs = totalDurationMs,
            useSmartPunctuation = useSmartPunctuation,
            punct = punct,
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
