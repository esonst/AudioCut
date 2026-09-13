package com.example.mp3player.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.example.mp3player.data.model.AudioItem
import com.example.mp3player.data.model.LoopMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * 基于 ExoPlayer 的本地音频播放管理器
 * 提供后台播放支持、音频焦点管理、循环模式切换、0.5x~2.0x倍速调节、快进快退及毫秒级播放进度监听
 */
class AudioPlayerManager(
    private val context: Context
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var exoPlayer: ExoPlayer? = null


    // 状态流
    private val _currentAudio = MutableStateFlow<AudioItem?>(null)
    val currentAudio: StateFlow<AudioItem?> = _currentAudio.asStateFlow()

    private val _playlist = MutableStateFlow<List<AudioItem>>(emptyList())
    val playlist: StateFlow<List<AudioItem>> = _playlist.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _loopMode = MutableStateFlow(LoopMode.SEQUENCE)
    val loopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var progressJob: Job? = null

    init {
        initPlayer()
        startProgressTracker()
    }


    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            // 启用恒定码率近似 seek：无 ID3/Xing 头的裸 MP3 流（及 ADTS/AMR）默认不可跳转，
            // 时长为 TIME_UNSET 且所有 seek 都会被 SEEK_ADJUSTMENT 拉回 0，表现为进度强制跳回开头
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    context,
                    androidx.media3.extractor.DefaultExtractorsFactory()
                        .setConstantBitrateSeekingEnabled(true)
                )
            )
            .setAudioAttributes(audioAttributes, true) // 自动处理音频焦点 (Audio Focus)
            .setHandleAudioBecomingNoisy(true) // 耳机拔出自动暂停
            .setWakeMode(C.WAKE_MODE_LOCAL) // 防止休眠断播
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        mediaItem?.mediaId?.toLongOrNull()?.let { id ->
                            val audio = _playlist.value.find { it.id == id }
                            if (audio != null) {
                                _currentAudio.value = audio
                            }
                        }
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> _isBuffering.value = true
                            Player.STATE_READY -> {
                                _isBuffering.value = false
                                _durationMs.value = duration.coerceAtLeast(0L)
                            }
                            Player.STATE_ENDED -> {
                                _isBuffering.value = false
                                handleTrackEnded()
                            }
                            Player.STATE_IDLE -> {
                                _isBuffering.value = false
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        _playbackSpeed.value = playbackParameters.speed
                    }
                })
            }
    }

    /**
     * 启动进度定时轮询
     */
    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                try {
                    exoPlayer?.let { player ->
                        if (player.isPlaying) {
                            _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                            if (player.duration > 0) {
                                _durationMs.value = player.duration
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 避免轮询循环因瞬时异常静默退出，导致进度流永远不再更新
                }
                // 播放中高频刷新进度；暂停/空闲时降低轮询频率，减少空转功耗
                delay(if (exoPlayer?.isPlaying == true) 30L else 300L)
            }
        }
    }

    /**
     * 直接从播放器读取当前真实播放位置（毫秒）
     * 与 currentPositionMs 不同，不依赖进度轮询器的存活与刷新时机，
     * 供片段/裁剪试听的终点边界检测使用，确保能精准停在终点
     */
    fun peekPositionMs(): Long {
        return try {
            exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: _currentPositionMs.value
        } catch (e: Exception) {
            _currentPositionMs.value
        }
    }

    /**
     * 设置播放列表并播放指定歌曲
     */
    fun playAudio(audio: AudioItem, newPlaylist: List<AudioItem>? = null) {
        if (newPlaylist != null) {
            _playlist.value = newPlaylist

            // 点击的就是当前音频且播放器已就绪：不重建播放列表（setMediaItems 会让播放从 0 开始），保留进度直接续播
            if (_currentAudio.value?.id == audio.id && exoPlayer?.playbackState != Player.STATE_IDLE) {
                play()
                return
            }

            // 同步播放列表到 ExoPlayer 以支持系统级 上一曲/下一曲
            exoPlayer?.let { player ->
                val mediaItems = newPlaylist.map { item ->
                    val uri = if (item.contentUri != null) {
                        item.contentUri
                    } else {
                        android.net.Uri.fromFile(java.io.File(item.filePath))
                    }
                    MediaItem.Builder()
                        .setMediaId(item.id.toString())
                        .setUri(uri)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(item.title)
                                .setArtist(item.artist)
                                .build()
                        )
                        .build()
                }
                player.setMediaItems(mediaItems)
                val index = newPlaylist.indexOfFirst { it.id == audio.id }
                if (index >= 0) {
                    player.seekTo(index, 0L)
                }
                player.prepare()
                player.play()
                _isPlaying.value = true
                _currentAudio.value = audio
            }
            return
        }

        // 如果已经是当前音频且播放器已准备好，则直接播放
