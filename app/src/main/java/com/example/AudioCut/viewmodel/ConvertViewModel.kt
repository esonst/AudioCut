package com.example.audiocut.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.*
import com.example.audiocut.core.AppEventBus
import com.example.audiocut.data.model.ConvertQuality
import com.example.audiocut.data.model.ConvertState
import com.example.audiocut.player.AudioPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 格式转换界面 ViewModel
 * 负责 FFmpeg 格式转换、进度跟踪、取消、分享与保存
 *
 * 通过事件总线接收跳转转换页事件（携带输入文件路径）
 */
class ConvertViewModel(
    application: Application,
    eventBus: AppEventBus,
    private val playerManager: AudioPlayerManager
) : BaseViewModel(application, eventBus) {

    val currentPlayingAudio: StateFlow<com.example.audiocut.data.model.AudioItem?> = playerManager.currentAudio

    private val _convertInputFile = MutableStateFlow<String?>(null)
    val convertInputFile: StateFlow<String?> = _convertInputFile.asStateFlow()

    private val _convertState = MutableStateFlow(ConvertState())
    val convertState: StateFlow<ConvertState> = _convertState.asStateFlow()

    private val _isVideoInput = MutableStateFlow(false)
    /** 当前输入文件是否为视频（为 true 时界面显示"复制音频"选项） */
    val isVideoInput: StateFlow<Boolean> = _isVideoInput.asStateFlow()

    @Volatile
    private var lastProbedPath: String? = null

    @Volatile
    private var convertSessionId: Long = -1L
    @Volatile
    private var convertCancelled = false
    // 以下字段在 IO 协程与统计回调线程间共享，需保证可见性
    @Volatile
    private var convertTotalDurationMs = 0L
    @Volatile
    private var convertStartTime = 0L
    private var conversionPollJob: Job? = null

    init {
        // 订阅跳转格式转换事件
        viewModelScope.launch {
            eventBus.navigateToConvert.collect { event ->
                _convertInputFile.value = event.inputFile
                probeInputMedia(effectiveInputPath())
            }
        }

        // 未显式指定输入文件时，跟随当前播放的音频/视频探测输入类型
        viewModelScope.launch {
            currentPlayingAudio.collect { audio ->
                if (convertInputFile.value == null) {
                    probeInputMedia(audio?.filePath)
                }
            }
        }

        // 当前音频被【覆盖】后，若转换输入关联该音频则清空转换状态与输入，避免残留旧文件记录
        viewModelScope.launch {
            eventBus.audioOverwritten.collect { event ->
                val input = convertInputFile.value
                val currentPath = currentPlayingAudio.value?.filePath
                val related = input == event.filePath || (input == null && currentPath == event.filePath)
                if (related) {
                    conversionPollJob?.cancel()
                    convertCancelled = false
                    convertSessionId = -1L
                    if (input == event.filePath) _convertInputFile.value = null
                    _convertState.value = ConvertState()
                }
            }
        }
    }

    fun setConvertInputFile(path: String?) {
        _convertInputFile.value = path
        probeInputMedia(effectiveInputPath())
    }

    /** 当前生效的输入文件路径：优先显式指定，否则回退到当前播放 */
    private fun effectiveInputPath(): String? =
        convertInputFile.value ?: currentPlayingAudio.value?.filePath

    /** 探测当前输入是否为视频（ffprobe 判断视频流，失败时按扩展名兜底） */
    private fun probeInputMedia(path: String?) {
        if (path == null) {
            lastProbedPath = null
            _isVideoInput.value = false
            return
        }
        if (path == lastProbedPath) return
        lastProbedPath = path
        viewModelScope.launch(Dispatchers.IO) {
            _isVideoInput.value = isVideoFile(path)
        }
    }

    private fun isVideoFile(path: String): Boolean {
        val extFallback = path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        return try {
            val streams = FFprobeKit.getMediaInformation(path).mediaInformation?.streams.orEmpty()
            if (streams.isNotEmpty()) streams.any { it.type == "video" } else extFallback
        } catch (_: Exception) {
            extFallback
        }
    }

    companion object {
        /** 常见视频扩展名（ffprobe 探测失败时的兜底） */
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "avi", "webm", "3gp", "mov", "ts",
            "flv", "wmv", "mpg", "mpeg", "rmvb", "m2ts", "vob"
        )
    }

    /** 开始格式转换 */
    fun startConversion(quality: ConvertQuality, inputFilePath: String? = null, customFileName: String? = null) {
        val inputFile = inputFilePath ?: convertInputFile.value
            ?: currentPlayingAudio.value?.filePath
            ?: run {
                emitToast("没有输入文件")
                return
            }

        if (!File(inputFile).exists()) {
            emitToast("输入文件不存在")
            return
        }

        val outputDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val baseName = (customFileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "converted_${File(inputFile).nameWithoutExtension}")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        // 复制音频固定输出 m4a（-c:a copy 直拷原音频流），其余转 mp3
        val outputExt = if (quality == ConvertQuality.EXTRACT) "m4a" else "mp3"
        val outputFile = "${outputDir?.absolutePath}/$baseName.$outputExt"

        viewModelScope.launch(Dispatchers.Main) {
            _convertState.value = ConvertState(
                isConverting = true, progress = 0f, elapsedTimeMs = 0L,
                remainingTimeMs = 0L, inputFilePath = inputFile,
                outputFilePath = outputFile, quality = quality
            )
        }

        val startTime = System.currentTimeMillis()
        convertStartTime = startTime
        convertCancelled = false
        convertSessionId = -1L

        viewModelScope.launch(Dispatchers.IO) {
            convertTotalDurationMs = queryMediaDurationMs(inputFile)

            // 无法获取时长时用文件大小兜底估算
            if (convertTotalDurationMs <= 0L) {
                val fileSize = File(inputFile).length()
                conversionPollJob = launch(Dispatchers.IO) {
                    while (isActive && !convertCancelled) {
                        kotlinx.coroutines.delay(300.milliseconds)
                        if (!convertCancelled) updateConversionProgress(outputFile, fileSize, quality, startTime)
                    }
                }
            }

            // 统计回调：精准进度
            // 注意：本库（ffmpeg-kit 8.1.2）Statistics.time 的单位是【毫秒】
            // （见 FFmpegKitConfig.statistics 的 time 参数注释 "processed duration in milliseconds"），
            // 直接用毫秒除以总时长得到进度即可，切勿再乘 1000 —— 曾因误当秒处理导致进度条瞬间跳满、剩余时间失真
            val statisticsCallback = StatisticsCallback { stats ->
                val totalMs = convertTotalDurationMs
                val processedMs = stats?.time ?: 0.0
                if (totalMs <= 0L || processedMs <= 0.0) return@StatisticsCallback

                val progress = (processedMs / totalMs).toFloat().coerceIn(0f, 0.99f)
                val elapsed = System.currentTimeMillis() - convertStartTime
                val remaining = if (progress > 0.01f) ((elapsed / progress) * (1f - progress)).toLong() else 0L

                viewModelScope.launch(Dispatchers.Main) {
                    if (convertCancelled) return@launch
                    _convertState.value = _convertState.value.copy(
                        progress = progress, elapsedTimeMs = elapsed,
                        remainingTimeMs = remaining, speed = stats.speed.toFloat()
                    )
                }
            }

            // 完成回调
            val completeCallback = FFmpegSessionCompleteCallback { session ->
                conversionPollJob?.cancel()
                if (convertCancelled) {
                    File(outputFile).takeIf { it.exists() }?.delete()
                    return@FFmpegSessionCompleteCallback
                }

                val totalTime = System.currentTimeMillis() - convertStartTime
                viewModelScope.launch(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        _convertState.value = _convertState.value.copy(
                            isConverting = false, progress = 1f,
                            elapsedTimeMs = totalTime, remainingTimeMs = 0L, speed = 0f
                        )
                        emitToast("转换成功！")
                        eventBus.notifyAudioLibraryChanged()
                    } else {
                        _convertState.value = _convertState.value.copy(
                            isConverting = false, error = "FFmpeg 执行失败"
                        )
                        emitToast("转换失败")
                    }
                }
            }

            try {
                val command = buildFFmpegCommand(inputFile, outputFile, quality)
                val session = FFmpegKit.executeAsync(command, completeCallback, null, statisticsCallback)
                convertSessionId = session.sessionId
            } catch (e: Exception) {
                conversionPollJob?.cancel()
                if (!convertCancelled) {
                    viewModelScope.launch(Dispatchers.Main) {
                        _convertState.value = _convertState.value.copy(
                            isConverting = false, error = e.message ?: "转换失败"
                        )
                    }
                    emitToast("转换失败: ${e.message}")
                }
            }
        }
    }

    /** 取消转换 */
    fun cancelConversion() {
        convertCancelled = true
        conversionPollJob?.cancel()

        if (convertSessionId >= 0) FFmpegKit.cancel(convertSessionId)
        else FFmpegKit.cancel()

        viewModelScope.launch(Dispatchers.Main) {
            _convertState.value = _convertState.value.copy(
                isConverting = false, progress = 0f, error = "用户取消"
            )
        }
        emitToast("转换已取消")
    }

    /** 文件大小兜底估算进度 */
    private fun updateConversionProgress(
        outputFile: String, inputSize: Long, quality: ConvertQuality, startTime: Long
    ) {
        try {
            val outputSize = File(outputFile).takeIf { it.exists() }?.length() ?: 0L
            val elapsed = System.currentTimeMillis() - startTime

            val ratio = when (quality) {
                ConvertQuality.HIGH -> 0.5f
                ConvertQuality.LOW -> 0.15f
                // 复制音频：输出仅含音频流，约为原视频体积的一小部分
                ConvertQuality.EXTRACT -> 0.1f
            }
            val expectedSize = (inputSize * ratio).toLong().coerceAtLeast(1L)
            val progress = (outputSize.toFloat() / expectedSize).coerceIn(0f, 0.95f)
            val speed = if (elapsed > 0) outputSize.toFloat() / (elapsed / 1000f) else 0f
            val remaining = if (progress > 0.05f) ((elapsed / progress) * (1f - progress)).toLong() else 0L

            viewModelScope.launch(Dispatchers.Main) {
                _convertState.value = convertState.value.copy(
                    progress = progress, elapsedTimeMs = elapsed,
                    remainingTimeMs = remaining, speed = speed
                )
            }
        } catch (_: Exception) {}
    }

    /** 查询媒体时长 */
    private fun queryMediaDurationMs(path: String): Long {
        val ffprobeMs = try {
            FFprobeKit.getMediaInformation(path).mediaInformation
                ?.duration?.toDoubleOrNull()?.takeIf { it > 0 }
                ?.let { (it * 1000).toLong() }
        } catch (_: Exception) { null }
        if (ffprobeMs != null && ffprobeMs > 0L) return ffprobeMs

        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) { 0L }
    }

    /** 构建 FFmpeg 命令 */
    private fun buildFFmpegCommand(input: String, output: String, quality: ConvertQuality): String {
        return when (quality) {
            // 复制音频：-c:a copy 直接复制原音频流（不重新编码），速度最快
            ConvertQuality.EXTRACT ->
                "-hide_banner -stats -i \"$input\" -vn -c:a copy \"$output\""
            else -> {
                val q = if (quality == ConvertQuality.LOW) 7 else 2
                "-hide_banner -stats -i \"$input\" -vn -c:a libmp3lame -q:a $q -ar 44100 -ac 2 \"$output\""
            }
        }
    }

    fun getConvertedFile(): File? = _convertState.value.outputFilePath?.let { File(it) }

    /** 分享转换结果 */
    fun shareConvertedFile(context: Context) {
        val file = getConvertedFile() ?: run {
            emitToast("没有可分享的文件")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension.equals("m4a", ignoreCase = true)) "audio/mp4" else "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(Intent.createChooser(intent, "分享音频文件"))
    }

    /** 保存到音频库 */
    fun saveConvertedToLibrary() {
        val file = getConvertedFile() ?: run {
            emitToast("没有可保存的文件")
            return
        }
        if (!file.exists()) {
            emitToast("文件不存在")
            return
        }
        emitToast("已保存到音频库")
        eventBus.notifyAudioLibraryChanged()
    }
}
