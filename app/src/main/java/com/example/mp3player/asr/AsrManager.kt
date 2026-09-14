package com.example.mp3player.asr

import android.content.Context
import com.example.mp3player.data.model.AudioItem
import com.example.mp3player.data.model.TranscriptResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ASR 管理器：封装离线语音识别引擎的生命周期与状态
 *
 * 设计原则：
 * - ASR 引擎是重量级资源（加载 SenseVoice 模型 + VAD），采用懒加载单例
 * - 仅在文稿界面使用，但识别任务可在后台继续（用户切页后仍可完成识别）
 * - 提供显式 release 入口，在设置界面或应用退出时释放模型内存
 * - 通过 StateFlow 暴露当前识别状态，支持跨界面观察
 */
class AsrManager(
    private val context: Context
) {

    private var engine: OfflineAsrEngine? = null

    private val _isEngineReady = MutableStateFlow(false)
    val isEngineReady: StateFlow<Boolean> = _isEngineReady.asStateFlow()

    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing.asStateFlow()

    /** 获取或懒初始化 ASR 引擎 */
    fun getEngine(): OfflineAsrEngine {
        return engine ?: synchronized(this) {
            engine ?: OfflineAsrEngine(context).also {
                engine = it
                _isEngineReady.value = true
            }
        }
    }

    /**
     * 执行语音识别
     * @param audio 目标音频
     * @param startOffsetMs 起始偏移（断点续传）
     * @param existingWords 已有词语（断点续传）
     * @param useVad 是否启用 VAD
     * @param chunkTargetMs 分片目标时长
     * @param onPartialResult 分片结果回调
     * @param onProgress 进度回调 (0f ~ 1f)
     */
    suspend fun transcribeAudio(
        audio: AudioItem,
        startOffsetMs: Long = 0L,
        existingWords: List<com.example.mp3player.data.model.TranscriptWord> = emptyList(),
        useVad: Boolean = true,
        chunkTargetMs: Long = 30_000L,
        onPartialResult: (TranscriptResult) -> Unit,
        onProgress: (Float) -> Unit
    ): TranscriptResult {
        _isRecognizing.value = true
        return try {
            getEngine().transcribeAudio(
                audio = audio,
                startOffsetMs = startOffsetMs,
                existingWords = existingWords,
                useVad = useVad,
                chunkTargetMs = chunkTargetMs,
                onPartialResult = onPartialResult,
                onProgress = onProgress
            )
        } finally {
            _isRecognizing.value = false
        }
    }

    /**
     * 释放 ASR 引擎占用的重度内存资源（模型与 VAD）
     * 通常在设置界面"清理资源"或应用退出时调用
     */
    fun release() {
        synchronized(this) {
            OfflineAsrEngine.releaseResources()
            engine = null
            _isEngineReady.value = false
        }
    }
}
