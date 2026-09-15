package com.example.AudioCut.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.AudioCut.asr.AsrManager
import com.example.AudioCut.asr.ModelInstallCoordinator
import com.example.AudioCut.asr.ModelManager
import com.example.AudioCut.core.AppEventBus
import com.example.AudioCut.data.repository.AudioRepository
import com.example.AudioCut.data.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置界面 ViewModel
 * 负责文稿配置（开启文稿 / 开启排版优化 / 排版优化单次字数 / 分块 / VAD）、数据清理、ASR 资源释放
 *
 * 模型管理：开启文稿时先检测识别模型，缺失则提示下载或导入；
 * 下载带进度条，失败时展示下载地址卡片供手动下载后导入。
 * 开启排版优化时先检测标点模型（punct-ct），缺失则提示下载或导入。
 */
class SettingsViewModel(
    application: Application,
    eventBus: AppEventBus,
    private val prefs: PreferencesRepository,
    private val asrManager: AsrManager,
    private val modelManager: ModelManager,
    private val audioRepository: AudioRepository
) : BaseViewModel(application, eventBus) {

    // ==================== 文稿开关 ====================
    private val _enableDocTranscript = MutableStateFlow(prefs.getEnableDocTranscript())
    val enableDocTranscript: StateFlow<Boolean> = _enableDocTranscript.asStateFlow()

    // ==================== 排版优化开关 ====================
    private val _enableLayoutOptimization = MutableStateFlow(prefs.getEnableLayoutOptimization())
    val enableLayoutOptimization: StateFlow<Boolean> = _enableLayoutOptimization.asStateFlow()

    // ==================== 排版优化设置 ====================
    /** 排版优化单次文本数量（字）：每次交给 punct 标点模型处理的字符数，默认 1000 */
    private val _punctChunkChars = MutableStateFlow(prefs.getPunctChunkChars())
    val punctChunkChars: StateFlow<Int> = _punctChunkChars.asStateFlow()

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

    /** 当前模型下载/导入任务；点击「停止」时取消并关闭进度卡片 */
    private var modelInstallJob: Job? = null

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
                emitToast("识别模型已就绪，文稿已开启")
            }
        }
    }

    /**
     * 开启排版优化：先检测 punct 标点模型，就绪才开启；缺失则提示下载或导入。
     * 关闭则直接保存。
     */
    fun onToggleLayoutOptimization(enabled: Boolean) {
        if (!enabled) {
            _enableLayoutOptimization.value = false
            prefs.saveEnableLayoutOptimization(false)
            eventBus.notifyLayoutOptimizationEnabledChanged(false)
            return
        }
        viewModelScope.launch {
            val ready = coordinator.checkOrPrompt(ModelManager.ModelType.PUNCT_CT)
            if (ready) {
                _enableLayoutOptimization.value = true
                prefs.saveEnableLayoutOptimization(true)
                eventBus.notifyLayoutOptimizationEnabledChanged(true)
                emitToast("标点模型已就绪，排版优化已开启")
            }
        }
    }

    /**
     * 设置排版优化单次文本数量（字）：取值范围 100 - 5000
     */
    fun setPunctChunkChars(chars: Int) {
        val clamped = chars.coerceIn(100, 5000)
        _punctChunkChars.value = clamped
        prefs.savePunctChunkChars(clamped)
    }

    // ==================== 模型下载 / 导入 ====================
    /** 下载当前提示的模型（带进度条），成功后开启对应开关 */
    fun downloadPromptedModel() {
        val type = modelInstallState.value.type ?: return
        modelInstallJob = viewModelScope.launch {
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
        modelInstallJob = viewModelScope.launch {
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

    /** 中止模型下载/导入并关闭进度卡片（下载协程取消后自动复位状态） */
    fun stopModelDownload() {
        modelInstallJob?.cancel()
        modelInstallJob = null
        coordinator.dismiss()
    }

    private fun applyModelEnabled(type: ModelManager.ModelType) {
        when (type) {
            ModelManager.ModelType.SENSE_VOICE -> {
                _enableDocTranscript.value = true
                prefs.saveEnableDocTranscript(true)
            }
            // punct-ct 标点模型：安装完成后开启排版优化开关
            ModelManager.ModelType.PUNCT_CT -> {
                _enableLayoutOptimization.value = true
                prefs.saveEnableLayoutOptimization(true)
                eventBus.notifyLayoutOptimizationEnabledChanged(true)
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

    /**
     * 清理导出音频：不清空音频库中的所有文件，仅刷新音频库，
     * 并移除已失去文件位置的失效记录（文件被外部删除、路径已不存在的条目）
     */
    fun clearAllExports() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val before = prefs.getAudioLibraryCache()
                val refreshed = audioRepository.scanImportedAudios()
                prefs.saveAudioLibraryCache(refreshed)
                prefs.cleanupHiddenFilePaths()
                val removedCount = (before.size - refreshed.size).coerceAtLeast(0)
                withContext(Dispatchers.Main) {
                    emitToast(
                        if (removedCount > 0) "音频库已刷新，清理了 $removedCount 条失效音频"
                        else "音频库已刷新，未发现失效音频"
                    )
                    eventBus.notifyAudioLibraryChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    emitToast("刷新音频库失败: ${e.message}")
                }
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
