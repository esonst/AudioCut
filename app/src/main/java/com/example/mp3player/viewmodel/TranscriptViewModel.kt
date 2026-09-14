package com.example.mp3player.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.example.mp3player.asr.AsrManager
import com.example.mp3player.core.AppEventBus
import com.example.mp3player.data.model.AudioSegment
import com.example.mp3player.data.model.TranscriptResult
import com.example.mp3player.data.model.TranscriptWord
import com.example.mp3player.data.repository.PreferencesRepository
import com.example.mp3player.player.AudioPlayerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 文稿界面 ViewModel
 * 负责离线语音识别、文稿展示、文本选区、从文本生成片段/裁剪区间
 *
 * ASR 生命周期：通过 AsrManager 统一管理，仅在文稿界面激活时使用
 * 识别任务可在后台继续（用户切页后仍可完成），在设置界面可手动释放模型
 */
class TranscriptViewModel(
    application: Application,
    eventBus: AppEventBus,
    private val asrManager: AsrManager,
    private val prefs: PreferencesRepository,
    private val playerManager: AudioPlayerManager
) : BaseViewModel(application, eventBus) {

    // ==================== 播放器状态（透传） ====================
    val currentPlayingAudio: StateFlow<com.example.mp3player.data.model.AudioItem?> = playerManager.currentAudio
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val currentPositionMs: StateFlow<Long> = playerManager.currentPositionMs
    val durationMs: StateFlow<Long> = playerManager.durationMs

    // ==================== ASR 状态 ====================
    private val _isAsrLoading = MutableStateFlow(false)
    val isAsrLoading: StateFlow<Boolean> = _isAsrLoading.asStateFlow()

    private val _asrProgress = MutableStateFlow(0f)
    val asrProgress: StateFlow<Float> = _asrProgress.asStateFlow()

    private val _asrProgressText = MutableStateFlow("")
    val asrProgressText: StateFlow<String> = _asrProgressText.asStateFlow()
    private var asrJob: Job? = null

    // ==================== 文稿结果 ====================
    private val _transcriptResult = MutableStateFlow<TranscriptResult?>(null)
    val transcriptResult: StateFlow<TranscriptResult?> = _transcriptResult.asStateFlow()

    private val _selectedWordIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedWordIds: StateFlow<Set<String>> = _selectedWordIds.asStateFlow()

    // ==================== 文本选区 ====================
    private val _selectedTextRange = MutableStateFlow<androidx.compose.ui.text.TextRange?>(null)
    val selectedTextRange: StateFlow<androidx.compose.ui.text.TextRange?> = _selectedTextRange.asStateFlow()

    // ==================== ASR 配置 ====================
    private val _enableSlicing = MutableStateFlow(prefs.getEnableSlicing())
    val enableSlicing: StateFlow<Boolean> = _enableSlicing.asStateFlow()

    private val _asrChunkSeconds = MutableStateFlow(prefs.getAsrChunkSeconds())
    val asrChunkSeconds: StateFlow<Int> = _asrChunkSeconds.asStateFlow()

    init {
        // 切换音频时自动加载缓存文稿
        viewModelScope.launch {
            currentPlayingAudio.collect { audio ->
                _transcriptResult.value = audio?.let { prefs.getCachedTranscript(it.id) }
                _selectedWordIds.value = emptySet()
            }
        }
    }

    fun setSelectedTextRange(range: androidx.compose.ui.text.TextRange?) {
        _selectedTextRange.value = range
    }

    fun setEnableSlicing(enabled: Boolean) {
        _enableSlicing.value = enabled
        prefs.saveEnableSlicing(enabled)
    }

    fun setAsrChunkSeconds(seconds: Int) {
        _asrChunkSeconds.value = seconds
        prefs.saveAsrChunkSeconds(seconds)
    }

    /** 开始语音识别（支持断点续传） */
    fun startAsrRecognition(resumeIfPossible: Boolean = true) {
        val current = currentPlayingAudio.value ?: run {
            emitToast("请先选择播放音频")
            return
        }
        if (asrJob?.isActive == true) {
            emitToast("已有正在进行的识别任务")
            return
        }

        asrJob = viewModelScope.launch {
            try {
                _isAsrLoading.value = true
                val currentChunkSec = _asrChunkSeconds.value
                val isSlicing = _enableSlicing.value
                val cached = _transcriptResult.value ?: prefs.getCachedTranscript(current.id)
                val canResume = resumeIfPossible && cached != null && !cached.isCompleted && cached.processedDurationMs > 0L
                val startOffsetMs = if (canResume) cached!!.processedDurationMs else 0L
                val existingWords = if (canResume) cached!!.words else emptyList()
                val totalMs = maxOf(1L, current.durationMs)

                val chunkTargetMs = if (isSlicing) currentChunkSec * 1000L else totalMs
                val totalChunks = if (isSlicing) (totalMs / chunkTargetMs + 1).toInt() else 1

                if (canResume) {
                    emitToast("正在从 ${startOffsetMs / 1000}s 继续生成文稿...")
                    _asrProgress.value = (startOffsetMs.toFloat() / totalMs).coerceIn(0f, 0.95f)
                    _asrProgressText.value = "[${startOffsetMs / chunkTargetMs}/$totalChunks]"
                } else {
                    _asrProgress.value = 0f
                    _asrProgressText.value = "[0/$totalChunks]"
                }

                val result = asrManager.transcribeAudio(
                    audio = current,
                    startOffsetMs = startOffsetMs,
                    existingWords = existingWords,
                    useVad = true,
                    chunkTargetMs = chunkTargetMs,
                    onPartialResult = { partial ->
                        _transcriptResult.value = partial
                        prefs.saveCachedTranscript(current.id, partial)
                        val processedMs = partial.processedDurationMs
                        _asrProgressText.value = "[${processedMs / chunkTargetMs}/$totalChunks]"
                    },
                    onProgress = { _asrProgress.value = it }
                )

                _transcriptResult.value = result
                prefs.saveCachedTranscript(current.id, result)
                emitToast(if (result.words.isNotEmpty()) "本地语音识别完成" else "未检测到清晰人声语音")
            } catch (e: Exception) {
                emitToast(if (e is CancellationException) "识别任务已停止" else "语音识别失败")
            } finally {
                _isAsrLoading.value = false
                _asrProgressText.value = ""
            }
        }
    }

    fun stopAsrRecognition() {
        if (asrJob?.isActive == true) {
            asrJob?.cancel()
            asrJob = null
            _isAsrLoading.value = false
        }
    }

    /** 删除当前文稿 */
    fun deleteCurrentTranscript() {
        val current = currentPlayingAudio.value ?: return
        _transcriptResult.value = null
        prefs.saveCachedTranscript(current.id, null)
        emitToast("文稿已删除")
    }

    /** 解析选中文本对应的时间区间 */
    private fun resolveSelectionRange(
        transcript: TranscriptResult, start: Int, end: Int
    ): Triple<Long, Long, String>? {
        var currentIdx = 0
        var startMs = -1L
        var endMs = -1L
        var snippet = ""

        for ((_, sentences) in transcript.paragraphs) {
            for ((_, _, _, _, words) in sentences) {
                for ((_, word1, startMs1, endMs1) in words) {
                    val wordStart = currentIdx
                    val wordEnd = currentIdx + word1.length
                    if (start < wordEnd && end > wordStart) {
                        if (startMs == -1L || startMs1 < startMs) startMs = startMs1
                        if (endMs == -1L || endMs1 > endMs) endMs = endMs1
                        if (snippet.length < 30) snippet += word1
                    }
                    currentIdx = wordEnd
                }
            }
            currentIdx += 2
        }
        return if (startMs != -1L && endMs != -1L) Triple(startMs, endMs, snippet) else null
    }

    /** 从选中文本创建剪辑片段（通过事件总线通知 ClipViewModel） */
    fun createSegmentFromSelection() {
        val range = _selectedTextRange.value ?: return
        if (range.collapsed) return
        val transcript = _transcriptResult.value ?: return
        val resolved = resolveSelectionRange(transcript, range.min, range.max) ?: return

        val segment = AudioSegment(
            audioId = currentPlayingAudio.value?.id ?: 0L,
            title = "剪辑: ${resolved.third.take(15)}",
            startMs = resolved.first,
            endMs = resolved.second,
            colorIndex = 0
        )
        eventBus.sendSegmentCreated(segment)
        _selectedTextRange.value = null
        emitToast("已生成剪辑片段")
    }

    /** 从选中文本创建裁剪区间（通过事件总线通知 TrimViewModel） */
    fun createTrimFromSelection() {
        val range = _selectedTextRange.value ?: return
        if (range.collapsed) return
        val transcript = _transcriptResult.value ?: return
        val resolved = resolveSelectionRange(transcript, range.min, range.max) ?: return

        val trim = AudioSegment(
            audioId = currentPlayingAudio.value?.id ?: 0L,
            title = "裁剪: ${resolved.third.take(15)}",
            startMs = resolved.first,
            endMs = resolved.second,
            colorIndex = 0
        )
        eventBus.sendTrimCreated(trim)
        _selectedTextRange.value = null
        emitToast("已添加裁剪卡片，可前往【裁剪】页处理")
    }

    override fun onCleared() {
        stopAsrRecognition()
        super.onCleared()
    }
}
