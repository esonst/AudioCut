package com.example.audiocut.asr

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模型安装协调器：为「开启文稿转写 / 开启智能分句」提供统一的
 * 检测 -> 提示下载/导入 -> 下载(带进度) -> 失败提示 状态机。
 *
 * 两个界面（设置页、文稿页）各自持有一个实例，底层共享 [ModelManager] 单例。
 */
class ModelInstallCoordinator(private val modelManager: ModelManager) {

    enum class Phase {
        /** 空闲 */
        NONE,
        /** 正在检测模型是否存在 */
        CHECKING,
        /** 模型缺失，提示下载或导入 */
        MISSING,
        /** 正在下载（progress 0f~1f，-1f 表示总长度未知） */
        DOWNLOADING,
        /** 正在导入本地文件 */
        IMPORTING,
        /** 下载失败，展示带下载地址的提示卡片 */
        FAILED
    }

    data class UiState(
        val type: ModelManager.ModelType? = null,
        val phase: Phase = Phase.NONE,
        val progress: Float = 0f
    ) {
        val isBusy: Boolean
            get() = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.IMPORTING

        val downloadUrl: String
            get() = type?.downloadUrl ?: ""
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 检查模型是否就绪。
     * @return true 表示就绪（状态复位）；false 表示缺失（进入 MISSING 提示阶段）
     */
    suspend fun checkOrPrompt(type: ModelManager.ModelType): Boolean {
        _state.value = UiState(type, Phase.CHECKING)
        val ready = modelManager.isModelReady(type)
        _state.value = if (ready) UiState() else UiState(type, Phase.MISSING)
        return ready
    }

    /**
     * 下载并安装当前待处理的模型（下载 -> 解压两个阶段，进度连续展示）
     * @return 是否成功；失败时进入 FAILED 阶段
     * 用户点击停止时取消下载协程，复位状态后向调用方传播取消
     */
    suspend fun download(): Boolean {
        val type = _state.value.type ?: return false
        _state.value = UiState(type, Phase.DOWNLOADING, 0f)
        try {
            val result = modelManager.downloadAndInstall(
                type,
                onProgress = { progress ->
                    _state.value = UiState(type, Phase.DOWNLOADING, progress)
                },
                onExtract = { progress ->
                    // 下载完成进入解压阶段（progress 0f~1f）
                    _state.value = UiState(type, Phase.IMPORTING, progress)
                }
            )
            return if (result.isSuccess) {
                _state.value = UiState()
                true
            } else {
                _state.value = UiState(type, Phase.FAILED)
                false
            }
        } catch (e: CancellationException) {
            // 用户点击停止：复位状态（关闭卡片）后传播取消
            _state.value = UiState()
            throw e
        }
    }

    /**
     * 导入用户手动下载的模型压缩包（复制 -> 解压两个阶段）
     * @return 是否成功；失败时进入 FAILED 阶段
     * 用户点击停止时取消导入协程，复位状态后向调用方传播取消
     */
    suspend fun import(uri: Uri): Boolean {
        val type = _state.value.type ?: return false
        _state.value = UiState(type, Phase.IMPORTING, -1f)
        try {
            val result = modelManager.installFromUri(
                type,
                uri,
                onProgress = { progress ->
                    // 本地文件复制阶段：进度未知
                    _state.value = UiState(type, Phase.IMPORTING, progress)
                },
                onExtract = { progress ->
                    // 解压阶段：带进度
                    _state.value = UiState(type, Phase.IMPORTING, progress)
                }
            )
            return if (result.isSuccess) {
                _state.value = UiState()
                true
            } else {
                _state.value = UiState(type, Phase.FAILED)
                false
            }
        } catch (e: CancellationException) {
            // 用户点击停止：复位状态（关闭卡片）后传播取消
            _state.value = UiState()
            throw e
        }
    }

    fun dismiss() {
        _state.value = UiState()
    }
}
