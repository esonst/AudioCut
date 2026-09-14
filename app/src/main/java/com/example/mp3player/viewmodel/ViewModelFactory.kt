package com.example.mp3player.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.mp3player.di.AppContainer

/**
 * 通用 ViewModelFactory
 * 通过 AppContainer 注入依赖，手动管理 ViewModel 创建
 */
class ViewModelFactory(
    private val application: Application,
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) ->
                MainViewModel(application, container.eventBus, container.audioRepository, container.playerManager, container.prefs) as T

            modelClass.isAssignableFrom(AudioLibraryViewModel::class.java) ->
                AudioLibraryViewModel(application, container.eventBus, container.audioRepository, container.prefs, container.playerManager) as T

            modelClass.isAssignableFrom(TranscriptViewModel::class.java) ->
                TranscriptViewModel(application, container.eventBus, container.asrManager, container.prefs, container.playerManager) as T

            modelClass.isAssignableFrom(ClipViewModel::class.java) ->
                ClipViewModel(application, container.eventBus, container.audioCutter, container.prefs, container.playerManager) as T

            modelClass.isAssignableFrom(TrimViewModel::class.java) ->
                TrimViewModel(application, container.eventBus, container.audioCutter, container.prefs, container.playerManager) as T

            modelClass.isAssignableFrom(ConvertViewModel::class.java) ->
                ConvertViewModel(application, container.eventBus, container.playerManager) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(application, container.eventBus, container.prefs, container.asrManager) as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
