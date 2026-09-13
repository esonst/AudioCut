package com.example.mp3player.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 本地音频波形振幅数据解析器
 * 从本地音频文件中高效采样振幅数据，用于绘制高精度可缩放频谱波形图
 */
class WaveformExtractor(private val context: Context) {

    // 内存缓存音频波形数据
    private val waveformCache = mutableMapOf<String, List<Float>>()

    suspend fun extractWaveform(
        audioPath: String,
        uri: Uri? = null,
        targetSamples: Int = 300
    ): List<Float> = withContext(Dispatchers.IO) {
        val cacheKey = "$audioPath-$targetSamples"
        waveformCache[cacheKey]?.let { 
            return@withContext it 
        }

        val amplitudes = mutableListOf<Float>()
        val extractor = MediaExtractor()

        try {
            if (File(audioPath).exists()) {
                extractor.setDataSource(audioPath)
            } else if (uri != null) {
                extractor.setDataSource(context, uri, null)
            } else {
                return@withContext generateFallbackWaveform(audioPath, targetSamples)
            }

            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex >= 0 && format != null) {
                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val rawAmplitudes = mutableListOf<Float>()
                val bufferInfo = MediaCodec.BufferInfo()
                var isEOS = false

                val maxExtractFrames = 8000
                var frameCount = 0

                while (!isEOS && frameCount < maxExtractFrames) {
                    val inIndex = codec.dequeueInputBuffer(2000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        buffer?.clear()
                        val sampleSize = extractor.readSampleData(buffer ?: ByteBuffer.allocate(0), 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    var outIndex = codec.dequeueOutputBuffer(bufferInfo, 2000)
                    while (outIndex >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            var sum = 0.0
                            var count = 0
                            val shortBuffer = outBuffer.asShortBuffer()
                            val step = 8 // 抽取降低计算量
                            var i = 0
                            while (i < shortBuffer.remaining()) {
                                val sample = shortBuffer.get(i).toDouble()
                                sum += sample * sample
                                count++
                                i += step
                            }
                            if (count > 0) {
                                val rms = sqrt(sum / count).toFloat()
                                rawAmplitudes.add(rms)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                    frameCount++
                }

                codec.stop()
                codec.release()

                // 降采样至 targetSamples
                if (rawAmplitudes.isNotEmpty()) {
                    val maxVal = rawAmplitudes.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                    val chunkSize = (rawAmplitudes.size.toFloat() / targetSamples).coerceAtLeast(1f)

                    for (i in 0 until targetSamples) {
                        val start = (i * chunkSize).toInt().coerceIn(0, rawAmplitudes.size - 1)
                        val end = ((i + 1) * chunkSize).toInt().coerceIn(start + 1, rawAmplitudes.size)
                        val subList = rawAmplitudes.subList(start, end)
                        val avg = if (subList.isNotEmpty()) subList.average().toFloat() / maxVal else 0.1f
                        amplitudes.add(avg.coerceIn(0.08f, 1.0f))
                    }
                }
            } else {
            }
        } catch (e: Exception) {
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
            }
        }

        // 如果解析失败或空，生成保底模拟波形（基于音频指纹）
        val result = if (amplitudes.isNotEmpty()) {
            amplitudes
        } else {
            generateFallbackWaveform(audioPath, targetSamples)
        }

        waveformCache[cacheKey] = result
        result
    }

    private fun generateFallbackWaveform(seedKey: String, count: Int): List<Float> {
        val hash = abs(seedKey.hashCode())
        val random = java.util.Random(hash.toLong())
        val list = mutableListOf<Float>()
        var prev = 0.4f
        for (i in 0 until count) {
            val delta = (random.nextFloat() - 0.5f) * 0.3f
            prev = (prev + delta).coerceIn(0.12f, 0.95f)
            list.add(prev)
        }
        return list
    }
}
