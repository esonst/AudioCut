package com.example.audiocut.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.example.audiocut.core.AppEventBus
import com.example.audiocut.data.model.*
import com.example.audiocut.data.repository.PreferencesRepository
import com.example.audiocut.ffmpeg.AudioCutterConcatenator
import com.example.audiocut.ffmpeg.ExportAudioFormat
import com.example.audiocut.ffmpeg.ExportResult
import com.example.audiocut.player.AudioPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 裁剪界面 ViewModel
 * 负责裁剪区间管理、裁剪预览、导出/覆盖原文件
 *
 * 通过事件总线接收文稿界面创建的裁剪区间，通过事件总线接收停止预览指令
 */
class TrimViewModel(
    application: Application,
    eventBus: AppEventBus,
    private val audioCutter: AudioCutterConcatenator,
    private val prefs: PreferencesRepository,
    private val playerManager: AudioPlayerManager
) : BaseViewModel(application, eventBus) {

    // ==================== 播放器状态（透传） ====================
    val currentPlayingAudio: StateFlow<AudioItem?> = playerManager.currentAudio
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val currentPositionMs: StateFlow<Long> = playerManager.currentPositionMs
    val durationMs: StateFlow<Long> = playerManager.durationMs

    // 裁剪预览播放器（独立于主播放器）
    private val trimPreviewPlayer = AudioPlayerManager(application)

    // ==================== 裁剪区间列表 ====================
    private val _trimRanges = MutableStateFlow<List<AudioSegment>>(emptyList())
    val trimRanges: StateFlow<List<AudioSegment>> = _trimRanges.asStateFlow()

    // ==================== 裁剪预览状态 ====================
    private val _isGeneratingTrimPreview = MutableStateFlow(false)
    val isGeneratingTrimPreview: StateFlow<Boolean> = _isGeneratingTrimPreview.asStateFlow()

    private val _trimPreviewProgress = MutableStateFlow(0f)
    val trimPreviewProgress: StateFlow<Float> = _trimPreviewProgress.asStateFlow()

    private val _trimPreviewResult = MutableStateFlow<ExportResult?>(null)
    val trimPreviewResult: StateFlow<ExportResult?> = _trimPreviewResult.asStateFlow()

    // 裁剪预览播放器状态（复用合并预览的命名，UI 层已绑定这些字段）
    val isMergedPreviewPlaying: StateFlow<Boolean> = trimPreviewPlayer.isPlaying
    val mergedPreviewPositionMs: StateFlow<Long> = trimPreviewPlayer.currentPositionMs
    val mergedPreviewDurationMs: StateFlow<Long> = trimPreviewPlayer.durationMs

    // ==================== 裁剪导出状态 ====================
    private val _isTrimExporting = MutableStateFlow(false)
    val isTrimExporting: StateFlow<Boolean> = _isTrimExporting.asStateFlow()

    private val _trimExportProgress = MutableStateFlow(0f)
    val trimExportProgress: StateFlow<Float> = _trimExportProgress.asStateFlow()

    // 单区间试听状态
    private val _previewingSegmentId = MutableStateFlow<String?>(null)
    val previewingSegmentId: StateFlow<String?> = _previewingSegmentId.asStateFlow()
    private var previewJob: kotlinx.coroutines.Job? = null

    init {
        // 切换音频时加载裁剪区间
        viewModelScope.launch {
            currentPlayingAudio.collect { audio ->
                _trimRanges.value = audio?.let { prefs.getTrimRangesForAudio(it.id) } ?: emptyList()
                _trimPreviewResult.value = null
            }
        }
        // 区间变化自动保存 + 预览失效
        viewModelScope.launch {
            trimRanges.collect { list ->
                currentPlayingAudio.value?.id?.let { prefs.saveTrimRangesForAudio(it, list) }
                _trimPreviewResult.value = null
            }
        }
        // 订阅文稿界面创建的裁剪区间事件
        viewModelScope.launch {
            eventBus.trimCreated.collect { trim ->
                addTrimRange(trim)
            }
        }
        // 订阅全局停止预览事件
        viewModelScope.launch {
            eventBus.stopAllPreview.collect {
                stopAllPreview()
            }
        }
        // 音频被覆盖后（来自剪辑页等），若正是当前音频则清空裁剪区间与预览状态
        viewModelScope.launch {
            eventBus.audioOverwritten.collect { event ->
                if (currentPlayingAudio.value?.id == event.audioId) {
                    previewJob?.cancel()
                    previewJob = null
                    _previewingSegmentId.value = null
                    trimPreviewPlayer.pause()
                    _trimRanges.value = emptyList()
                    _trimPreviewResult.value = null
                }
            }
        }
    }

    /** 添加裁剪区间（供事件总线调用） */
    private fun addTrimRange(trim: AudioSegment) {
        val newTrim = trim.copy(colorIndex = _trimRanges.value.size % 5)
        _trimRanges.value += newTrim
    }

    /** 当前位置手动创建裁剪区间 */
    fun createManualTrimAtCurrentPos() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }
        val pos = currentPositionMs.value
        val duration = durationMs.value.coerceAtLeast(audio.durationMs)
        val startMs = pos.coerceIn(0L, maxOf(0L, duration - 1000L))
        val endMs = (startMs + 5000L).coerceAtMost(duration)

        _trimRanges.value += AudioSegment(
            audioId = audio.id,
            title = "裁剪段 ${_trimRanges.value.size + 1}",
            startMs = startMs,
            endMs = endMs,
            colorIndex = _trimRanges.value.size % 5
        )
        emitToast("已在当前位置创建裁剪卡片")
    }

    fun updateTrimRange(trimId: String, newStartMs: Long, newEndMs: Long) {
        _trimRanges.value = _trimRanges.value.map {
            if (it.id == trimId) it.copy(
                startMs = newStartMs.coerceAtLeast(0L),
                endMs = newEndMs.coerceAtLeast(newStartMs + 100L)
            ) else it
        }
    }

    fun renameTrimRange(trimId: String, newTitle: String) {
        _trimRanges.value = _trimRanges.value.map {
            if (it.id == trimId) it.copy(title = newTitle) else it
        }
    }

    fun toggleTrimSelected(trimId: String) {
        _trimRanges.value = _trimRanges.value.map {
            if (it.id == trimId) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun deleteTrimRange(trimId: String) {
        if (_previewingSegmentId.value == trimId) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }
        _trimRanges.value = _trimRanges.value.filter { it.id != trimId }
        emitToast("裁剪卡片已删除")
    }

    /** 单裁剪区间试听 */
    fun previewTrimRange(trim: AudioSegment) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }

        if (_previewingSegmentId.value == trim.id && isPlaying.value) {
            playerManager.pause()
            previewJob?.cancel()
            previewJob = null
            _previewingSegmentId.value = null
            return
        }

        trimPreviewPlayer.pause()
        if (playerManager.currentAudio.value?.id != audio.id) {
            playerManager.playAudio(audio, emptyList())
        }

        _previewingSegmentId.value = trim.id
        playerManager.seekTo(trim.startMs)
        playerManager.play()

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(100.milliseconds)
            while (isActive && _previewingSegmentId.value == trim.id) {
                val current = _trimRanges.value.find { it.id == trim.id }
                if (current == null) {
                    playerManager.pause()
                    _previewingSegmentId.value = null
                    break
                }
                val pos = playerManager.peekPositionMs()
                if (pos >= current.endMs - 30L || pos < current.startMs - 500L) {
                    playerManager.pause()
                    if (pos >= current.endMs - 30L) {
                        playerManager.seekTo(current.startMs)
                    }
                    _previewingSegmentId.value = null
                    break
                }
                kotlinx.coroutines.delay(20.milliseconds)
            }
        }
    }

    /** 计算裁剪后保留的区间（取补集） */
    private fun computeKeepSegments(): List<AudioSegment> {
        val audio = currentPlayingAudio.value ?: return emptyList()
        val totalMs = maxOf(durationMs.value, audio.durationMs)
        val cuts = trimRanges.value
            .filter { it.isSelected && it.endMs > it.startMs }
            .sortedBy { it.startMs }

        // 合并重叠区间
        val merged = mutableListOf<Pair<Long, Long>>()
        for (cut in cuts) {
            val last = merged.lastOrNull()
            if (last != null && cut.startMs <= last.second) {
                merged[merged.size - 1] = last.first to maxOf(last.second, cut.endMs)
            } else {
                merged.add(cut.startMs to cut.endMs)
            }
        }

        // 取补集
        val keeps = mutableListOf<AudioSegment>()
        var cursor = 0L
        for ((s, e) in merged) {
            if (s > cursor) keeps.add(AudioSegment(audioId = audio.id, title = "保留段", startMs = cursor, endMs = s))
            cursor = maxOf(cursor, e)
        }
        if (cursor < totalMs) keeps.add(AudioSegment(audioId = audio.id, title = "保留段", startMs = cursor, endMs = totalMs))

        return keeps.filter { it.endMs - it.startMs >= 100L }
    }

    /** 生成/播放裁剪预览 */
    fun startOrToggleTrimPreview() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频")
            return
        }

        if (isPlaying.value || _previewingSegmentId.value != null) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }

        val preview = _trimPreviewResult.value
        if (preview != null && preview.isSuccess && File(preview.outputPath).exists()) {
            trimPreviewPlayer.togglePlayPause()
            return
        }

        viewModelScope.launch {
            _isGeneratingTrimPreview.value = true
            _trimPreviewProgress.value = 0f
            trimPreviewPlayer.pause()

            val keepSegments = computeKeepSegments()
            if (keepSegments.isEmpty()) {
                _isGeneratingTrimPreview.value = false
                emitToast("裁剪后无剩余内容，请调整裁剪区间")
                return@launch
            }

            val result = audioCutter.generateFastPreview(audio, keepSegments) { _trimPreviewProgress.value = it }
            _isGeneratingTrimPreview.value = false
            _trimPreviewProgress.value = if (result.isSuccess) 1f else 0f
            _trimPreviewResult.value = result

            if (result.isSuccess && File(result.outputPath).exists()) {
                val item = AudioItem(
                    id = 999999L,
                    title = "裁剪预览: ${audio.title}",
                    artist = audio.artist,
                    durationMs = result.durationMs,
                    sizeBytes = File(result.outputPath).length(),
                    filePath = result.outputPath,
                    dateModifiedSec = System.currentTimeMillis() / 1000
                )
                trimPreviewPlayer.playAudio(item)
                emitToast("裁剪预览就绪")
            } else {
                emitToast("生成裁剪预览失败: ${result.errorMessage}")
            }
        }
    }

    fun closeTrimPreview() {
        trimPreviewPlayer.pause()
        _trimPreviewResult.value = null
    }

    /** 重命名当前裁剪预览文件（缓存目录内），供预览卡片铅笔按钮调用 */
    fun renamePreviewFile(newBaseName: String) {
        val preview = _trimPreviewResult.value ?: return
        if (!preview.isSuccess) return
        val oldFile = File(preview.outputPath)
        if (!oldFile.exists()) return
        var cleanName = newBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (cleanName.endsWith(".${oldFile.extension}", ignoreCase = true)) {
            cleanName = cleanName.dropLast(oldFile.extension.length + 1)
        }
        if (cleanName.isBlank()) return
        val newFile = File(oldFile.parentFile ?: return, "$cleanName.${oldFile.extension}")
        if (newFile == oldFile) return
        if (oldFile.renameTo(newFile)) {
            _trimPreviewResult.value = preview.copy(outputPath = newFile.absolutePath)
            emitToast("预览文件已重命名为 ${newFile.name}")
        } else {
            emitToast("重命名失败")
        }
    }

    /** 将裁剪预览保存到指定位置（SAF Uri）或默认音频目录；视频输入时保存带画面视频 */
    fun saveTrimPreviewAs(customName: String, targetUri: Uri? = null) {
        val preview = _trimPreviewResult.value ?: return
        if (!preview.isSuccess) return
        val source = File(preview.outputPath)
        if (!source.exists()) return
        val audio = currentPlayingAudio.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isVideo = audio != null && audioCutter.isVideoSource(audio)
                if (isVideo) {
                    val keepSegments = computeKeepSegments()
                    if (keepSegments.isEmpty()) {
                        withContext(Dispatchers.Main) { emitToast("裁剪后无剩余内容，请调整裁剪区间") }
                        return@launch
                    }
                    if (targetUri != null) {
                        val tempVideo = File(
                            getApplication<Application>().cacheDir,
                            "video_save_${System.currentTimeMillis()}.mp4"
                        )
                        val result = audioCutter.exportVideoWithSegments(audio, keepSegments, tempVideo)
                        if (!result.isSuccess) {
                            withContext(Dispatchers.Main) { emitToast("保存失败: ${result.errorMessage}") }
                            return@launch
                        }
                        getApplication<Application>().contentResolver.openOutputStream(targetUri)?.use { out ->
                            tempVideo.inputStream().use { it.copyTo(out) }
                        }
                        tempVideo.delete()
                        withContext(Dispatchers.Main) { emitToast("已保存至所选位置") }
                    } else {
                        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        val name = customName.ifBlank { "Trim_${System.currentTimeMillis()}" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val target = File(dir, "$name.mp4")
                        val result = audioCutter.exportVideoWithSegments(audio, keepSegments, target)
                        if (!result.isSuccess) {
                            withContext(Dispatchers.Main) { emitToast("保存失败: ${result.errorMessage}") }
                            return@launch
                        }
                        withContext(Dispatchers.Main) { emitToast("已保存至: ${target.absolutePath}") }
                    }
                } else {
                    if (targetUri != null) {
                        getApplication<Application>().contentResolver.openOutputStream(targetUri)?.use { out ->
                            source.inputStream().use { it.copyTo(out) }
                        }
                        withContext(Dispatchers.Main) { emitToast("已保存至所选位置") }
                    } else {
                        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        val name = customName.ifBlank { "Trim_${System.currentTimeMillis()}" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val target = File(dir, "$name.${source.extension}")
                        source.copyTo(target, overwrite = true)
                        withContext(Dispatchers.Main) { emitToast("已保存至: ${target.absolutePath}") }
                    }
                }
                withContext(Dispatchers.Main) { eventBus.notifyAudioLibraryChanged() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { emitToast("保存失败: ${e.message}") }
            }
        }
    }

    /** 读取本地音频文件时长（毫秒） */
    private fun queryAudioDurationMs(path: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            0L
        }
    }

    // 预览播放器控制（UI 层调用）
    fun seekMergedPreview(posMs: Long) = trimPreviewPlayer.seekTo(posMs)
    fun rewindMergedPreview(seconds: Int) = trimPreviewPlayer.fastForwardOrRewind(seconds)

    private fun resolveSourceFormat(audio: AudioItem): ExportAudioFormat {
        return when (File(audio.filePath).extension.lowercase()) {
            "mp3" -> ExportAudioFormat.MP3
            "wav" -> ExportAudioFormat.WAV
            else -> ExportAudioFormat.M4A
        }
    }

    private suspend fun performTrimExport(
        audio: AudioItem, keepSegments: List<AudioSegment>,
        fileName: String, format: ExportAudioFormat
    ): ExportResult {
        return audioCutter.exportSegments(audio, keepSegments, fileName, format) {
            _trimExportProgress.value = it
        }
    }

    /** 裁剪另存为 */
    fun saveTrimAs(customName: String) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频可导出")
            return
        }
        val keepSegments = computeKeepSegments()
        if (keepSegments.isEmpty()) {
            emitToast("裁剪后无剩余内容，请调整裁剪区间")
            return
        }

        val fileName = customName.ifBlank { "Trim_${System.currentTimeMillis()}" }
        viewModelScope.launch {
            _isTrimExporting.value = true
            _trimExportProgress.value = 0f
            try {
                val result = performTrimExport(audio, keepSegments, fileName, resolveSourceFormat(audio))
                emitToast(if (result.isSuccess) "另存成功：${result.outputPath}" else "另存失败: ${result.errorMessage}")
                if (result.isSuccess) eventBus.notifyAudioLibraryChanged()
            } catch (e: Exception) {
                emitToast("另存异常: ${e.localizedMessage}")
            } finally {
                _isTrimExporting.value = false
            }
        }
    }

    /** 裁剪并分享 */
    fun exportTrimAndShare() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频可导出")
            return
        }

        // 优先复用预览文件（视频输入时预览仅含音频，需重新导出带画面视频）
        val preview = _trimPreviewResult.value
        if (preview != null && preview.isSuccess && File(preview.outputPath).exists()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (audioCutter.isVideoSource(audio)) {
                        val keepSegments = computeKeepSegments()
                        if (keepSegments.isEmpty()) {
                            withContext(Dispatchers.Main) { emitToast("裁剪后无剩余内容，请调整裁剪区间") }
                            return@launch
                        }
                        val videoFile = File(
                            getApplication<Application>().cacheDir,
                            "share_video_${System.currentTimeMillis()}.mp4"
                        )
                        val result = audioCutter.exportVideoWithSegments(audio, keepSegments, videoFile)
                        if (!result.isSuccess) {
                            videoFile.delete()
                            withContext(Dispatchers.Main) { emitToast("分享失败: ${result.errorMessage}") }
                            return@launch
                        }
                        withContext(Dispatchers.Main) { shareAudioFile(videoFile.absolutePath, isVideo = true) }
                    } else {
                        shareAudioFile(preview.outputPath, isVideo = false)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { emitToast("分享失败: ${e.localizedMessage}") }
                }
            }
            return
        }

        val keepSegments = computeKeepSegments()
        if (keepSegments.isEmpty()) {
            emitToast("裁剪后无剩余内容，请调整裁剪区间")
            return
        }

        viewModelScope.launch {
            _isTrimExporting.value = true
            _trimExportProgress.value = 0f
            try {
                val result = performTrimExport(
                    audio, keepSegments,
                    "Trim_${System.currentTimeMillis()}", resolveSourceFormat(audio)
                )
                if (result.isSuccess && File(result.outputPath).exists()) {
                    shareAudioFile(result.outputPath, isVideo = result.outputIsVideo)
                } else {
                    emitToast("导出失败: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                emitToast("导出异常: ${e.localizedMessage}")
            } finally {
                _isTrimExporting.value = false
            }
        }
    }

    /** 裁剪并覆盖原文件 */
    fun overwriteOriginalWithTrim() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频")
            return
        }
        val keepSegments = computeKeepSegments()
        if (keepSegments.isEmpty()) {
            emitToast("裁剪后无剩余内容，请调整裁剪区间")
            return
        }

        viewModelScope.launch {
            _isTrimExporting.value = true
            _trimExportProgress.value = 0f
            try {
                withContext(Dispatchers.Main) {
                    stopAllPreview()
                    playerManager.pause()
                }

                val format = resolveSourceFormat(audio)
                val result = performTrimExport(
                    audio, keepSegments,
                    "Trim_Overwrite${System.currentTimeMillis()}", format
                )

                if (!result.isSuccess) {
                    emitToast("裁剪导出失败: ${result.errorMessage}")
                    return@launch
                }

                // 文件级 IO（覆盖写入、读时长、读大小）放到 IO 线程，避免大文件阻塞主线程
                val duration = withContext(Dispatchers.IO) {
                    val sourceFile = File(audio.filePath)
                    val newFile = File(result.outputPath)
                    if (!sourceFile.exists() || !sourceFile.isFile) {
                        null
                    } else {
                        newFile.copyTo(sourceFile, overwrite = true)
                        newFile.delete()
                        queryAudioDurationMs(sourceFile.absolutePath).coerceAtLeast(result.durationMs)
                    }
                }
                if (duration == null) {
                    emitToast("原文件不可直接覆盖，请使用【保存】")
                    return@launch
                }

                // 刷新当前音频元信息（时长/大小/修改时间）
                val updated = audio.copy(
                    durationMs = duration,
                    sizeBytes = File(audio.filePath).length(),
                    dateModifiedSec = System.currentTimeMillis() / 1000
                )
                playerManager.updateCurrentAudioMetadata(updated)

                // 清除关联数据：文稿、剪辑片段、裁剪片段、转换格式记录、收藏等
                prefs.deleteAudioData(audio.id)
                _trimRanges.value = emptyList()
                _trimPreviewResult.value = null

                eventBus.notifyAudioOverwritten(audio.id, audio.filePath)
                eventBus.notifyAudioLibraryChanged()
                emitToast("已覆盖原文件：${audio.title}")
            } catch (e: Exception) {
                emitToast("覆盖原文件失败: ${e.localizedMessage}")
            } finally {
                _isTrimExporting.value = false
            }
        }
    }

    private fun shareAudioFile(filePath: String, isVideo: Boolean = false) {
        try {
            val context = getApplication<Application>()
            val file = File(filePath)
            if (!file.exists()) return

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/*" else "audio/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "分享裁剪${if (isVideo) "视频" else "音频"}").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            emitToast("分享失败: ${e.localizedMessage}")
        }
    }

    /** 停止所有预览 */
    fun stopAllPreview() {
        previewJob?.cancel()
        previewJob = null
        _previewingSegmentId.value = null
        trimPreviewPlayer.pause()
    }

    override fun onCleared() {
        trimPreviewPlayer.release()
        super.onCleared()
    }
}
