package com.example.mp3player.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.example.mp3player.asr.AsrManager
import com.example.mp3player.asr.ModelInstallCoordinator
import com.example.mp3player.asr.ModelManager
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
 * 负责文稿转写配置（开启文稿转写 / 智能分句 / 分块 / VAD）、数据清理、ASR 资源释放
 *
 * 模型管理：开启文稿转写/智能分句时先检测模型，缺失则提示下载或导入；
 * 下载带进度条，失败时展示下载地址卡片供手动下载后导入。
 */
class SettingsViewModel(
    application: Application,
    eventBus: AppEventBus,
    private val prefs: PreferencesRepository,
    private val asrManager: AsrManager,
    private val modelManager: ModelManager
) : BaseViewModel(application, eventBus) {

    // ==================== 文稿转写开关 ====================
    private val _enableDocTranscript = MutableStateFlow(prefs.getEnableDocTranscript())
    val enableDocTranscript: StateFlow<Boolean> = _enableDocTranscript.asStateFlow()

    private val _enableSmartPunct = MutableStateFlow(prefs.getEnableSmartPunct())
    val enableSmartPunct: StateFlow<Boolean> = _enableSmartPunct.asStateFlow()

    // ==================== 分块设置 ====================
    private val _enableSlicing = MutableStateFlow(prefs.getEnableSlicing())
    val enableSlicing: StateFlow<Boolean> = _enableSlicing.asStateFlow()

    private val _asrChunkSeconds = MutableStateFlow(prefs.getAsrChunkSeconds())
    val asrChunkSeconds: StateFlow<Int> = _asrChunkSeconds.asStateFlow()

    // ==================== VAD 设置 ====================
    private val _enableVad = MutableStateFlow(prefs.getEnableVad())
    val enableVad: StateFlow<Boolean> = _enableVad.asStateFlow()

    private val _vadThreshold = MutableStateFlow(prefs.getVadThreshold())
    val vadThreshold: StateFlow<Float> = _vadThreshold.asStateFlow()

    private val _vadMinSilence = MutableStateFlow(prefs.getVadMinSilence())
    val vadMinSilence: StateFlow<Float> = _vadMinSilence.asStateFlow()

    private val _vadMinSpeech = MutableStateFlow(prefs.getVadMinSpeech())
    val vadMinSpeech: StateFlow<Float> = _vadMinSpeech.asStateFlow()

    private val _vadMaxSpeech = MutableStateFlow(prefs.getVadMaxSpeech())
    val vadMaxSpeech: StateFlow<Float> = _vadMaxSpeech.asStateFlow()

    // ==================== 模型安装状态 ====================
    private val coordinator = ModelInstallCoordinator(modelManager)
    val modelInstallState: StateFlow<ModelInstallCoordinator.UiState> = coordinator.state

    /** 本次会话是否已自动提示过模型缺失（避免每次进入设置页都弹窗打扰） */
    private var autoPromptedThisSession = false

    /**
     * 进入设置页时自动检查：文稿转写开关默认开启，但模型缺失时该开关无法真正生效。
     * 检测到「开关已开启 + 模型缺失」时主动弹出下载/导入提示；本会话内只自动提示一次。
     */
    fun verifyDocTranscriptModel() {
        if (autoPromptedThisSession) return
        autoPromptedThisSession = true
        if (!prefs.getEnableDocTranscript()) return
        if (modelManager.isSenseVoiceReady()) return
        viewModelScope.launch {
            coordinator.checkOrPrompt(ModelManager.ModelType.SENSE_VOICE)
        }
    }

    // ==================== 开关切换 ====================
    /**
     * 开启文稿转写：先检测 SenseVoice 模型，就绪才开启；缺失则提示下载或导入。
     * 关闭则直接保存（识别时跳过预处理，直接下一步）。
     */
    fun onToggleDocTranscript(enabled: Boolean) {
        if (!enabled) {
            _enableDocTranscript.value = false
            prefs.saveEnableDocTranscript(false)
            return
        }
        viewModelScope.launch {
            val ready = coordinator.checkOrPrompt(ModelManager.ModelType.SENSE_VOICE)
            if (ready) {
                _enableDocTranscript.value = true
                prefs.saveEnableDocTranscript(true)
                emitToast("识别模型已就绪，文稿转写已开启")
            }
        }
    }

    /**
     * 开启智能分句：先检测 punct-ct 标点模型，就绪才开启；缺失则提示下载或导入。
     * 关闭则按当前机械方式分句。
     */
    fun onToggleSmartPunct(enabled: Boolean) {
        if (!enabled) {
            _enableSmartPunct.value = false
            prefs.saveEnableSmartPunct(false)
            return
        }
        viewModelScope.launch {
            val ready = coordinator.checkOrPrompt(ModelManager.ModelType.PUNCT_CT)
            if (ready) {
                _enableSmartPunct.value = true
                prefs.saveEnableSmartPunct(true)
                emitToast("标点模型已就绪，智能分句已开启")
            }
        }
    }

    // ==================== 模型下载 / 导入 ====================
    /** 下载当前提示的模型（带进度条），成功后开启对应开关 */
    fun downloadPromptedModel() {
        val type = modelInstallState.value.type ?: return
        viewModelScope.launch {
            val ok = coordinator.download()
            if (ok) {
                applyModelEnabled(type)
                emitToast("模型下载并安装完成")
            } else {
                emitToast("模型下载失败，可手动下载后导入")
            }
        }
    }

    /** 导入本地模型压缩包（.tar.bz2），成功后开启对应开关 */
    fun importPromptedModel(uri: Uri) {
        val type = modelInstallState.value.type ?: return
        viewModelScope.launch {
            val ok = coordinator.import(uri)
            if (ok) {
                applyModelEnabled(type)
                emitToast("模型导入完成")
            } else {
                emitToast("模型导入失败，请选择 .tar.bz2 压缩包")
            }
        }
    }

    fun dismissModelDialog() = coordinator.dismiss()

    private fun applyModelEnabled(type: ModelManager.ModelType) {
        when (type) {
            ModelManager.ModelType.SENSE_VOICE -> {
                _enableDocTranscript.value = true
                prefs.saveEnableDocTranscript(true)
            }
            ModelManager.ModelType.PUNCT_CT -> {
                _enableSmartPunct.value = true
                prefs.saveEnableSmartPunct(true)
            }
        }
    }

    // ==================== 分块设置 ====================
    fun setEnableSlicing(enabled: Boolean) {
        _enableSlicing.value = enabled
        prefs.saveEnableSlicing(enabled)
    }

    fun setAsrChunkSeconds(seconds: Int) {
        _asrChunkSeconds.value = seconds
        prefs.saveAsrChunkSeconds(seconds)
    }

    // ==================== VAD 设置 ====================
    fun setEnableVad(enabled: Boolean) {
        _enableVad.value = enabled
        prefs.saveEnableVad(enabled)
        invalidateAsrCache()
    }

    fun setVadThreshold(value: Float) {
        if (value.isNaN() || value < 0.05f || value > 0.95f) return
        _vadThreshold.value = value
        prefs.saveVadThreshold(value)
        invalidateAsrCache()
    }

    fun setVadMinSilence(value: Float) {
        if (value.isNaN() || value < 0.05f || value > 5.0f) return
        _vadMinSilence.value = value
        prefs.saveVadMinSilence(value)
        invalidateAsrCache()
    }

    fun setVadMinSpeech(value: Float) {
        if (value.isNaN() || value < 0.05f || value > 5.0f) return
        _vadMinSpeech.value = value
        prefs.saveVadMinSpeech(value)
        invalidateAsrCache()
    }

    fun setVadMaxSpeech(value: Float) {
        if (value.isNaN() || value < 1.0f || value > 120.0f) return
        _vadMaxSpeech.value = value
        prefs.saveVadMaxSpeech(value)
        invalidateAsrCache()
    }

    /** VAD 参数变更后释放已加载的 ASR 缓存，确保下次识别使用新参数（静默，不弹提示） */
    private fun invalidateAsrCache() {
        asrManager.release()
    }

    // ==================== 数据管理 ====================
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
     * ASR 模型是重度内存资源（SenseVoice + VAD + 标点模型），仅在文稿界面使用
     * 用户可在设置界面手动释放以回收内存，下次进入文稿页自动重新加载
     */
    fun releaseAsrEngine() {
        asrManager.release()
        emitToast("ASR 引擎资源已释放，下次进入文稿页将重新加载")
    }
}
