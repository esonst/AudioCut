package com.example.audiocut.ffmpeg

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.data.model.AudioSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ExportAudioFormat(val extension: String, val displayName: String, val mimeType: String) {
    M4A("m4a", "M4A (AAC 压缩)", "audio/mp4a-latm"),
    WAV("wav", "WAV (无损母带)", "audio/wav"),
    MP3("mp3", "MP3 (通用音频)", "audio/mpeg")
}

data class ExportResult(
    val isSuccess: Boolean,
    val outputPath: String = "",
    val errorMessage: String = "",
    val durationMs: Long = 0L,
    val format: ExportAudioFormat = ExportAudioFormat.M4A,
    /** 产物是否为带画面的视频（视频源保留画面导出） */
    val outputIsVideo: Boolean = false
)

/**
 * 音频切片与多片段高精度拼接导出器
 * 支持流拷贝（无损且极速）和 PCM 重采样合并两种模式
 */
class AudioCutterConcatenator(private val context: Context) {

    companion object {
        /** 纯音频扩展名：即使内嵌封面视频轨道也按音频处理，不判为视频 */
        val AUDIO_ONLY_EXTENSIONS = setOf(
            "mp3", "wav", "flac", "aac", "ogg", "opus",
            "m4a", "m4b", "m4r", "amr", "wma", "ape", "alac", "mid", "midi"
        )
    }

