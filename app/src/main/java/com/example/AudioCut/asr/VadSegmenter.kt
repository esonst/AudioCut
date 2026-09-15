package com.example.AudioCut.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 流水线第二阶段：VAD（语音活动检测）
 * 负责从 PCM 中切分出人声片段，供识别器逐段识别。
 * VAD 实例为全局单例，避免重复加载。
 */
class VadSegmenter {

    /** VAD 切分出的一个语音片段 */
    data class SpeechSegment(
        /** 片段在音频中的起始时间（毫秒，相对全片） */
        val startMs: Long,
        /** 片段 PCM 数据 */
        val samples: FloatArray
    )

    companion object {
        private const val VAD_WINDOW_SIZE = 512

        @Volatile
        private var cachedVad: Vad? = null
        private val lock = Any()

        fun release() {
            synchronized(lock) {
                runCatching { cachedVad?.release() }
                cachedVad = null
            }
        }
    }

    /**
     * 获取或初始化 VAD 单例
     */
    fun getOrInit(
        vadFile: File,
        threshold: Float = 0.5f,
        minSilenceDuration: Float = 0.5f,
        minSpeechDuration: Float = 0.25f,
        maxSpeechDuration: Float = 30.0f
    ): Vad? = synchronized(lock) {
        if (cachedVad != null) return cachedVad
        if (!vadFile.exists()) return null

        return runCatching {
            val config = SileroVadModelConfig(
                model = vadFile.absolutePath,
                threshold = threshold,
                minSilenceDuration = minSilenceDuration,
                minSpeechDuration = minSpeechDuration,
                windowSize = VAD_WINDOW_SIZE,
                maxSpeechDuration = maxSpeechDuration
            )
            cachedVad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = config,
                    sampleRate = AudioChunker.SAMPLE_RATE,
                    numThreads = 1,
                    debug = false
                )
            )
            cachedVad
        }.getOrNull()
    }

    /**
     * 对 PCM 执行 VAD 分段。
     * @param samples 待检测 PCM（16kHz）
     * @param baseTimeMs 该 PCM 在音频中的起始时间（毫秒）
     */
    fun segment(
        vad: Vad,
        samples: FloatArray,
        baseTimeMs: Long
    ): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        runCatching {
            vad.reset()
            var offset = 0

            while (offset < samples.size) {
                val end = minOf(offset + VAD_WINDOW_SIZE, samples.size)
                vad.acceptWaveform(samples.copyOfRange(offset, end))

                while (!vad.empty()) {
                    val segment = vad.front()
                    if (segment.samples.isNotEmpty()) {
                        val segmentStartMs = baseTimeMs + (segment.start * 1000L / AudioChunker.SAMPLE_RATE)
                        segments.add(SpeechSegment(segmentStartMs, segment.samples))
                    }
                    vad.pop()
                }
                offset += VAD_WINDOW_SIZE
            }

            // 刷新缓冲区剩余数据
            vad.flush()
            while (!vad.empty()) {
                val segment = vad.front()
                if (segment.samples.isNotEmpty()) {
                    val segmentStartMs = baseTimeMs + (segment.start * 1000L / AudioChunker.SAMPLE_RATE)
                    segments.add(SpeechSegment(segmentStartMs, segment.samples))
                }
                vad.pop()
            }
        }
        return segments
    }
}
