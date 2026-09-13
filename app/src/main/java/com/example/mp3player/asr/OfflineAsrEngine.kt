package com.example.mp3player.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.mp3player.data.model.*
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * 本地离线 ASR 语音识别引擎 (性能优化版)
 * - 实现模型单例化，避免重复加载 226MB 模型带来的内存波动与延迟
 * - 优化 PCM 解码，使用 ShortArray 替换 ArrayList<Short> 减少装箱开销
 * - 智能分句与段落排版
 */
class OfflineAsrEngine(private val context: Context) {

    private val modelDirName = "model_offline"

    companion object {
        private var cachedRecognizer: OfflineRecognizer? = null
        private var cachedVad: Vad? = null
        private val lock = Any()

        val punctuationSet = setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':', '…', '“', '”', '‘', '’')
        val endPunctuation = setOf("。", "！", "？", "!", "?", "；", ";", "…")
        val clausePunctuation = setOf("，", ",", "、", "：", ":")

        /**
         * 显式释放 ASR 占用的重度内存资源 (模型与 VAD)
         */
        fun releaseResources() {
            synchronized(lock) {
                try {
                    cachedVad?.release()
                    cachedVad = null
                    cachedRecognizer?.release()
                    cachedRecognizer = null
                } catch (e: Exception) {
                }
            }
        }

        fun isPunctuationToken(token: String): Boolean {
            return token.isNotBlank() && token.all { it in punctuationSet }
        }

        fun isSpecialTag(token: String): Boolean {
            val t = token.trim()
            return t.startsWith("<") || t.startsWith("[") || t.startsWith("#")
        }

        fun parseTextToWords(text: String): List<String> {
            val words = mutableListOf<String>()
            val currentWord = StringBuilder()
            fun flush() { if (currentWord.isNotEmpty()) { words.add(currentWord.toString()); currentWord.setLength(0) } }
            for (ch in text) {
                if (ch.isWhitespace()) flush()
                else if (ch in punctuationSet) {
                    if (currentWord.isNotEmpty()) { currentWord.append(ch); flush() }
                    else if (words.isNotEmpty()) { words[words.size - 1] = words.last() + ch }
                } else if (ch.code in 0x4E00..0x9FFF) { flush(); currentWord.append(ch) }
                else currentWord.append(ch)
            }
            flush()
            return words
        }

        fun processSenseVoiceResult(
            text: String, tokens: Array<String>, timestamps: FloatArray, durations: FloatArray,
            segmentBaseTimeMs: Long = 0L, sliceStartMs: Long = 0L, sliceEndMs: Long = Long.MAX_VALUE, totalDurationMs: Long = Long.MAX_VALUE
        ): List<TranscriptWord>? {
            val words = mutableListOf<TranscriptWord>()
            var wordId = 0L

            if (tokens.isNotEmpty() && timestamps.isNotEmpty() && tokens.size == timestamps.size) {
                for (i in tokens.indices) {
                    var tok = tokens[i].replace("@@", "").replace("\u2581", " ").replace("\u2585", " ")
                    if (tok.any { it.code in 0x4E00..0x9FFF }) tok = tok.replace(" ", "")
                    if (tok.isBlank() || isSpecialTag(tok)) continue

                    val start = (segmentBaseTimeMs + (timestamps[i] * 1000L).toLong()).coerceIn(0L, totalDurationMs)
                    val dur = if (durations.getOrNull(i) ?: 0f > 0.01f) durations[i] else 0.2f
                    val end = (start + (dur * 1000L).toLong()).coerceIn(start + 50L, totalDurationMs)

                    if (sliceStartMs > 0 && end <= sliceStartMs) continue
                    if (sliceEndMs < totalDurationMs && start >= sliceEndMs) continue

                    if (isPunctuationToken(tok)) {
                        words.lastOrNull()?.let { last -> 
                            words[words.size - 1] = last.copy(word = last.word + tok, endMs = maxOf(last.endMs, end))
                        }
                        continue
                    }
                    words.add(TranscriptWord(wordId++, tok, start, end))
                }
            }

            if (words.isEmpty() && text.isNotBlank()) {
                val parsed = parseTextToWords(text.split(" ").filter { !isSpecialTag(it) }.joinToString(" "))
                if (parsed.isNotEmpty()) {
                    val effective = (if (sliceEndMs == Long.MAX_VALUE) totalDurationMs else sliceEndMs) - sliceStartMs
                    val avg = (effective / maxOf(1, parsed.size)).coerceIn(100L, 400L)
                    parsed.forEachIndexed { i, s ->
                        val start = (segmentBaseTimeMs + i * avg).coerceIn(0L, totalDurationMs)
                        val end = (start + avg).coerceAtMost(totalDurationMs)
                        if (!(sliceStartMs > 0 && end <= sliceStartMs)) words.add(TranscriptWord(wordId++, s, start, end))
                    }
                }
            }
            return if (words.isNotEmpty()) words else null
        }

