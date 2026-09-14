package com.example.mp3player.asr

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.mp3player.data.model.*
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder



/**
 * 本地离线ASR语音识别引擎（SenseVoice中文模型 + FFmpeg解码）
 *
 * 核心特性：
 * - SenseVoice中文模型原生自带标点，不再手动生成标点
 * - 分句策略：模型结束标点优先 + 词语时间间隔兜底
 * - 音频解码统一使用FFmpegKit，兼容全格式
 * - 模型与VAD全局单例，避免重复加载
 * - 分块识别 + VAD语音分段 + 增量结果回调
 */
class OfflineAsrEngine(private val context: Context) {

    private val modelDirName = "model_offline"

    companion object {
        // ========== 配置常量 ==========
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
        /** 分片识别默认块大小（毫秒） */
        private const val DEFAULT_CHUNK_MS = 30_000L
        /** 分片前后冗余时长，避免截断词语（毫秒） */
        private const val CHUNK_REDUNDANCY_MS = 2_000L

        // ========== 标点集合（仅识别模型原生输出，不主动生成） ==========
        /** 句子结束标点 */
        private val endPunctuationSet = setOf('。', '！', '？', '!', '?', '…')
        /** 全部中文标点 */
        private val allPunctuationSet = setOf(
            '，', '。', '！', '？', '；', '：', '、',
            ',', '.', '!', '?', ';', ':', '…',
            '“', '”', '‘', '’'
        )

        // ========== 单例缓存 ==========
        @Volatile
        private var cachedRecognizer: OfflineRecognizer? = null
        @Volatile
        private var cachedVad: Vad? = null
        private val lock = Any()

        /**
         * 显式释放ASR占用的重度内存资源（模型与VAD）
         */
        fun releaseResources() {
            synchronized(lock) {
                runCatching { cachedVad?.release() }
                cachedVad = null
                runCatching { cachedRecognizer?.release() }
                cachedRecognizer = null
            }
        }

        // ========== 工具函数 ==========
        private fun isSpecialTag(token: String): Boolean {
            val t = token.trim()
            return t.startsWith("<") || t.startsWith("[") || t.startsWith("#")
        }

        private fun isPunctuationToken(token: String): Boolean {
            val t = token.trim()
            return t.isNotBlank() && t.all { it in allPunctuationSet } && !isSpecialTag(t)
        }

        /**
         * 处理SenseVoice识别结果，将token与时间戳对齐
         * 标点token会合并到前一个词语末尾，不单独成词
         */
        /**
         * 处理SenseVoice识别结果，将token与时间戳对齐
         * 模型输出的标点token会合并到前一个词语末尾，不单独成词
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

            // 优先使用token+时间戳精确对齐
            if (tokens.isNotEmpty() && timestamps.isNotEmpty() && tokens.size == timestamps.size) {
                for (i in tokens.indices) {
                    var tok = tokens[i]
                        .replace("@@", "")
                        .replace("\u2581", " ")
                        .replace("\u2585", " ")

                    // 中文token内去除多余空格
                    if (tok.any { it.code in 0x4E00..0x9FFF }) {
                        tok = tok.replace(" ", "")
                    }

                    if (tok.isBlank() || isSpecialTag(tok)) continue

                    var start = (segmentBaseTimeMs + (timestamps[i] * 1000L).toLong())
                        .coerceIn(0L, totalDurationMs)
                    val dur = if (durations.getOrNull(i) ?: 0f > 0.01f) durations[i] else 0.2f
                    val end = (start + (dur * 1000L).toLong())
                        .coerceIn(start + 50L, totalDurationMs)

                    // 过滤超出当前切片范围的内容
                    if (sliceStartMs > 0) {
                        if (end <= sliceStartMs) {
                            // 完全落在前置冗余区，直接丢弃
                            continue
                        }
                        // 词语跨sliceStart边界，把start裁剪到sliceStartMs，消除冗余区时间
                        if (start < sliceStartMs) {
                            start = sliceStartMs
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

            // Fallback：无token时从纯文本均匀分配时间戳
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


        /**
         * 基于词语列表构建句子
         * 断句规则（满足任一即可）：
         * 1. 词语末尾包含模型原生结束标点
         * 2. 相邻词语音间隔超过阈值（兜底）
         * 全程不主动新增任何标点
         */
        /**
         * 基于词语列表构建句子（纯规则自动加标点）
         * 断句规则（满足任一即可）：
         * 1. 词间间隔 ≥ 800ms（长停顿）
         * 2. 单句累计字符 ≥ 45（长句兜底）
         * 3. 最后一个词语
         * 句内逗号规则（满足任一即可）：
         * 1. 词间间隔 ≥ 250ms 且 < 800ms
         * 2. 单句累计字符 ≥ 18（句内停顿兜底）
         */
        fun buildSentencesFromWords(
            words: List<TranscriptWord>,
            totalDurationMs: Long = 0L
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

                if (isLastWord) {
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


        /**
         * 基于句子列表构建段落（纯排版换行，不新增标点）
         */
        /**
         * 基于句子列表构建段落（纯排版换行）
         * 累计字符达到阈值且遇到句末标点时，拆分新段落
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

    // ========== 公开方法 ==========
    /**
     * 准备模型文件：从Assets拷贝到内部存储，带版本校验
     */
    suspend fun prepareModel(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val targetDir = File(context.filesDir, modelDirName)
            if (!targetDir.exists()) targetDir.mkdirs()

            val versionFile = File(targetDir, "model_version.txt")
            val currentVersion = "sense-voice-vad-v1.1"
            val versionMatch = versionFile.exists() && versionFile.readText().trim() == currentVersion

            val requiredFiles = listOf("model.int8.onnx", "tokens.txt", "silero_vad.int8.onnx")
            val assetManager = context.assets

            for (fileName in requiredFiles) {
                val outFile = File(targetDir, fileName)
                var needsCopy = !versionMatch || !outFile.exists() || outFile.length() == 0L

                // 文件完整性校验
                if (!needsCopy) {
                    needsCopy = when (fileName) {
                        "model.int8.onnx" -> outFile.length() < 150_000_000L
                        "tokens.txt" -> outFile.length() < 200_000L
                        else -> false
                    }
                }

                if (needsCopy) {
                    val tempFile = File(targetDir, "$fileName.tmp")
                    assetManager.open("$modelDirName/$fileName").use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        if (outFile.exists()) outFile.delete()
                        tempFile.renameTo(outFile)
                    }
                }
            }

            versionFile.writeText(currentVersion)
            true
        }.getOrDefault(false)
    }

    /**
     * 主入口：识别音频文件
     */
    suspend fun transcribeAudio(
        audio: AudioItem,
        startOffsetMs: Long = 0L,
        existingWords: List<TranscriptWord> = emptyList(),
        useVad: Boolean = true,
        chunkTargetMs: Long = DEFAULT_CHUNK_MS,
        onPartialResult: (suspend (TranscriptResult) -> Unit)? = null,
        onProgress: suspend (Float) -> Unit
    ): TranscriptResult = withContext(Dispatchers.Default) {
        onProgress(0.05f)
        prepareModel()
        onProgress(0.10f)

        // 获取音频总时长
        var totalDurationMs = audio.durationMs
        if (totalDurationMs <= 0) {
            totalDurationMs = extractAudioDuration(audio)
        }
        if (totalDurationMs <= 0) totalDurationMs = 3600_000L // 保底1小时

        val accumulatedWords = existingWords.toMutableList()
        var currentOffsetMs = startOffsetMs.coerceIn(0L, totalDurationMs)

        val vad = if (useVad) getOrInitVad() else null
        val recognizer = getOrInitRecognizer()
            ?: return@withContext TranscriptResult(
                audioId = audio.id,
                fullText = "模型初始化失败",
                words = emptyList(),
                sentences = emptyList(),
                paragraphs = emptyList(),
                durationMs = totalDurationMs,
                isCompleted = true
            )

        while (currentOffsetMs < totalDurationMs) {
            val sliceStartMs = currentOffsetMs
            val sliceEndMs = minOf(totalDurationMs, sliceStartMs + chunkTargetMs)

            // 前后冗余，避免截断词语
            val actualStartMs = maxOf(0L, sliceStartMs - CHUNK_REDUNDANCY_MS)
            val actualEndMs = minOf(totalDurationMs, sliceEndMs + 500L)

            if (actualEndMs <= actualStartMs) break

            val samples = decodeAudioTimeRangeTo16kMonoPCM(audio, actualStartMs, actualEndMs)

            // 解码失败说明已到文件末尾
            if (samples.isEmpty()) {
                totalDurationMs = currentOffsetMs
                break
            }

            val chunkWords = transcribeChunkWithVad(
                vad = vad,
                recognizer = recognizer,
                samples = samples,
                actualStartMs = actualStartMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            )

            if (chunkWords.isNotEmpty()) {
                val maxId = accumulatedWords.maxOfOrNull { it.id } ?: -1L
                var nextId = maxId + 1L
                accumulatedWords.addAll(chunkWords.map { it.copy(id = nextId++) })
            }

            currentOffsetMs = sliceEndMs
            val progress = (currentOffsetMs.toFloat() / totalDurationMs).coerceIn(0.1f, 0.95f)
            onProgress(progress)

            // 增量返回中间识别结果
            if (onPartialResult != null) {
                val sentences = buildSentencesFromWords(accumulatedWords, totalDurationMs)
                val paragraphs = buildParagraphsFromSentences(sentences)
                val fullText = paragraphs.joinToString("\n\n") { p ->
                    p.sentences.joinToString("") { it.text }
                }
                onPartialResult(
                    TranscriptResult(
                        audioId = audio.id,
                        fullText = fullText,
                        words = sentences.flatMap { it.words },
                        sentences = sentences,
                        paragraphs = paragraphs,
                        durationMs = totalDurationMs,
                        isCompleted = currentOffsetMs >= totalDurationMs,
                        processedDurationMs = currentOffsetMs
                    )
                )
            }
        }

        onProgress(1.0f)

        val finalSentences = buildSentencesFromWords(accumulatedWords, totalDurationMs)
        val finalParagraphs = buildParagraphsFromSentences(finalSentences)
        val finalFullText = finalParagraphs.joinToString("\n\n") { p ->
            p.sentences.joinToString("") { it.text }
        }

        TranscriptResult(
            audioId = audio.id,
            fullText = finalFullText,
            words = finalSentences.flatMap { it.words },
            sentences = finalSentences,
            paragraphs = finalParagraphs,
            durationMs = totalDurationMs,
            isCompleted = true,
            processedDurationMs = totalDurationMs
        )
    }

    // ========== 内部方法 ==========
    /**
     * 获取或初始化VAD单例
     */
    private fun getOrInitVad(): Vad? = synchronized(lock) {
        if (cachedVad != null) return cachedVad
        val vadFile = File(context.filesDir, "$modelDirName/silero_vad.int8.onnx")
        if (!vadFile.exists()) return null

        return runCatching {
            val config = SileroVadModelConfig(
                model = vadFile.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30.0f
            )
            cachedVad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = config,
                    sampleRate = 16000,
                    numThreads = 1,
                    debug = false
                )
            )
            cachedVad
        }.getOrNull()
    }

    /**
     * 获取或初始化识别器单例
     */
    private fun getOrInitRecognizer(): OfflineRecognizer? = synchronized(lock) {
        if (cachedRecognizer != null) return cachedRecognizer
        val modelFile = File(context.filesDir, "$modelDirName/model.int8.onnx")
        val tokensFile = File(context.filesDir, "$modelDirName/tokens.txt")
        if (!modelFile.exists() || !tokensFile.exists()) return null

        return runCatching {
            val senseVoiceConfig = OfflineSenseVoiceModelConfig(
                model = modelFile.absolutePath,
                language = "zh",
                useInverseTextNormalization = true
            )
            cachedRecognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = senseVoiceConfig,
                        tokens = tokensFile.absolutePath,
                        numThreads = 2,
                        debug = false
                    )
                )
            )
            cachedRecognizer
        }.getOrNull()
    }

    /**
     * 使用VAD对音频分片进行分段识别
     * VAD异常时自动降级为整段识别
     */
    private fun transcribeChunkWithVad(
        vad: Vad?,
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        actualStartMs: Long,
        sliceStartMs: Long,
        sliceEndMs: Long,
        totalDurationMs: Long
    ): List<TranscriptWord> {
        if (vad == null) {
            return runSherpaRecognitionSegment(
                recognizer = recognizer,
                samples = samples,
                baseTimeMs = actualStartMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            ) ?: emptyList()
        }

        val words = mutableListOf<TranscriptWord>()
        runCatching {
            vad.reset()
            var offset = 0
            val windowSize = 512

            while (offset < samples.size) {
                val end = minOf(offset + windowSize, samples.size)
                vad.acceptWaveform(samples.copyOfRange(offset, end))

                while (!vad.empty()) {
                    val segment = vad.front()
                    if (segment.samples.isNotEmpty()) {
                        val segmentStartMs = actualStartMs + (segment.start * 1000L / 16000)
                        runSherpaRecognitionSegment(
                            recognizer = recognizer,
                            samples = segment.samples,
                            baseTimeMs = segmentStartMs,
                            sliceStartMs = sliceStartMs,
                            sliceEndMs = sliceEndMs,
                            totalDurationMs = totalDurationMs
                        )?.let { words.addAll(it) }
                    }
                    vad.pop()
                }
                offset += windowSize
            }

            // 刷新缓冲区剩余数据
            vad.flush()
            while (!vad.empty()) {
                val segment = vad.front()
                if (segment.samples.isNotEmpty()) {
                    val segmentStartMs = actualStartMs + (segment.start * 1000L / 16000)
                    runSherpaRecognitionSegment(
                        recognizer = recognizer,
                        samples = segment.samples,
                        baseTimeMs = segmentStartMs,
                        sliceStartMs = sliceStartMs,
                        sliceEndMs = sliceEndMs,
                        totalDurationMs = totalDurationMs
                    )?.let { words.addAll(it) }
                }
                vad.pop()
            }
        }.getOrElse {
            // VAD失败时降级为整段识别
            return runSherpaRecognitionSegment(
                recognizer = recognizer,
                samples = samples,
                baseTimeMs = actualStartMs,
                sliceStartMs = sliceStartMs,
                sliceEndMs = sliceEndMs,
                totalDurationMs = totalDurationMs
            ) ?: emptyList()
        }

        return words
    }

    /**
     * 执行单段语音识别
     */
    private fun runSherpaRecognitionSegment(
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
            stream?.acceptWaveform(samples, 16000)
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

    /**
     * 使用FFmpeg解码指定时间段的音频，输出16kHz单声道归一化PCM
     */
    private suspend fun decodeAudioTimeRangeTo16kMonoPCM(
        audio: AudioItem,
        startMs: Long,
        endMs: Long
    ): FloatArray = withContext(Dispatchers.IO) {
        val inputFile = getInputFile(audio) ?: return@withContext FloatArray(0)
        if (!inputFile.exists()) return@withContext FloatArray(0)

        val startSec = startMs / 1000.0
        val endSec = endMs / 1000.0
        val outputPcmFile = File(context.cacheDir, "asr_pcm_${System.currentTimeMillis()}.pcm")

        val command = "-i \"${inputFile.absolutePath}\" " +
                "-ss $startSec -to $endSec " +
                "-ar 16000 -ac 1 -f s16le -acodec pcm_s16le \"${outputPcmFile.absolutePath}\""

        return@withContext runCatching {
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                return@runCatching FloatArray(0)
            }

            if (!outputPcmFile.exists() || outputPcmFile.length() == 0L) {
                return@runCatching FloatArray(0)
            }

            val bytes = outputPcmFile.readBytes()
            outputPcmFile.delete() // 清理临时文件

            val shortBuffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()

            val floatArray = FloatArray(shortBuffer.remaining())
            for (i in floatArray.indices) {
                floatArray[i] = (shortBuffer.get().toInt() / 32768.0f).coerceIn(-1f, 1f)
            }

            floatArray
        }.getOrDefault(FloatArray(0)).also {
            runCatching { outputPcmFile.delete() }
        }
    }

    /**
     * 提取音频时长（仅读元数据，轻量高效）
     */
    private fun extractAudioDuration(audio: AudioItem): Long {
        val extractor = MediaExtractor()
        return try {
            if (File(audio.filePath).exists()) {
                extractor.setDataSource(audio.filePath)
            } else if (audio.contentUri != null) {
                extractor.setDataSource(context, audio.contentUri, null)
            } else {
                return 0L
            }

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    return format.getLong(MediaFormat.KEY_DURATION) / 1000L
                }
            }
            0L
        } catch (e: Exception) {
            0L
        } finally {
            extractor.release()
        }
    }

    /**
     * 处理输入音频：Uri类型转存为临时文件
     */
    private suspend fun getInputFile(audio: AudioItem): File? = withContext(Dispatchers.IO) {
        if (File(audio.filePath).exists()) {
            return@withContext File(audio.filePath)
        }

        val uri = audio.contentUri ?: return@withContext null
        val tempFile = File(context.cacheDir, "asr_input_${audio.id}.tmp")

        runCatching {
            if (tempFile.exists()) tempFile.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (tempFile.exists()) tempFile else null
        }.getOrNull()
    }
}
