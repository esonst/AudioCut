package com.example.AudioCut.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AudioCut.core.AppEventBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 全局 BaseViewModel：封装 Toast 事件、通用状态与协程工具
 * 所有业务 ViewModel 继承此类，统一通过 AppEventBus 发送 Toast
 */
abstract class BaseViewModel(
    application: Application,
    protected val eventBus: AppEventBus
) : AndroidViewModel(application) {

    /** 发送 Toast 提示 */
    protected fun emitToast(msg: String) {
        eventBus.sendToast(msg)
    }

    /** 在 viewModelScope 中安全启动协程，异常自动上报 Toast */
    protected fun launchSafe(
        block: suspend () -> Unit
    ) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            emitToast("操作失败: ${e.localizedMessage}")
        }
    }
}
