package com.example.mp3player.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.example.mp3player.core.AppEventBus
import com.example.mp3player.data.model.*
import com.example.mp3player.data.repository.PreferencesRepository
import com.example.mp3player.ffmpeg.AudioCutterConcatenator
import com.example.mp3player.ffmpeg.ExportAudioFormat
import com.example.mp3player.ffmpeg.ExportResult
import com.example.mp3player.player.AudioPlayerManager
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
            mergedPreviewPlayer.pause()

            val result = audioCutter.generateFastPreview(audio, selected)
            _isGeneratingMergedPreview.value = false
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

    /** 保存预览到音乐库 */
    fun saveMergedPreviewToLibrary(customName: String? = null) {
        val preview = mergedPreviewResult.value ?: return
        if (!preview.isSuccess) return
        val source = File(preview.outputPath)
        if (!source.exists()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val fileName = customName?.ifBlank { null } ?: "Clip${System.currentTimeMillis()}"
                val target = File(dir, "${fileName}.${source.extension}")
                source.copyTo(target, overwrite = true)
                withContext(Dispatchers.Main) {
                    emitToast("已保存至: ${target.absolutePath}")
                    eventBus.notifyAudioLibraryChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    emitToast("保存失败: ${e.message}")
                }
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
                emitToast(
                    if (result.isSuccess) "音频拼接导出成功！格式：${result.format.name}，保存至：${result.outputPath}"
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