        fun buildSentencesFromWords(words: List<TranscriptWord>, totalDurationMs: Long = 0L): List<TranscriptSentence> {
            if (words.isEmpty()) return emptyList()
            val punctuated = mutableListOf<TranscriptWord>()
            var cc = 0
            for (i in words.indices) {
                val w = words[i]
                var txt = w.word.trim()
                val next = words.getOrNull(i + 1)
                val pause = if (next != null) (next.startMs - w.endMs).coerceAtLeast(0L) else 1000L
                cc += txt.length
                if (!endPunctuation.any { txt.endsWith(it) } && !clausePunctuation.any { txt.endsWith(it) }) {
                    if (next == null || pause >= 800L || cc >= 35) { txt = "$txt。"; cc = 0 }
                    else if (pause >= 400L || cc >= 15) { txt = "$txt，"; cc = 0 }
                } else cc = 0
                punctuated.add(w.copy(word = txt))
            }
            val sentences = mutableListOf<TranscriptSentence>()
            var cur = mutableListOf<TranscriptWord>()
            for (i in punctuated.indices) {
                cur.add(punctuated[i])
                if (endPunctuation.any { punctuated[i].word.endsWith(it) } || i == punctuated.size - 1) {
                    sentences.add(TranscriptSentence(sentences.size.toLong(), cur.joinToString("") { it.word }, cur.first().startMs, cur.last().endMs, cur.toList()))
                    cur = mutableListOf()
                }
            }
            return sentences
        }