    /**
     * 【新逻辑】极速无损预览与拼接：优先尝试流拷贝，失败自动降级到转码合并
     */
    suspend fun generateFastPreview(
        sourceAudio: AudioItem,
        segments: List<AudioSegment>,
        onProgress: suspend (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val validSegments = segments.filter { it.isSelected && it.endMs > it.startMs }
        if (validSegments.isEmpty()) return@withContext ExportResult(false, errorMessage = "无有效片段")

        val sourceFile = File(sourceAudio.filePath)
        val ext = sourceFile.extension.lowercase().ifEmpty { "m4a" }
        val previewFile = File(context.cacheDir, "fast_preview_${System.currentTimeMillis()}.$ext")

        // 1. 尝试流拷贝 (Stream Copy)
        try {
            val result = performStreamCopy(sourceAudio, validSegments, previewFile, onProgress)
            if (result.isSuccess) return@withContext result
        } catch (e: Exception) {
            // 流拷贝失败，降级到转码
        }

        // 2. 失败后降级到转码，且目标格式设为原格式（尽力而为）
        val targetFormat = when (ext) {
            "mp3" -> ExportAudioFormat.MP3
            "wav" -> ExportAudioFormat.WAV
            else -> ExportAudioFormat.M4A
        }

        try {
            return@withContext generateTranscodedAudio(sourceAudio, validSegments, targetFormat, onProgress)
        } catch (e: Exception) {
            ExportResult(false, errorMessage = "预览生成完全失败: ${e.localizedMessage}")
        }
    }

    /**
     * 生成转码音频（替代原 generatePreviewAudio，支持指定格式）
     */
    suspend fun generateTranscodedAudio(
        sourceAudio: AudioItem,
        segments: List<AudioSegment>,
        targetFormat: ExportAudioFormat = ExportAudioFormat.M4A,
        onProgress: suspend (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val validSegments = segments.filter { it.isSelected && it.endMs > it.startMs }
        if (validSegments.isEmpty()) return@withContext ExportResult(false, errorMessage = "无有效片段")

        val outputFile = File(context.cacheDir, "transcoded_merged_${System.currentTimeMillis()}.${targetFormat.extension}")
        var tempPcmFile: File? = null
        try {
            tempPcmFile = File.createTempFile("export_pcm_", ".raw", context.cacheDir)
            val pcmInfo = extractAndConcatPcmToTempFile(sourceAudio, validSegments, tempPcmFile) { prog ->
                onProgress(prog * 0.7f)
            }

            if (pcmInfo == null) return@withContext ExportResult(false, errorMessage = "解码失败")

            val success = when (targetFormat) {
                ExportAudioFormat.WAV -> writeWavFile(outputFile, tempPcmFile, pcmInfo.sampleRate, pcmInfo.channels)
                // MP3 使用 FFmpeg libmp3lame 真编码（MediaCodec 无 MP3 编码器），保证 .mp3 后缀与内容一致
                // 为了播放正确性，如果目标是 MP3 但没有 MP3 编码器，则回退到 M4A
                ExportAudioFormat.MP3 -> encodePcmToMp3(outputFile, tempPcmFile, pcmInfo.sampleRate, pcmInfo.channels) { onProgress(0.7f + it * 0.3f) }
                ExportAudioFormat.M4A -> encodePcmToM4a(outputFile, tempPcmFile, pcmInfo.sampleRate, pcmInfo.channels) { onProgress(0.7f + it * 0.3f) }
            }

            if (success) {
                ExportResult(true, outputFile.absolutePath, durationMs = validSegments.sumOf { it.durationMs }, format = targetFormat)
            } else {
                ExportResult(false, errorMessage = "写入失败")
            }
        } catch (e: Exception) {
            ExportResult(false, errorMessage = e.localizedMessage ?: "异常")
        } finally {
            tempPcmFile?.delete()
        }
    }

    /**
     * 导出
     */
    suspend fun exportSegments(
        sourceAudio: AudioItem,
        segments: List<AudioSegment>,
        customFileName: String? = null,
        targetFormat: ExportAudioFormat = ExportAudioFormat.M4A,
        onProgress: suspend (Float) -> Unit
    ): ExportResult = withContext(Dispatchers.IO) {
        val validSegments = segments.filter { it.isSelected && it.endMs > it.startMs }
        if (validSegments.isEmpty()) return@withContext ExportResult(false, errorMessage = "无有效片段")

        // 【视频输入】导出带画面的视频（MP4，保留原视频画面 + 处理后的音频）
        if (isVideoSource(sourceAudio)) {
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "exports")
            outputDir.mkdirs()
            val baseName = customFileName ?: "Clip_${System.currentTimeMillis()}"
            val videoFile = File(outputDir, "$baseName.mp4")
            return@withContext exportVideoWithSegments(sourceAudio, validSegments, videoFile, onProgress)
        }

        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "exports")
        outputDir.mkdirs()
        val baseName = customFileName ?: "Clip_${System.currentTimeMillis()}"
        val outputFile = File(outputDir, "${baseName}.${targetFormat.extension}")

        val sourceExt = File(sourceAudio.filePath).extension.lowercase()
        
        // 1. 如果目标格式与源格式一致，且不是 WAV，优先尝试流拷贝
        if (sourceExt == targetFormat.extension.lowercase() && sourceExt != "wav") {
            try {
                val result = performStreamCopy(sourceAudio, validSegments, outputFile, onProgress)
                if (result.isSuccess) return@withContext result
            } catch (e: Exception) {
                // 流拷贝失败，降级到转码
            }
        }

        // 2. 转码流程
        return@withContext generateTranscodedAudio(sourceAudio, validSegments, targetFormat, onProgress)
    }

