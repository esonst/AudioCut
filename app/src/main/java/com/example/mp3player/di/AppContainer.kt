package com.example.mp3player.di

import android.app.Application
import com.example.mp3player.asr.AsrManager
import com.example.mp3player.core.AppEventBus
import com.example.mp3player.data.repository.AudioRepository
import com.example.mp3player.data.repository.PreferencesRepository
import com.example.mp3player.ffmpeg.AudioCutterConcatenator
import com.example.mp3player.player.AudioPlayerManager

/**
 * 手动依赖注入容器
 * 应用级单例，在 Application.onCreate 中初始化
 * 替代 Hilt，避免 KSP 注解处理器兼容性问题
 */
class AppContainer(application: Application) {
    val eventBus: AppEventBus by lazy { AppEventBus() }
    val prefs: PreferencesRepository by lazy { PreferencesRepository(application) }
    val audioRepository: AudioRepository by lazy { AudioRepository(application) }
    val playerManager: AudioPlayerManager by lazy { AudioPlayerManager(application) }
    val audioCutter: AudioCutterConcatenator by lazy { AudioCutterConcatenator(application) }
    val asrManager: AsrManager by lazy { AsrManager(application) }
}
