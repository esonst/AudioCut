package com.example.mp3player.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.*
import com.example.mp3player.core.AppEventBus
import com.example.mp3player.data.model.ConvertQuality
import com.example.mp3player.data.model.ConvertState
import com.example.mp3player.player.AudioPlayerManager
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

    val currentPlayingAudio: StateFlow<com.example.mp3player.data.model.AudioItem?> = playerManager.currentAudio

    private val _convertInputFile = MutableStateFlow<String?>(null)
    val convertInputFile: StateFlow<String?> = _convertInputFile.asStateFlow()

    private val _convertState = MutableStateFlow(ConvertState())
    val convertState: StateFlow<ConvertState> = _convertState.asStateFlow()

    @Volatile
    private var convertSessionId: Long = -1L
    @Volatile
    private var convertCancelled = false
    private var convertTotalDurationMs = 0L
    private var convertStartTime = 0L
    private var conversionPollJob: Job? = null

    init {
        // 订阅跳转格式转换事件
        viewModelScope.launch {
            eventBus.navigateToConvert.collect { event ->
                _convertInputFile.value = event.inputFile
            }
        }
    }

    fun setConvertInputFile(path: String?) {
        _convertInputFile.value = path
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
        val outputFile = "${outputDir?.absolutePath}/$baseName.mp3"

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

            val ratio = if (quality == ConvertQuality.HIGH) 0.5f else 0.15f
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
        val q = if (quality == ConvertQuality.LOW) 7 else 2
        return "-hide_banner -stats -i \"$input\" -vn -c:a libmp3lame -q:a $q -ar 44100 -ac 2 \"$output\""
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
            type = "audio/mpeg"
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