    /**
     * 判断源是否包含视频轨道（传入的是视频文件）
     * 音频扩展名（m4a/mp3 等）即使内嵌封面视频轨道也按音频处理，
     * 避免带封面的 m4a 被误判为视频
     */
    suspend fun isVideoSource(sourceAudio: AudioItem): Boolean = withContext(Dispatchers.IO) {
        // 纯音频扩展名优先判音频（封面图常被编码为视频轨道，不算真实视频）
        val ext = sourceAudio.filePath.substringAfterLast('.', "").lowercase()
        if (ext in AUDIO_ONLY_EXTENSIONS) return@withContext false
        try {
            val extractor = MediaExtractor()
            try {
                if (sourceAudio.contentUri != null) extractor.setDataSource(context, sourceAudio.contentUri, null)
                else extractor.setDataSource(sourceAudio.filePath)
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) return@withContext true
                }
                false
            } finally {
                runCatching { extractor.release() }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 【视频输入】按片段精确裁剪并拼接，保留视频画面，输出 MP4（H.264 + AAC）
     * 预览阶段仍为纯音频；保存/导出/覆盖/分享调用本方法，确保最终产物带画面
     */
    suspend fun exportVideoWithSegments(
        sourceAudio: AudioItem,
        segments: List<AudioSegment>,
        outputFile: File,
        onProgress: suspend (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val validSegments = segments.filter { it.isSelected && it.endMs > it.startMs && (it.endMs - it.startMs) >= 100L }
        if (validSegments.isEmpty()) return@withContext ExportResult(false, errorMessage = "无有效片段")

        val (inputPath, tempSource) = resolveLocalPathForFfmpeg(sourceAudio)
        if (inputPath == null) {
            return@withContext ExportResult(false, errorMessage = "视频源无法访问，无法保留画面导出")
        }

        onProgress(0.05f)
        val tmpOut = File(outputFile.parentFile ?: outputFile, "tmp_${outputFile.name}")
        try {
            val command = buildVideoConcatCommand(inputPath, validSegments, tmpOut.absolutePath)
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                return@withContext ExportResult(false, errorMessage = "视频导出失败: ${session.output?.takeLast(300) ?: "ffmpeg 错误"}")
            }
            if (!tmpOut.exists() || tmpOut.length() == 0L) {
                return@withContext ExportResult(false, errorMessage = "视频导出失败：输出为空")
            }
            if (outputFile.exists()) outputFile.delete()
            if (!tmpOut.renameTo(outputFile)) {
                tmpOut.copyTo(outputFile, overwrite = true)
                tmpOut.delete()
            }
            onProgress(1f)
            ExportResult(
                true,
                outputFile.absolutePath,
                durationMs = validSegments.sumOf { it.durationMs },
                outputIsVideo = true
            )
        } catch (e: Exception) {
            runCatching { tmpOut.delete() }
            ExportResult(false, errorMessage = "视频导出异常: ${e.localizedMessage}")
        } finally {
            // 清理为 FFmpeg 临时拷贝的源文件
            tempSource?.delete()
        }
    }

    /**
     * FFmpeg 需要本地文件路径：
     * 1. 本地文件路径存在则直接使用
     * 2. MediaStore 查询 DATA 真实路径
     * 3. 其它 content Uri 临时拷贝到缓存目录（处理完成后删除）
     * @return (本地路径, 是否为临时拷贝文件)
     */
    private fun resolveLocalPathForFfmpeg(sourceAudio: AudioItem): Pair<String?, File?> {
        val filePath = sourceAudio.filePath
        if (filePath.isNotBlank() && File(filePath).exists()) return filePath to null

        val uri = sourceAudio.contentUri ?: return null to null
        // MediaStore DATA 路径
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx >= 0) {
                        val p = c.getString(idx)
                        if (!p.isNullOrBlank() && File(p).exists()) return p to null
                    }
                }
            }
        } catch (_: Exception) {
        }
        // 临时拷贝到缓存目录
        return try {
            val name = getFileNameFromUri(uri) ?: "video_src_${System.currentTimeMillis()}.mp4"
            val dest = File(context.cacheDir, "ffmpeg_src_${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.exists() && dest.length() > 0L) dest.absolutePath to dest else null to null
        } catch (_: Exception) {
            null to null
        }
    }

    /** 从 content Uri 查询显示文件名 */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 构建 FFmpeg filter_complex 命令：对每个片段精确 trim（含视频+音频），再 concat 拼接
     */
    private fun buildVideoConcatCommand(inputPath: String, segments: List<AudioSegment>, outputPath: String): String {
        val sb = StringBuilder("-hide_banner -loglevel error -i \"$inputPath\" -filter_complex \"")
        val filters = mutableListOf<String>()
        segments.forEachIndexed { index, seg ->
            val s = "%.3f".format(seg.startMs / 1000.0)
            val e = "%.3f".format(seg.endMs / 1000.0)
            filters.add("[0:v]trim=start=$s:end=$e,setpts=PTS-STARTPTS[v$index]")
            filters.add("[0:a]atrim=start=$s:end=$e,asetpts=PTS-STARTPTS[a$index]")
        }
        sb.append(filters.joinToString(";"))
        val vLabels = segments.indices.joinToString("") { "[v$it]" }
        val aLabels = segments.indices.joinToString("") { "[a$it]" }
        sb.append(";${vLabels}${aLabels}concat=n=${segments.size}:v=1:a=1[vout][aout]\" ")
        sb.append("-map \"[vout]\" -map \"[aout]\" ")
        sb.append("-c:v libx264 -preset veryfast -crf 23 -c:a aac -b:a 192k -movflags +faststart \"$outputPath\"")
        return sb.toString()
    }

    /**
     * 核心流拷贝实现：支持 M4A (MediaMuxer) 和 MP3 (Raw Frame Write)
     * 确保按照 segments 列表的顺序进行物理拼接。
     */
    private suspend fun performStreamCopy(
        sourceAudio: AudioItem,
        segments: List<AudioSegment>,
        outputFile: File,
        onProgress: suspend (Float) -> Unit
    ): ExportResult {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var mp3Fos: BufferedOutputStream? = null
        val buffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.nativeOrder())
        val bufferInfo = MediaCodec.BufferInfo()
        val mp3Buffer = ByteArray(1024 * 1024)

        try {
            if (sourceAudio.contentUri != null) extractor.setDataSource(context, sourceAudio.contentUri, null)
            else extractor.setDataSource(sourceAudio.filePath)

            var trackIdx = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i
                    sourceFormat = fmt
                    break
                }
            }

            if (trackIdx < 0 || sourceFormat == null) return ExportResult(false, errorMessage = "未找到音频轨道")
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: ""
            extractor.selectTrack(trackIdx)

            val isMp3 = mime.contains("mpeg")
            var totalDurationUs = 0L
            var writeTrackIdx = -1
            if (isMp3) {
                mp3Fos = BufferedOutputStream(FileOutputStream(outputFile), 8192)
            } else {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                writeTrackIdx = muxer.addTrack(cleanAudioFormat(sourceFormat))
                muxer.start()
            }

            // 严格按照传入列表的索引顺序处理每一个片段
            val totalSegments = segments.size
            var processedSegments = 0
            segments.forEach { seg ->
                // 每次切换片段前，强制 Seek 到目标起点
                extractor.seekTo(seg.startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                var firstSampleInSeg = true
                var basePtsUs = 0L
                var lastSampleTimeUsInOutput = 0L
                var samplesWritten = 0

                while (true) {
                    val sampleTime = extractor.sampleTime
                    // 如果达到文件末尾或超过当前片段设定的终点，停止读取该片段
                    if (sampleTime < 0 || sampleTime > seg.endMs * 1000L) break

                    if (sampleTime >= seg.startMs * 1000L) {
                        if (firstSampleInSeg) {
                            basePtsUs = sampleTime
                            firstSampleInSeg = false
                        }

                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break

                        bufferInfo.size = size
                        bufferInfo.offset = 0
                        bufferInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0

                        // 计算该样本在输出流中的 PTS：当前累积总时长 + 该样本在原片段中的相对位置
                        val relativePts = sampleTime - basePtsUs
                        bufferInfo.presentationTimeUs = totalDurationUs + relativePts

                        // 准备缓冲区
                        buffer.position(0)
                        buffer.limit(size)

                        if (isMp3) {
                            buffer.get(mp3Buffer, 0, size)
                            mp3Fos?.write(mp3Buffer, 0, size)
                        } else {
                            muxer?.writeSampleData(writeTrackIdx, buffer, bufferInfo)
                        }

                        lastSampleTimeUsInOutput = bufferInfo.presentationTimeUs
                        samplesWritten++
                    }
                    extractor.advance()
                }

                // 更新下一段的起始基准时间戳
                if (samplesWritten > 0) {
                    // 估算帧间隔：对于 44.1k/48k AAC 约为 23ms，MP3 约为 26ms。使用 25ms 作为一个通用的平滑过渡值。
                    totalDurationUs = lastSampleTimeUsInOutput + 25000L
                } else {
                    // 如果该段未写入任何样本（可能范围太小或无效），则按逻辑时长累加
                    totalDurationUs += (seg.endMs - seg.startMs) * 1000L
                }
                
                processedSegments++
                // 每5个片段更新一次进度，减少回调频率
                if (processedSegments % 5 == 0 || processedSegments == totalSegments) {
                    onProgress(processedSegments.toFloat() / totalSegments)
                }
            }

            if (!isMp3) {
                muxer?.stop()
            } else {
                mp3Fos?.flush()
            }

            return ExportResult(true, outputFile.absolutePath, durationMs = totalDurationUs / 1000L)
        } catch (e: Exception) {
            return ExportResult(false, errorMessage = "流拷贝拼接失败: ${e.localizedMessage}")
        } finally {
            try { extractor.release() } catch (ignored: Exception) {}
            try { muxer?.release() } catch (ignored: Exception) {}
            try { mp3Fos?.close() } catch (ignored: Exception) {}
        }
    }


    private data class DecodedPcmInfo(val sampleRate: Int, val channels: Int)

    /**
     * 核心：按顺序（逐个片段）提取 PCM 并合并
     */
    private suspend fun extractAndConcatPcmToTempFile(
        audio: AudioItem,
        segments: List<AudioSegment>,
        tempPcmFile: File,
        onProgress: suspend (Float) -> Unit
    ): DecodedPcmInfo? = withContext(Dispatchers.IO) {
        var pcmOutStream: BufferedOutputStream? = null
        var resultInfo: DecodedPcmInfo? = null

        try {
            pcmOutStream = BufferedOutputStream(FileOutputStream(tempPcmFile))
            
            segments.forEachIndexed { index, seg ->
                val info = extractSingleSegmentPcm(audio, seg, pcmOutStream)
                if (info != null) resultInfo = info
                onProgress((index + 1).toFloat() / segments.size)
            }
            
            pcmOutStream.flush()
            resultInfo
        } catch (e: Exception) {
            null
        } finally {
            try { pcmOutStream?.close() } catch (ignored: Exception) {}
        }
    }

    private fun extractSingleSegmentPcm(
        audio: AudioItem,
        seg: AudioSegment,
        outStream: BufferedOutputStream
    ): DecodedPcmInfo? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (audio.contentUri != null) extractor.setDataSource(context, audio.contentUri, null)
            else extractor.setDataSource(audio.filePath)

            var trackIdx = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; break
                }
            }
            if (trackIdx < 0) return null
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            
            extractor.seekTo(seg.startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEOS = false
            var outputEOS = false
            val byteChunk = ByteArray(8192)

            while (!outputEOS) {
                if (!inputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = extractor.readSampleData(buf!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIdx >= 0) {
                    val ptsMs = bufferInfo.presentationTimeUs / 1000L
                    if (ptsMs >= seg.startMs && ptsMs <= seg.endMs) {
                        val buf = codec.getOutputBuffer(outIdx)
                        buf?.let {
                            it.position(bufferInfo.offset)
                            it.limit(bufferInfo.offset + bufferInfo.size)
                            while (it.hasRemaining()) {
                                val len = it.remaining().coerceAtMost(byteChunk.size)
                                it.get(byteChunk, 0, len)
                                outStream.write(byteChunk, 0, len)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || ptsMs > seg.endMs) {
                        outputEOS = true
                    }
                }
            }
            return DecodedPcmInfo(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
            try { extractor.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * 写 WAV 头部
     */
    private fun writeWavFile(file: File, pcmFile: File, sampleRate: Int, channels: Int): Boolean {
        val totalAudioLen = pcmFile.length()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * 2).toLong()
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte(); header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
        header[6] = ((totalDataLen shr 16) and 0xffL).toByte(); header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte(); header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xffL).toByte(); header[29] = ((byteRate shr 8) and 0xffL).toByte()
        header[30] = ((byteRate shr 16) and 0xffL).toByte(); header[31] = ((byteRate shr 24) and 0xffL).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte(); header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xffL).toByte(); header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()
        return try {
            FileOutputStream(file).use { fos ->
                fos.write(header)
                FileInputStream(pcmFile).use { fis -> fis.copyTo(fos) }
            }
            true
        } catch (e: Exception) { false }
    }

    /**
     * 编码为 M4A
     */
    private suspend fun encodePcmToM4a(
        file: File, pcmFile: File, sampleRate: Int, channels: Int, onProgress: suspend (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIdx = -1
            var started = false
            val fis = FileInputStream(pcmFile)
            val bufferInfo = MediaCodec.BufferInfo()
            // AAC-LC 每帧固定 1024 采样，输入为 16-bit PCM：帧字节数 = 1024 x channels x 2
            // （mono 2048 / stereo 4096），按整帧送入编码器，避免 mono 时 4096 非整帧导致编码失败
            val frameBytes = (1024 * channels * 2).coerceAtLeast(1024)
            val chunk = ByteArray(frameBytes)
            var ptsUs = 0L
            var inputEOS = false
            var outputEOS = false
            while (!outputEOS) {
                if (!inputEOS) {
                    val inIdx = encoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)
                        val size = fis.read(chunk)
                        if (size <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEOS = true
                        } else {
                            buf!!.put(chunk, 0, size)
                            encoder.queueInputBuffer(inIdx, 0, size, ptsUs, 0)
                            ptsUs += (size * 1000000L / (sampleRate * channels * 2))
                            onProgress(ptsUs.toFloat() / (pcmFile.length() * 1000000L / (sampleRate * channels * 2)))
                        }
                    }
                }
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIdx >= 0) {
                    if (started) {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null) muxer.writeSampleData(trackIdx, outBuf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEOS = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIdx = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true
                }
            }
            true
        } catch (e: Exception) { false } finally {
            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            try { muxer?.stop() } catch (ignored: Exception) {}
            try { muxer?.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * 清理 MediaFormat，移除 MediaMuxer 不支持或可能导致失败的元数据键
     */
    private fun cleanAudioFormat(format: MediaFormat): MediaFormat {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return format
        val newFormat = MediaFormat.createAudioFormat(
            mime,
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        )
        
        // 复制必要的编码参数
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            newFormat.setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE))
        }
        
        // 复制 CSD (Codec Specific Data) - 对于 AAC 等格式至关重要
        var i = 0
        while (format.containsKey("csd-$i")) {
            val csd = format.getByteBuffer("csd-$i")
            if (csd != null) {
                newFormat.setByteBuffer("csd-$i", csd)
            }
            i++
        }
        
        return newFormat
    }

    /**
     * 编码为 MP3：使用 FFmpeg libmp3lame 真编码（MediaCodec 不提供 MP3 编码器），
     * 确保 .mp3 后缀与文件内容一致，可被系统播放器正确识别。
     */
    private suspend fun encodePcmToMp3(
        file: File, pcmFile: File, sampleRate: Int, channels: Int, onProgress: suspend (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val tmpOut = File(file.parentFile ?: file, "tmp_${file.name}")
        try {
            val command = "-hide_banner -loglevel error -f s16le -ar $sampleRate -ac $channels " +
                    "-i \"${pcmFile.absolutePath}\" -codec:a libmp3lame -q:a 2 \"${tmpOut.absolutePath}\""
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) return@withContext false
            if (!tmpOut.exists() || tmpOut.length() == 0L) return@withContext false
            if (file.exists()) file.delete()
            if (!tmpOut.renameTo(file)) return@withContext false
            onProgress(1f)
            true
        } catch (e: Exception) {
            runCatching { tmpOut.delete() }
            false
        }
    }
}
