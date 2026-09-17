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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 剪辑界面 ViewModel
 * 负责片段列表管理、单片段预览、合并预览、拼接导出
 *
 * 通过事件总线接收文稿界面创建的片段，通过事件总线接收停止预览指令
 */
class ClipViewModel(
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

    // 合并预览播放器（独立于主播放器，不显示通知栏）
    val mergedPreviewPlayer = AudioPlayerManager(application)

    // ==================== 片段列表 ====================
    private val _segments = MutableStateFlow<List<AudioSegment>>(emptyList())
    val segments: StateFlow<List<AudioSegment>> = _segments.asStateFlow()

    // 单片段预览状态
    private val _previewingSegmentId = MutableStateFlow<String?>(null)
    val previewingSegmentId: StateFlow<String?> = _previewingSegmentId.asStateFlow()
    private var previewJob: kotlinx.coroutines.Job? = null

    // ==================== 合并预览状态 ====================
    private val _isGeneratingMergedPreview = MutableStateFlow(false)
    val isGeneratingMergedPreview: StateFlow<Boolean> = _isGeneratingMergedPreview.asStateFlow()

    private val _mergedPreviewProgress = MutableStateFlow(0f)
    val mergedPreviewProgress: StateFlow<Float> = _mergedPreviewProgress.asStateFlow()

    private val _mergedPreviewResult = MutableStateFlow<ExportResult?>(null)
    val mergedPreviewResult: StateFlow<ExportResult?> = _mergedPreviewResult.asStateFlow()

    val isMergedPreviewPlaying: StateFlow<Boolean> = mergedPreviewPlayer.isPlaying
    val mergedPreviewPositionMs: StateFlow<Long> = mergedPreviewPlayer.currentPositionMs
    val mergedPreviewDurationMs: StateFlow<Long> = mergedPreviewPlayer.durationMs
    val mergedPreviewSpeed: StateFlow<Float> = mergedPreviewPlayer.playbackSpeed

    // ==================== 导出状态 ====================
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    init {
        // 切换音频时加载对应片段
        viewModelScope.launch {
            currentPlayingAudio.collect { audio ->
                _segments.value = audio?.let { prefs.getSegmentsForAudio(it.id) } ?: emptyList()
                _mergedPreviewResult.value = null
            }
        }
        // 片段变化自动保存 + 预览失效
        viewModelScope.launch {
            segments.collect { list ->
                currentPlayingAudio.value?.id?.let { prefs.saveSegmentsForAudio(it, list) }
                _mergedPreviewResult.value = null
            }
        }
        // 订阅文稿界面创建的片段事件
        viewModelScope.launch {
            eventBus.segmentCreated.collect { segment ->
                addSegment(segment)
            }
        }
        // 订阅全局停止预览事件
        viewModelScope.launch {
            eventBus.stopAllPreview.collect {
                stopAllPreview()
            }
        }
        // 音频被覆盖后（来自裁剪页等），若正是当前音频则清空片段与预览状态
        viewModelScope.launch {
            eventBus.audioOverwritten.collect { event ->
                if (currentPlayingAudio.value?.id == event.audioId) {
                    previewJob?.cancel()
                    previewJob = null
                    _previewingSegmentId.value = null
                    mergedPreviewPlayer.pause()
                    _segments.value = emptyList()
                    _mergedPreviewResult.value = null
                }
            }
        }
    }

    /** 添加片段（供事件总线调用） */
    private fun addSegment(segment: AudioSegment) {
        val newSeg = segment.copy(colorIndex = _segments.value.size % 5)
        _segments.value += newSeg
    }

    /** 复制片段 */
    fun copySegment(segmentId: String) {
        val original = _segments.value.find { it.id == segmentId } ?: return
        val index = _segments.value.indexOf(original)
        val copy = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            title = "${original.title} (副本)"
        )
        val newList = _segments.value.toMutableList()
        newList.add(index + 1, copy)
        _segments.value = newList
        emitToast("已复制片段")
    }

    /** 拖拽排序 */
    fun reorderSegments(fromIndex: Int, toIndex: Int) {
        val newList = _segments.value.toMutableList()
        if (fromIndex !in newList.indices || toIndex !in newList.indices) return
        val item = newList.removeAt(fromIndex)
        newList.add(toIndex, item)
        _segments.value = newList
    }

    /** 单片段试听 */
    fun previewSegment(segment: AudioSegment) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }

        if (_previewingSegmentId.value == segment.id && isPlaying.value) {
            playerManager.pause()
            previewJob?.cancel()
            previewJob = null
            _previewingSegmentId.value = null
            return
        }

        mergedPreviewPlayer.pause()
        if (playerManager.currentAudio.value?.id != audio.id) {
            playerManager.playAudio(audio, emptyList())
        }

        _previewingSegmentId.value = segment.id
        playerManager.seekTo(segment.startMs)
        playerManager.play()

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(100.milliseconds)
            while (isActive && _previewingSegmentId.value == segment.id) {
                val currentSeg = _segments.value.find { it.id == segment.id }
                if (currentSeg == null) {
                    playerManager.pause()
                    _previewingSegmentId.value = null
                    break
                }
                val pos = playerManager.peekPositionMs()
                if (pos >= currentSeg.endMs - 30L || pos < currentSeg.startMs - 500L) {
                    playerManager.pause()
                    if (pos >= currentSeg.endMs - 30L) {
                        playerManager.seekTo(currentSeg.startMs)
                    }
                    _previewingSegmentId.value = null
                    break
                }
                kotlinx.coroutines.delay(20.milliseconds)
            }
        }
    }

    /** 当前位置手动创建片段 */
    fun createManualSegmentAtCurrentPos() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }
        val pos = currentPositionMs.value
        val duration = durationMs.value.coerceAtLeast(audio.durationMs)
        val startMs = pos.coerceIn(0L, maxOf(0L, duration - 1000L))
        val endMs = (startMs + 5000L).coerceAtMost(duration)

        _segments.value += AudioSegment(
            audioId = audio.id,
            title = "手动片段 ${segments.value.size + 1}",
            startMs = startMs,
            endMs = endMs,
            colorIndex = _segments.value.size % 5
        )
        emitToast("已在当前位置创建片段")
    }

    fun updateSegmentRange(segmentId: String, newStartMs: Long, newEndMs: Long) {
        _segments.value = _segments.value.map {
            if (it.id == segmentId) it.copy(
                startMs = newStartMs.coerceAtLeast(0L),
                endMs = newEndMs.coerceAtLeast(newStartMs + 100L)
            ) else it
        }
    }

    fun renameSegment(segmentId: String, newTitle: String) {
        _segments.value = _segments.value.map {
            if (it.id == segmentId) it.copy(title = newTitle) else it
        }
    }

    fun toggleSegmentSelected(segmentId: String) {
        _segments.value = _segments.value.map {
            if (it.id == segmentId) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun deleteSegment(segmentId: String) {
        if (_previewingSegmentId.value == segmentId) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }
        _segments.value = _segments.value.filter { it.id != segmentId }
        emitToast("片段已删除")
    }

    /** 生成/播放合并预览 */
    fun startOrToggleMergedPreview() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频")
            return
        }
        val selected = _segments.value.filter { it.isSelected && it.endMs > it.startMs }
        if (selected.isEmpty()) {
            emitToast("请至少勾选一个有效片段进行预览")
            return
        }

        if (isPlaying.value || _previewingSegmentId.value != null) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }

        val preview = _mergedPreviewResult.value
        if (preview != null && preview.isSuccess && File(preview.outputPath).exists()) {
            mergedPreviewPlayer.togglePlayPause()
            return
        }

        viewModelScope.launch {
            _isGeneratingMergedPreview.value = true
            _mergedPreviewProgress.value = 0f
            mergedPreviewPlayer.pause()

            val result = audioCutter.generateFastPreview(audio, selected) { _mergedPreviewProgress.value = it }
            _isGeneratingMergedPreview.value = false
            _mergedPreviewProgress.value = if (result.isSuccess) 1f else 0f
            _mergedPreviewResult.value = result

            if (result.isSuccess && File(result.outputPath).exists()) {
                val item = AudioItem(
                    id = 888888L,
                    title = "预览: ${audio.title}",
                    artist = audio.artist,
                    durationMs = result.durationMs,
                    sizeBytes = File(result.outputPath).length(),
                    filePath = result.outputPath,
                    dateModifiedSec = System.currentTimeMillis() / 1000
                )
                mergedPreviewPlayer.playAudio(item)
                emitToast("预览就绪")
            } else {
                emitToast("生成预览失败: ${result.errorMessage}")
            }
        }
    }

    /** 保存预览到音乐库（视频输入时重新导出带画面视频，预览文件仅含音频） */
    fun saveMergedPreviewToLibrary(customName: String? = null) {
        val preview = mergedPreviewResult.value ?: return
        if (!preview.isSuccess) return
        val source = File(preview.outputPath)
        if (!source.exists()) return
        val audio = currentPlayingAudio.value

        viewModelScope.launch(Dispatchers.IO) {
            _isExporting.value = true
            _exportProgress.value = 0f
            try {
                val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val fileName = customName?.ifBlank { null } ?: "Clip${System.currentTimeMillis()}"
                val target: File
                if (audio != null && audioCutter.isVideoSource(audio)) {
                    val selected = _segments.value.filter { it.isSelected && it.endMs > it.startMs }
                    if (selected.isEmpty()) {
                        withContext(Dispatchers.Main) { emitToast("请先勾选有效片段") }
                        return@launch
                    }
                    target = File(dir, "$fileName.mp4")
                    val result = audioCutter.exportVideoWithSegments(audio, selected, target) { _exportProgress.value = it }
                    if (!result.isSuccess) {
                        withContext(Dispatchers.Main) { emitToast("保存失败: ${result.errorMessage}") }
                        return@launch
                    }
                } else {
                    target = File(dir, "${fileName}.${source.extension}")
                    source.copyTo(target, overwrite = true)
                    _exportProgress.value = 1f
                }
                withContext(Dispatchers.Main) {
                    emitToast("已保存至: ${target.absolutePath}")
                    eventBus.notifyAudioLibraryChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    emitToast("保存失败: ${e.message}")
                }
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun playMergedPreview() = mergedPreviewPlayer.play()
    fun pauseMergedPreview() = mergedPreviewPlayer.pause()
    fun toggleMergedPreviewPlayPause() = mergedPreviewPlayer.togglePlayPause()
    fun seekMergedPreview(posMs: Long) = mergedPreviewPlayer.seekTo(posMs)
    fun rewindMergedPreview(seconds: Int) = mergedPreviewPlayer.fastForwardOrRewind(seconds)
    fun setMergedPreviewSpeed(speed: Float) = mergedPreviewPlayer.setPlaybackSpeed(speed)

    fun closeMergedPreview() {
        mergedPreviewPlayer.pause()
        _mergedPreviewResult.value = null
    }

    /** 重命名当前合并预览文件（缓存目录内），供预览卡片铅笔按钮调用 */
    fun renamePreviewFile(newBaseName: String) {
        val preview = _mergedPreviewResult.value ?: return
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
            _mergedPreviewResult.value = preview.copy(outputPath = newFile.absolutePath)
            emitToast("预览文件已重命名为 ${newFile.name}")
        } else {
            emitToast("重命名失败")
        }
    }

    /** 将合并预览保存到指定位置（SAF Uri）或默认音频目录；视频输入时保存带画面视频 */
    fun savePreviewToLocation(customName: String, targetUri: Uri? = null) {
        val preview = _mergedPreviewResult.value ?: return
        if (!preview.isSuccess) return
        val source = File(preview.outputPath)
        if (!source.exists()) return
        val audio = currentPlayingAudio.value

        viewModelScope.launch(Dispatchers.IO) {
            _isExporting.value = true
            _exportProgress.value = 0f
            try {
                val isVideo = audio != null && audioCutter.isVideoSource(audio)
                if (isVideo) {
                    val selected = _segments.value.filter { it.isSelected && it.endMs > it.startMs }
                    if (selected.isEmpty()) {
                        withContext(Dispatchers.Main) { emitToast("请先勾选有效片段") }
                        return@launch
                    }
                    if (targetUri != null) {
                        val tempVideo = File(
                            getApplication<Application>().cacheDir,
                            "video_save_${System.currentTimeMillis()}.mp4"
                        )
                        val result = audioCutter.exportVideoWithSegments(audio, selected, tempVideo) { _exportProgress.value = it }
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
                        val name = customName.ifBlank { "Clip_${System.currentTimeMillis()}" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val target = File(dir, "$name.mp4")
                        val result = audioCutter.exportVideoWithSegments(audio, selected, target) { _exportProgress.value = it }
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
                        _exportProgress.value = 1f
                        withContext(Dispatchers.Main) { emitToast("已保存至所选位置") }
                    } else {
                        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        val name = customName.ifBlank { "Clip_${System.currentTimeMillis()}" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val target = File(dir, "$name.${source.extension}")
                        source.copyTo(target, overwrite = true)
                        _exportProgress.value = 1f
                        withContext(Dispatchers.Main) { emitToast("已保存至: ${target.absolutePath}") }
                    }
                }
                withContext(Dispatchers.Main) { eventBus.notifyAudioLibraryChanged() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { emitToast("保存失败: ${e.message}") }
            } finally {
                _isExporting.value = false
            }
        }
    }

    /** 分享合并预览文件；视频输入时先导出带画面视频再分享 */
    fun shareMergedPreview() {
        val preview = _mergedPreviewResult.value ?: run {
            emitToast("当前没有可分享的预览")
            return
        }
        if (!preview.isSuccess) return
        val source = File(preview.outputPath)
        if (!source.exists()) {
            emitToast("预览文件不存在")
            return
        }
        val audio = currentPlayingAudio.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val shareFile: File
                if (audio != null && audioCutter.isVideoSource(audio)) {
                    val selected = _segments.value.filter { it.isSelected && it.endMs > it.startMs }
                    if (selected.isEmpty()) {
                        withContext(Dispatchers.Main) { emitToast("请先勾选有效片段") }
                        return@launch
                    }
                    shareFile = File(
                        getApplication<Application>().cacheDir,
                        "share_video_${System.currentTimeMillis()}.mp4"
                    )
                    val result = audioCutter.exportVideoWithSegments(audio, selected, shareFile)
                    if (!result.isSuccess) {
                        shareFile.delete()
                        withContext(Dispatchers.Main) { emitToast("分享失败: ${result.errorMessage}") }
                        return@launch
                    }
                } else {
                    shareFile = source
                }
                withContext(Dispatchers.Main) { doShareMergedPreview(shareFile) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { emitToast("分享失败: ${e.localizedMessage}") }
            }
        }
    }

    private fun doShareMergedPreview(file: File) {
        try {
            val context = getApplication<Application>()
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val isVideo = file.extension.equals("mp4", ignoreCase = true)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/*" else "audio/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "分享剪辑${if (isVideo) "视频" else "音频"}").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            emitToast("分享失败: ${e.localizedMessage}")
        }
    }

    /** 覆盖当前文件：以合并预览结果覆盖原文件，刷新元信息并清除该音频的文稿/片段/裁剪等关联数据 */
    fun overwriteOriginalWithMerged() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频")
            return
        }
        val preview = _mergedPreviewResult.value
        if (preview == null || !preview.isSuccess || !File(preview.outputPath).exists()) {
            emitToast("请先生成合并预览")
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            try {
                withContext(Dispatchers.Main) {
                    stopAllPreview()
                    playerManager.pause()
                }

                // 文件级 IO（覆盖写入、读时长、读大小）放到 IO 线程，避免大文件阻塞主线程
                val duration = withContext(Dispatchers.IO) {
                    val sourceFile = File(audio.filePath)
                    if (!sourceFile.exists() || !sourceFile.isFile) {
                        null
                    } else if (audioCutter.isVideoSource(audio)) {
                        // 视频输入：生成带画面视频后覆盖（预览文件仅含音频，不能直接覆盖）
                        val selected = _segments.value.filter { it.isSelected && it.endMs > it.startMs }
                        if (selected.isEmpty()) {
                            null
                        } else {
                            val tempVideo = File(
                                getApplication<Application>().cacheDir,
                                "overwrite_video_${System.currentTimeMillis()}.mp4"
                            )
                            val videoResult = audioCutter.exportVideoWithSegments(audio, selected, tempVideo) { _exportProgress.value = it }
                            if (!videoResult.isSuccess) {
                                tempVideo.delete()
                                null
                            } else {
                                tempVideo.copyTo(sourceFile, overwrite = true)
                                tempVideo.delete()
                                queryAudioDurationMs(sourceFile.absolutePath).coerceAtLeast(videoResult.durationMs)
                            }
                        }
                    } else {
                        val previewFile = File(preview.outputPath)
                        previewFile.copyTo(sourceFile, overwrite = true)
                        previewFile.delete()
                        queryAudioDurationMs(sourceFile.absolutePath).coerceAtLeast(preview.durationMs)
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
                _segments.value = emptyList()
                _mergedPreviewResult.value = null

                eventBus.notifyAudioOverwritten(audio.id, audio.filePath)
                eventBus.notifyAudioLibraryChanged()
                emitToast("已覆盖原文件：${audio.title}")
            } catch (e: Exception) {
                emitToast("覆盖失败: ${e.localizedMessage}")
            } finally {
                _isExporting.value = false
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

    /** 导出拼接音频 */
    fun exportMergedSegments(
        customFileName: String? = null,
        format: ExportAudioFormat = ExportAudioFormat.M4A
    ) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频可导出")
            return
        }
        val selected = _segments.value.filter { it.isSelected }
        if (selected.isEmpty()) {
            emitToast("请至少勾选一个导出片段")
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            try {
                val result = audioCutter.exportSegments(
                    sourceAudio = audio,
                    segments = selected,
                    customFileName = customFileName,
                    targetFormat = format
                ) { _exportProgress.value = it }
                _exportResult.value = result
                val formatLabel = if (result.outputIsVideo) "MP4 视频（含画面）" else "音频 ${result.format.name}"
                emitToast(
                    if (result.isSuccess) "拼接导出成功！$formatLabel，保存至：${result.outputPath}"
                    else "导出失败: ${result.errorMessage}"
                )
                if (result.isSuccess) eventBus.notifyAudioLibraryChanged()
            } catch (e: Exception) {
                emitToast("导出异常: ${e.localizedMessage}")
            } finally {
                _isExporting.value = false
            }
        }
    }

    /** 停止所有预览 */
    fun stopAllPreview() {
        previewJob?.cancel()
        previewJob = null
        _previewingSegmentId.value = null
        mergedPreviewPlayer.pause()
    }

    override fun onCleared() {
        mergedPreviewPlayer.release()
        super.onCleared()
    }
}