        fun buildParagraphsFromSentences(sentences: List<TranscriptSentence>): List<TranscriptParagraph> {
            val paragraphs = mutableListOf<TranscriptParagraph>()
            var cur = mutableListOf<TranscriptSentence>()
            var cc = 0
            for (s in sentences) {
                cur.add(s)
                cc += s.text.length
                if (cc >= 50 && endPunctuation.any { s.text.endsWith(it) }) {
                    paragraphs.add(TranscriptParagraph(paragraphs.size.toLong(), cur.toList()))
                    cur = mutableListOf(); cc = 0
                }
            }
            if (cur.isNotEmpty()) paragraphs.add(TranscriptParagraph(paragraphs.size.toLong(), cur.toList()))
            return paragraphs
        }
    }

    suspend fun prepareModel(): Boolean = withContext(Dispatchers.IO) {
        try {
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
                if (!needsCopy) {
                    if (fileName == "model.int8.onnx" && outFile.length() < 150_000_000L) needsCopy = true
                    else if (fileName == "tokens.txt" && outFile.length() < 200_000L) needsCopy = true
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
        } catch (e: Exception) { false }
    }

    suspend fun transcribeAudio(
        audio: AudioItem,
        startOffsetMs: Long = 0L,
        existingWords: List<TranscriptWord> = emptyList(),
        useVad: Boolean = true,
        chunkTargetMs: Long = 30_000L,
        onPartialResult: (suspend (TranscriptResult) -> Unit)? = null,
        onProgress: suspend (Float) -> Unit
    ): TranscriptResult = withContext(Dispatchers.Default) {
        onProgress(0.05f)
        prepareModel()
        onProgress(0.10f)

        // 尝试获取精准时长
        var totalDurationMs = audio.durationMs
        if (totalDurationMs <= 0) {
            val extractor = MediaExtractor()
            try {
                if (File(audio.filePath).exists()) extractor.setDataSource(audio.filePath)
                else if (audio.contentUri != null) extractor.setDataSource(context, audio.contentUri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        totalDurationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        break
                    }
                }
            } catch (e: Exception) {
            } finally {
                extractor.release()
            }
        }
        
        // 如果依然无法获取，保底设为一个较大的值，或者通过解码动态探测
        if (totalDurationMs <= 0) totalDurationMs = 3600_000L // 默认1小时

        val accumulatedWords = existingWords.toMutableList()
        var currentOffsetMs = startOffsetMs.coerceIn(0L, totalDurationMs)
        val redundancyMs = 2_000L
        val vad = if (useVad) getOrInitVad() else null
        val recognizer = getOrInitRecognizer() ?: return@withContext TranscriptResult(audio.id, "模型初始化失败", emptyList(), emptyList(), emptyList(), totalDurationMs, true)


        while (currentOffsetMs < totalDurationMs) {
            val sliceStartMs = currentOffsetMs
            val sliceEndMs = minOf(totalDurationMs, sliceStartMs + chunkTargetMs)
            
            // 确保每次都有一定的冗余，避免截断单词
            val actualStartMs = maxOf(0L, sliceStartMs - redundancyMs)
            val actualEndMs = minOf(totalDurationMs, sliceEndMs + 500L) // 向后也多读一点
            
            if (actualEndMs <= actualStartMs) break
            
            
            val samples = decodeAudioTimeRangeTo16kMonoPCM(audio, actualStartMs, actualEndMs)
            
            // 关键：如果解码不出数据，说明已经到文件末尾了
            if (samples.isEmpty()) {
                totalDurationMs = currentOffsetMs // 修正时长
                break
            }

            val chunkWords = transcribeChunkWithVad(vad, recognizer, samples, actualStartMs, sliceStartMs, sliceEndMs, totalDurationMs)
            if (chunkWords.isNotEmpty()) {
                val maxExistingId = accumulatedWords.maxOfOrNull { it.id } ?: -1L
                var nextId = maxExistingId + 1L
                accumulatedWords.addAll(chunkWords.map { it.copy(id = nextId++) })
            } else {
            }
            
            currentOffsetMs = sliceEndMs
            onProgress((currentOffsetMs.toFloat() / totalDurationMs).coerceIn(0.1f, 0.95f))
            
            val sentences = buildSentencesFromWords(accumulatedWords, totalDurationMs)
            val paragraphs = buildParagraphsFromSentences(sentences)
            val fullText = paragraphs.joinToString("\n\n") { p -> p.sentences.joinToString("") { it.text } }
            
            onPartialResult?.invoke(TranscriptResult(audio.id, fullText, sentences.flatMap { it.words }, sentences, paragraphs, totalDurationMs, currentOffsetMs >= totalDurationMs, currentOffsetMs))
        }
        
        
        onProgress(1.0f)
        val finalSentences = buildSentencesFromWords(accumulatedWords, totalDurationMs)
        val finalParagraphs = buildParagraphsFromSentences(finalSentences)
        val finalFullText = finalParagraphs.joinToString("\n\n") { p -> p.sentences.joinToString("") { it.text } }
        TranscriptResult(audio.id, finalFullText, finalSentences.flatMap { it.words }, finalSentences, finalParagraphs, totalDurationMs, true, totalDurationMs)
    }

    private fun getOrInitVad(): Vad? = synchronized(lock) {
        if (cachedVad != null) return cachedVad
        val vadFile = File(context.filesDir, "$modelDirName/silero_vad.int8.onnx")
        if (!vadFile.exists()) return null
        return try {
            val sileroConfig = SileroVadModelConfig(model = vadFile.absolutePath, threshold = 0.5f, minSilenceDuration = 0.5f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 30.0f)
            cachedVad = Vad(assetManager = null, config = VadModelConfig(sileroVadModelConfig = sileroConfig, sampleRate = 16000, numThreads = 1, debug = false))
            cachedVad
        } catch (e: Throwable) { null }
    }

    private fun getOrInitRecognizer(): OfflineRecognizer? = synchronized(lock) {
        if (cachedRecognizer != null) return cachedRecognizer
        val modelFile = File(context.filesDir, "$modelDirName/model.int8.onnx")
        val tokensFile = File(context.filesDir, "$modelDirName/tokens.txt")
        if (!modelFile.exists() || !tokensFile.exists()) return null
        return try {
            val senseVoiceConfig = OfflineSenseVoiceModelConfig(model = modelFile.absolutePath, language = "", useInverseTextNormalization = true)
            cachedRecognizer = OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = OfflineModelConfig(senseVoice = senseVoiceConfig, tokens = tokensFile.absolutePath, numThreads = 2, debug = false)))
            cachedRecognizer
        } catch (e: Throwable) { null }
    }

    private fun transcribeChunkWithVad(
        vad: Vad?, recognizer: OfflineRecognizer, samples: FloatArray,
        actualStartMs: Long, sliceStartMs: Long, sliceEndMs: Long, totalDurationMs: Long
    ): List<TranscriptWord> {
        if (vad == null) return runSherpaRecognitionSegment(recognizer, samples, actualStartMs, sliceStartMs, sliceEndMs, totalDurationMs) ?: emptyList()
        val words = mutableListOf<TranscriptWord>()
        try {
            vad.reset()
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 512, samples.size)
                vad.acceptWaveform(samples.copyOfRange(offset, end))
                while (!vad.empty()) {
                    val seg = vad.front()
                    if (seg.samples.isNotEmpty()) {
                        runSherpaRecognitionSegment(recognizer, seg.samples, actualStartMs + (seg.start * 1000L / 16000), sliceStartMs, sliceEndMs, totalDurationMs)?.let { words.addAll(it) }
                    }
                    vad.pop()
                }
                offset += 512
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                if (seg.samples.isNotEmpty()) {
                    runSherpaRecognitionSegment(recognizer, seg.samples, actualStartMs + (seg.start * 1000L / 16000), sliceStartMs, sliceEndMs, totalDurationMs)?.let { words.addAll(it) }
                }
                vad.pop()
            }
        } catch (e: Throwable) {
            return runSherpaRecognitionSegment(recognizer, samples, actualStartMs, sliceStartMs, sliceEndMs, totalDurationMs) ?: emptyList()
        }
        return words
    }

    private fun runSherpaRecognitionSegment(
        recognizer: OfflineRecognizer, samples: FloatArray, baseTimeMs: Long,
        sliceStartMs: Long, sliceEndMs: Long, totalDurationMs: Long
    ): List<TranscriptWord>? {
        var stream: OfflineStream? = null
        return try {
            stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val res = recognizer.getResult(stream)
            processSenseVoiceResult(res.text, res.tokens, res.timestamps, res.durations, baseTimeMs, sliceStartMs, sliceEndMs, totalDurationMs)
        } catch (e: Throwable) { null } finally { stream?.release() }
    }

    private suspend fun decodeAudioTimeRangeTo16kMonoPCM(
        audio: AudioItem, startMs: Long, endMs: Long
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (File(audio.filePath).exists()) extractor.setDataSource(audio.filePath)
            else if (audio.contentUri != null) extractor.setDataSource(context, audio.contentUri, null)
            else return@withContext FloatArray(0)
            
            var trackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) { trackIdx = i; break }
            }
            if (trackIdx < 0) return@withContext FloatArray(0)
            
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            
            // 关键：SEEK 后需要检查实际落点
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            // 预估 PCM 大小，避免频繁扩容
            val estimatedSamples = ((endMs - startMs + 1000) * sampleRate / 1000).toInt()
            var pcmData = ShortArray(maxOf(estimatedSamples, 1024))
            var pcmPos = 0
            
            val info = MediaCodec.BufferInfo()
            var outEOS = false
            var inEOS = false
            
            var decodeStartTime = System.currentTimeMillis()
            
            while (!outEOS && System.currentTimeMillis() - decodeStartTime < 10000) { // 增加超时保护
                if (!inEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        codec.getInputBuffer(inIdx)?.let { buf ->
                            val size = extractor.readSampleData(buf, 0)
                            val sampleTime = extractor.sampleTime
                            
                            if (size < 0 || (sampleTime > endUs + 500_000L && sampleTime > 0)) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    // 只要在范围内，或者是因为 SEEK 导致的略微提前的数据，都采纳
                    // 但我们要严格遵守 endUs，防止读过头
                    if (info.presentationTimeUs >= (startUs - 500_000L) && info.presentationTimeUs <= endUs + 100_000L) {
                        codec.getOutputBuffer(outIdx)?.let { buf ->
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            val sb = buf.asShortBuffer()
                            val count = sb.remaining() / channels
                            
                            if (pcmPos + count >= pcmData.size) {
                                pcmData = pcmData.copyOf(maxOf(pcmData.size * 2, pcmPos + count + 1024))
                            }
                            
                            while (sb.hasRemaining()) {
                                val left = sb.get().toInt()
                                val right = if (channels >= 2) sb.get().toInt() else left
                                pcmData[pcmPos++] = ((left + right) / 2).toShort()
                                for (c in 2 until channels) if (sb.hasRemaining()) sb.get()
                            }
                        }
                    }
                    
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) || info.presentationTimeUs > endUs) {
                        outEOS = true
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            
            if (pcmPos == 0) return@withContext FloatArray(0)
            
            // 重采样到 16kHz
            val ratio = 16000.0 / sampleRate
            val result = FloatArray((pcmPos * ratio).toInt())
            for (i in result.indices) {
                val src = i / ratio
                val idx = src.toInt()
                val f = (src - idx).toFloat()
                val s0 = pcmData[idx.coerceIn(0, pcmPos - 1)]
                val s1 = pcmData[(idx + 1).coerceIn(0, pcmPos - 1)]
                result[i] = ((s0 + f * (s1 - s0)) / 32768f).coerceIn(-1f, 1f)
            }
            result
        } catch (e: Exception) {
            FloatArray(0)
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {}
            extractor.release()
        }
    }
}
