package com.example.AudioCut.core

import com.example.AudioCut.data.model.AudioSegment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局应用事件总线
 * 用于跨 ViewModel / 跨模块通信，避免 ViewModel 之间直接持有引用
 *
 * 事件类型：
 * - Toast：全局提示
 * - SegmentCreated：文稿界面创建剪辑片段 → 通知 ClipViewModel
 * - TrimCreated：文稿界面创建裁剪区间 → 通知 TrimViewModel
 * - AudioLibraryChanged：音频库数据变化 → 通知其他模块刷新
 * - NavigateToConvert：跳转到格式转换页并携带输入文件
 */
class AppEventBus {

    // ==================== Toast 事件 ====================
    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    fun sendToast(msg: String) {
        _toastEvent.tryEmit(msg)
    }

    // ==================== 文稿 → 剪辑 片段创建事件 ====================
    private val _segmentCreated = MutableSharedFlow<AudioSegment>(extraBufferCapacity = 1)
    val segmentCreated: SharedFlow<AudioSegment> = _segmentCreated.asSharedFlow()

    fun sendSegmentCreated(segment: AudioSegment) {
        _segmentCreated.tryEmit(segment)
    }

    // ==================== 文稿 → 裁剪 区间创建事件 ====================
    private val _trimCreated = MutableSharedFlow<AudioSegment>(extraBufferCapacity = 1)
    val trimCreated: SharedFlow<AudioSegment> = _trimCreated.asSharedFlow()

    fun sendTrimCreated(trim: AudioSegment) {
        _trimCreated.tryEmit(trim)
    }

    // ==================== 音频库数据变化事件 ====================
    private val _audioLibraryChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val audioLibraryChanged: SharedFlow<Unit> = _audioLibraryChanged.asSharedFlow()

    fun notifyAudioLibraryChanged() {
        _audioLibraryChanged.tryEmit(Unit)
    }

    // ==================== 跳转格式转换事件 ====================
    data class NavigateToConvertEvent(val inputFile: String?)

    private val _navigateToConvert = MutableSharedFlow<NavigateToConvertEvent>(extraBufferCapacity = 1)
    val navigateToConvert: SharedFlow<NavigateToConvertEvent> = _navigateToConvert.asSharedFlow()

    fun sendNavigateToConvert(inputFile: String?) {
        _navigateToConvert.tryEmit(NavigateToConvertEvent(inputFile))
    }

    // ==================== 停止所有预览事件 ====================
    private val _stopAllPreview = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopAllPreview: SharedFlow<Unit> = _stopAllPreview.asSharedFlow()

    fun sendStopAllPreview() {
        _stopAllPreview.tryEmit(Unit)
    }

    // ==================== 音频被覆盖事件 ====================
    /** 音频被【覆盖】后广播：携带音频 ID 与文件路径，通知文稿/剪辑/裁剪/转换等模块清理关联状态 */
    data class AudioOverwrittenEvent(val audioId: Long, val filePath: String)

    private val _audioOverwritten = MutableSharedFlow<AudioOverwrittenEvent>(extraBufferCapacity = 1)
    val audioOverwritten: SharedFlow<AudioOverwrittenEvent> = _audioOverwritten.asSharedFlow()

    fun notifyAudioOverwritten(audioId: Long, filePath: String) {
        _audioOverwritten.tryEmit(AudioOverwrittenEvent(audioId, filePath))
    }

    // ==================== 排版优化开关变化事件 ====================
    private val _layoutOptimizationEnabledChanged = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val layoutOptimizationEnabledChanged: SharedFlow<Boolean> = _layoutOptimizationEnabledChanged.asSharedFlow()

    fun notifyLayoutOptimizationEnabledChanged(enabled: Boolean) {
        _layoutOptimizationEnabledChanged.tryEmit(enabled)
    }
}
