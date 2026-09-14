package com.example.mp3player.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.example.mp3player.asr.AsrManager
import com.example.mp3player.core.AppEventBus
import com.example.mp3player.data.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置界面 ViewModel
 * 负责应用配置项、数据清理、ASR 资源释放
 *
 * ASR 资源管理：ASR 引擎仅在文稿界面使用，此处提供显式释放入口
 * 释放后下次进入文稿界面会重新懒加载模型
 */
class SettingsViewModel(
    application: Application,
    eventBus: AppEventBus,
    private val prefs: PreferencesRepository,
    private val asrManager: AsrManager
) : BaseViewModel(application, eventBus) {

    private val _enableSlicing = MutableStateFlow(prefs.getEnableSlicing())
    val enableSlicing: StateFlow<Boolean> = _enableSlicing.asStateFlow()

    private val _asrChunkSeconds = MutableStateFlow(prefs.getAsrChunkSeconds())
    val asrChunkSeconds: StateFlow<Int> = _asrChunkSeconds.asStateFlow()

    fun setEnableSlicing(enabled: Boolean) {
        _enableSlicing.value = enabled
        prefs.saveEnableSlicing(enabled)
    }

    fun setAsrChunkSeconds(seconds: Int) {
        _asrChunkSeconds.value = seconds
        prefs.saveAsrChunkSeconds(seconds)
    }

    /** 清空所有文稿缓存 */
    fun clearAllTranscripts() {
        prefs.clearAllTranscripts()
        emitToast("所有文稿缓存已清理")
    }

    /** 清空所有导出文件 */
    fun clearAllExports() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            var count = 0
            dir?.listFiles()?.forEach { if (it.isFile) { it.delete(); count++ } }
            withContext(Dispatchers.Main) {
                emitToast("已清理 $count 个导出文件")
                eventBus.notifyAudioLibraryChanged()
            }
        }
    }

    /**
     * 释放 ASR 引擎资源
     * ASR 模型是重度内存资源（SenseVoice + VAD），仅在文稿界面使用
     * 用户可在设置界面手动释放以回收内存，下次进入文稿页自动重新加载
     */
    fun releaseAsrEngine() {
        asrManager.release()
        emitToast("ASR 引擎资源已释放，下次进入文稿页将重新加载")
    }
}