//        if (_currentAudio.value?.id == audio.id && exoPlayer?.playbackState != Player.STATE_IDLE) {
//            play()
//            return
//        }

        _currentAudio.value = audio
        val uri = if (audio.contentUri != null) audio.contentUri else android.net.Uri.fromFile(java.io.File(audio.filePath))
        val mediaItem = MediaItem.Builder()
            .setMediaId(audio.id.toString())
            .setUri(uri)
            .build()

        exoPlayer?.let { player ->
            player.setMediaItem(mediaItem)
            player.playbackParameters = PlaybackParameters(_playbackSpeed.value)
            player.prepare()
            player.play()
            _isPlaying.value = true
        }
    }

    fun play() {
        exoPlayer?.let { player ->
            // 仅在 IDLE 时需要 prepare；ENDED 状态下调用 prepare() 会被 Media3
            // 重置回媒体默认位置（0），覆盖掉刚执行的 seekTo 导致进度归零。
            // ENDED 下重播应显式 seekTo（调用方 seekToAndPlay/REPEAT_ONE 均已先 seek）。
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            when (player.playbackState) {
                Player.STATE_IDLE -> player.prepare()
                // 播放完毕后点播放：显式回到起点重播，避免 prepare() 把进度重置到默认位置
                Player.STATE_ENDED -> player.seekTo(0L)
                else -> {}
            }
            player.play()
        }
    }

    /**
     * 计算跳转目标位置：取所有时长来源的最大值作为钳制上限，
     * 避免单一来源异常（如播放器未就绪时为 0 或过期的极小时长）导致任何跳转都被钳到接近 0
     */
    private fun coerceSeekTarget(positionMs: Long): Long {
        val total = maxOf(
            _durationMs.value,
            _currentAudio.value?.durationMs ?: 0L,
            try { exoPlayer?.duration ?: 0L } catch (e: Exception) { 0L }
        )
        return if (total > 0L) positionMs.coerceIn(0L, total) else positionMs.coerceAtLeast(0L)
    }

    /**
     * 拖动跳转到指定毫秒
     */
    fun seekTo(positionMs: Long) {
        val target = coerceSeekTarget(positionMs)
        exoPlayer?.seekTo(target)
        _currentPositionMs.value = target
    }

    /**
     * 快进/快退指定秒数 (正数快进，负数快退)
     */
    fun fastForwardOrRewind(seconds: Int) {
        val current = _currentPositionMs.value
        seekTo(coerceSeekTarget(current + seconds * 1000L))
    }

    /**
     * 调节倍速：0.5x ~ 2.0x (步长0.1)
     */
    fun setPlaybackSpeed(speed: Float) {
        val clampedSpeed = ((speed * 10).toInt() / 10f).coerceIn(0.5f, 2.0f)
        _playbackSpeed.value = clampedSpeed
        exoPlayer?.playbackParameters = PlaybackParameters(clampedSpeed)
    }

    /**
     * 循环模式切换
     */
    fun toggleLoopMode(): LoopMode {
        val modes = LoopMode.entries
        val nextIndex = (_loopMode.value.ordinal + 1) % modes.size
        val nextMode = modes[nextIndex]
        _loopMode.value = nextMode
        return nextMode
    }

    fun setLoopMode(mode: LoopMode) {
        _loopMode.value = mode
        exoPlayer?.let { player ->
            when (mode) {
                LoopMode.REPEAT_ONE -> {
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.shuffleModeEnabled = false
                }
                LoopMode.REPEAT_ALL -> {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    player.shuffleModeEnabled = false
                }
                LoopMode.SHUFFLE -> {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    player.shuffleModeEnabled = true
                }
                LoopMode.SEQUENCE -> {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.shuffleModeEnabled = false
                }
            }
        }
    }

    /**
     * 上一曲
     */
    fun playPrevious() {
        val player = exoPlayer ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        } else {
            val currentList = _playlist.value
            if (currentList.isNotEmpty()) {
                playAudio(currentList.last())
            }
        }
    }

    /**
     * 下一曲
     */
    fun playNext() {
        val player = exoPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else {
            val currentList = _playlist.value
            if (currentList.isNotEmpty()) {
                playAudio(currentList.first())
            }
        }
    }

    /**
     * 当前歌曲播放完毕后的处理
     */
    private fun handleTrackEnded() {
        when (_loopMode.value) {
            LoopMode.REPEAT_ONE -> {
                seekTo(0)
                play()
            }
            LoopMode.REPEAT_ALL -> {
                playNext()
            }
            LoopMode.SHUFFLE -> {
                playNext()
            }
            LoopMode.SEQUENCE -> {
                val currentList = _playlist.value
                val current = _currentAudio.value
                if (current != null && currentList.isNotEmpty()) {
                    val currentIndex = currentList.indexOfFirst { it.id == current.id }
                    if (currentIndex >= 0 && currentIndex < currentList.size - 1) {
                        playAudio(currentList[currentIndex + 1])
                    } else {
                        pause()
                        seekTo(0)
                    }
                }
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        scope.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }

}
