package com.example.audiocut.asr

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.audiocut.data.model.AudioItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 流水线第一阶段：分块（Chunking）
 * 负责将音频按时间范围解码为 16kHz 单声道归一化 PCM，并规划带前后冗余的分块区间。
 */
class AudioChunker(private val context: Context) {

    /** 分片前后冗余时长，避免截断词语（毫秒） */
    companion object {
        const val CHUNK_REDUNDANCY_MS = 2_000L
        const val CHUNK_TAIL_MS = 500L
        const val SAMPLE_RATE = 16000
    }

    /**
     * 规划分块区间。
     * @return 每个元素为 (actualStartMs, actualEndMs, sliceStartMs, sliceEndMs)，
     *         actual 为解码范围（含冗余），slice 为归属当前块的有效范围。
     */
    fun buildChunkRanges(
        totalMs: Long,
        startOffsetMs: Long,
        chunkTargetMs: Long
    ): List<LongArray> {
        if (totalMs <= 0) return emptyList()
        val ranges = mutableListOf<LongArray>()
        var offset = startOffsetMs.coerceIn(0L, totalMs)

        while (offset < totalMs) {
            val sliceStart = offset
            val sliceEnd = minOf(totalMs, sliceStart + chunkTargetMs)

            val actualStart = maxOf(0L, sliceStart - CHUNK_REDUNDANCY_MS)
            val actualEnd = minOf(totalMs, sliceEnd + CHUNK_TAIL_MS)
            if (actualEnd <= actualStart) break

            ranges.add(longArrayOf(actualStart, actualEnd, sliceStart, sliceEnd))
            offset = sliceEnd
        }
        return ranges
    }

    /**
     * 使用 FFmpeg 解码指定时间段的音频，输出 16kHz 单声道归一化 PCM（Float，-1..1）
     */
    suspend fun decodePcm(audio: AudioItem, startMs: Long, endMs: Long): FloatArray =
        withContext(Dispatchers.IO) {
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
                outputPcmFile.delete()

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
    fun extractAudioDuration(audio: AudioItem): Long {
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
     * 处理输入音频：Uri 类型转存为临时文件
     */
    suspend fun getInputFile(audio: AudioItem): File? = withContext(Dispatchers.IO) {
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
