package com.example.audiocut.asr

import android.content.Context
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.data.model.TranscriptResult
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
     * @param config 流水线配置（分块 / VAD / 智能分句）
     * @param onPartialResult 分片结果回调
     * @param onProgress 进度回调 (0f ~ 1f)
     * @param onLog 处理日志回调（分块/VAD/识别/智能分句各阶段）
     */
    suspend fun transcribeAudio(
        audio: AudioItem,
        startOffsetMs: Long = 0L,
        existingWords: List<com.example.audiocut.data.model.TranscriptWord> = emptyList(),
        config: AsrConfig = AsrConfig(),
        startMs: Long = 0L,
        endMs: Long = 0L,
        onPartialResult: (TranscriptResult) -> Unit,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit = {}
    ): TranscriptResult {
        _isRecognizing.value = true
        return try {
            getEngine().transcribeAudio(
                audio = audio,
                startOffsetMs = startOffsetMs,
                existingWords = existingWords,
                config = config,
                startMs = startMs,
                endMs = endMs,
                onPartialResult = onPartialResult,
                onProgress = onProgress,
                onLog = onLog
            )
        } finally {
            _isRecognizing.value = false
        }
    }

    /**
     * 释放 ASR 引擎占用的重度内存资源（模型与 VAD）
     * 通常在设置界面"清理资源"或应用退出时调用
     *
     * @return true 已释放；false 当前正在识别，为避免释放正在使用的模型导致崩溃，拒绝释放
     */
    fun release(): Boolean {
        synchronized(this) {
            if (_isRecognizing.value) return false
            OfflineAsrEngine.releaseResources()
            engine = null
            _isEngineReady.value = false
        }
        return true
    }
}
