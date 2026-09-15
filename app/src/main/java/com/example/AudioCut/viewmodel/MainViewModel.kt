package com.example.audiocut.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.example.audiocut.core.AppEventBus
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.data.repository.AudioRepository
import com.example.audiocut.data.repository.PreferencesRepository
import com.example.audiocut.navigation.AppScreen
import com.example.audiocut.navigation.PlayerTab
import com.example.audiocut.player.AudioPlayerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全局核心 ViewModel
 * 负责导航、主播放器控制、应用生命周期、跨界面共享状态
 *
 * 通过手动 DI 注入所有依赖，通过 AppEventBus 与子 ViewModel 通信
 */
class MainViewModel(
    application: Application,
    eventBus: AppEventBus,
    val audioRepository: AudioRepository,
    val playerManager: AudioPlayerManager,
    val prefs: PreferencesRepository
) : BaseViewModel(application, eventBus) {

    // ==================== 页面与 Tab 状态 ====================
    private val _currentScreen = MutableStateFlow(AppScreen.AUDIO_LIBRARY)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _currentTab = MutableStateFlow(PlayerTab.TRANSCRIPT)
    val currentTab: StateFlow<PlayerTab> = _currentTab.asStateFlow()

    // 页面跳转事件（与 HorizontalPager 联动）
    private val _targetPage = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val targetPage: SharedFlow<Int> = _targetPage.asSharedFlow()

    // 全局 Toast 事件（透传 eventBus）
    val toastEvent: SharedFlow<String> = eventBus.toastEvent

    // ==================== 播放器状态（全局共享） ====================
    val currentPlayingAudio: StateFlow<AudioItem?> = playerManager.currentAudio
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val currentPositionMs: StateFlow<Long> = playerManager.currentPositionMs
    val durationMs: StateFlow<Long> = playerManager.durationMs
    val playbackSpeed: StateFlow<Float> = playerManager.playbackSpeed
    val loopMode: StateFlow<com.example.audiocut.data.model.LoopMode> = playerManager.loopMode
    val isBuffering: StateFlow<Boolean> = playerManager.isBuffering

    init {
        observeAndSaveGlobalState()
    }

    private fun observeAndSaveGlobalState() {
        // 持久化播放状态
        viewModelScope.launch {
            currentPlayingAudio.collect { prefs.saveLastPlayedAudioId(it?.id) }
        }
        viewModelScope.launch {
            loopMode.collect { prefs.saveLoopMode(it) }
        }
        viewModelScope.launch {
            playbackSpeed.collect { prefs.savePlaybackSpeed(it) }
        }
        // 周期性保存播放进度
        viewModelScope.launch {
            while (isActive) {
                delay(5000.milliseconds)
                if (isPlaying.value) {
                    prefs.saveLastPlayedPositionMs(currentPositionMs.value)
                }
            }
        }
    }

    /**
     * 恢复上次播放状态（由音频库加载完成后调用）
     */
    fun loadLastPlaybackState(audioList: List<AudioItem>) {
        viewModelScope.launch {
            val lastId = prefs.getLastPlayedAudioId()
            val lastPos = prefs.getLastPlayedPositionMs()
            val mode = prefs.getLoopMode()
            val speed = prefs.getPlaybackSpeed()

            playerManager.setLoopMode(mode)
            playerManager.setPlaybackSpeed(speed)

            if (lastId != null) {
                val audio = audioList.find { it.id == lastId }
                if (audio != null) {
                    playerManager.playAudio(audio, audioList)
                    playerManager.pause()
                    delay(500.milliseconds)
                    playerManager.seekTo(lastPos)
                }
            }
        }
    }

    // ==================== 导航方法 ====================
    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
        if (screen != AppScreen.AUDIO_LIBRARY) {
            _currentTab.value = PlayerTab.fromIndex(screen.pageIndex)
        }
        _targetPage.tryEmit(screen.pageIndex)
    }

    fun switchTab(tab: PlayerTab) {
        _currentTab.value = tab
        _currentScreen.value = AppScreen.fromIndex(tab.pageIndex)
        _targetPage.tryEmit(tab.pageIndex)
    }

    fun onPageScrolled(pageIndex: Int) {
        val screen = AppScreen.fromIndex(pageIndex)
        _currentScreen.value = screen
        if (screen != AppScreen.AUDIO_LIBRARY) {
            _currentTab.value = PlayerTab.fromIndex(pageIndex)
        }
    }

    fun navigateToConvertFormat(inputFile: String?) {
        eventBus.sendNavigateToConvert(inputFile)
        val targetScreen = AppScreen.CONVERT
        _currentScreen.value = targetScreen
        _currentTab.value = PlayerTab.fromIndex(targetScreen.pageIndex)
        _targetPage.tryEmit(targetScreen.pageIndex)
    }

    // ==================== 全局播放控制 ====================
    fun playAudio(audio: AudioItem, audioList: List<AudioItem>) {
        try {
            eventBus.sendStopAllPreview()
            playerManager.playAudio(audio, audioList)
        } catch (e: Exception) {
            emitToast("播放失败: ${e.message}")
        }
    }

    fun toggleMainPlayPause() {
        eventBus.sendStopAllPreview()
        playerManager.togglePlayPause()
    }

    fun mainSeekTo(positionMs: Long) {
        eventBus.sendStopAllPreview()
        playerManager.seekTo(positionMs)
    }

    fun mainFastForwardOrRewind(seconds: Int) {
        eventBus.sendStopAllPreview()
        playerManager.fastForwardOrRewind(seconds)
    }

    fun playMainPrevious() {
        eventBus.sendStopAllPreview()
        playerManager.playPrevious()
    }

    fun playMainNext() {
        eventBus.sendStopAllPreview()
        playerManager.playNext()
    }

    override fun onCleared() {
        playerManager.release()
        super.onCleared()
    }
}
