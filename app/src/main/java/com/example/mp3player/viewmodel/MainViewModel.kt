package com.example.mp3player.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.*
import com.example.mp3player.asr.OfflineAsrEngine
import com.example.mp3player.asr.WaveformExtractor
import com.example.mp3player.data.model.*
import com.example.mp3player.data.repository.AudioRepository
import com.example.mp3player.ffmpeg.AudioCutterConcatenator
import com.example.mp3player.ffmpeg.ExportAudioFormat
import com.example.mp3player.ffmpeg.ExportResult
import com.example.mp3player.player.AudioPlayerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 导航页面定义（支持音乐库-文稿-剪辑-裁剪-格式转换-设置 六屏左右滑动切换）
 */
enum class AppScreen(val pageIndex: Int) {
    AUDIO_LIBRARY(0), // 音乐库
    TRANSCRIPT(1),    // 文稿
    CLIP(2),          // 剪辑
    TRIM(3),          // 裁剪（删除所选部分）
    CONVERT(4),        // 格式转换
    SETTINGS(5);      // 设置

    companion object {
        fun fromIndex(index: Int): AppScreen = when (index) {
            0 -> AUDIO_LIBRARY
            1 -> TRANSCRIPT
            2 -> CLIP
            3 -> TRIM
            4 -> CONVERT
            5 -> SETTINGS
            else -> AUDIO_LIBRARY
        }
    }
}

/**
 * 顶部 Tab 栏定义（文稿、剪辑 两个界面）
 */
enum class PlayerTab(val pageIndex: Int) {
    TRANSCRIPT(1), // 【文稿】
    CLIP(2);       // 【剪辑】

    companion object {
        fun fromIndex(index: Int): PlayerTab = when (index) {
            1 -> TRANSCRIPT
            2 -> CLIP
            else -> TRANSCRIPT
        }
    }
}

/**
 * 核心统一业务 ViewModel
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioRepository = AudioRepository(application)
    val playerManager = AudioPlayerManager(application)
    private val asrEngine = OfflineAsrEngine(application)
    private val waveformExtractor = WaveformExtractor(application)
    private val audioCutter = AudioCutterConcatenator(application)
    private val prefs = com.example.mp3player.data.repository.PreferencesRepository(application)

    // 页面与Tab状态
    private val _currentScreen = MutableStateFlow(AppScreen.AUDIO_LIBRARY)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _currentTab = MutableStateFlow(PlayerTab.TRANSCRIPT)
    val currentTab: StateFlow<PlayerTab> = _currentTab.asStateFlow()

    // 扫描与列表数据
    private val _allAudios = MutableStateFlow<List<AudioItem>>(emptyList())
    val allAudios: StateFlow<List<AudioItem>> = _allAudios.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _browseMode = MutableStateFlow(BrowseMode.ALL)
    val browseMode: StateFlow<BrowseMode> = _browseMode.asStateFlow()

    private val _sortField = MutableStateFlow(prefs.getSortField())
    val sortField: StateFlow<SortField> = _sortField.asStateFlow()

    private val _sortDirection = MutableStateFlow(prefs.getSortDirection())
    val sortDirection: StateFlow<SortDirection> = _sortDirection.asStateFlow()

    private val _durationFilter = MutableStateFlow(DurationFilter.ALL)
    val durationFilter: StateFlow<DurationFilter> = _durationFilter.asStateFlow()

    private val _sizeFilter = MutableStateFlow(SizeFilter.ALL)
    val sizeFilter: StateFlow<SizeFilter> = _sizeFilter.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // 扫描设备所有文件的结果（用于“扫描导入”界面）
    private val _deviceAudios = MutableStateFlow<List<AudioItem>>(emptyList())
    val deviceAudios: StateFlow<List<AudioItem>> = _deviceAudios.asStateFlow()

    private val _isScanningDevice = MutableStateFlow(false)
    val isScanningDevice: StateFlow<Boolean> = _isScanningDevice.asStateFlow()

    // 多选删除状态
    private val _selectedAudioIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedAudioIds: StateFlow<Set<Long>> = _selectedAudioIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = _selectedAudioIds.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 收藏状态集合
    private val _favoriteIds = MutableStateFlow(prefs.getFavoriteIds())
    val favoriteIds: StateFlow<Set<Long>> = _favoriteIds.asStateFlow()

    // 新导入音频的高亮提示（在音频库中闪烁一下，不跳转页面）
    private val _highlightedAudioId = MutableStateFlow<Long?>(null)
    val highlightedAudioId: StateFlow<Long?> = _highlightedAudioId.asStateFlow()

    private var highlightJob: Job? = null

    /**
     * 标记新导入的音频在音频库中闪烁高亮，短暂显示后自动清除
     */
    fun flashImportedAudio(audioId: Long) {
        highlightJob?.cancel()
        _highlightedAudioId.value = audioId
        highlightJob = viewModelScope.launch {
            delay(2500.milliseconds)
            if (_highlightedAudioId.value == audioId) {
                _highlightedAudioId.value = null
            }
        }
    }

    // 过滤与排序后的音频列表
    val displayAudios: StateFlow<List<AudioItem>> = combine(
        _allAudios,
        _searchQuery,
        _sortField,
        _sortDirection,
        combine(_durationFilter, _sizeFilter) { d, s -> Pair(d, s) }
    ) { audios, query, field, direction, filters ->
        val filtered = audioRepository.filterAudios(audios, query, filters.first, filters.second)
        audioRepository.sortAudios(filtered, field, direction)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 文件夹分组数据
    val groupedAudios: StateFlow<Map<String, List<AudioItem>>> = displayAudios.map {
        audioRepository.groupByFolder(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // 播放器状态传递
    val currentPlayingAudio = playerManager.currentAudio
    val isPlaying = playerManager.isPlaying
    val currentPositionMs = playerManager.currentPositionMs
    val durationMs = playerManager.durationMs
    val playbackSpeed = playerManager.playbackSpeed
    val loopMode = playerManager.loopMode
    val isBuffering = playerManager.isBuffering

    // ASR 与文稿状态
    private val _isAsrLoading = MutableStateFlow(false)
    val isAsrLoading: StateFlow<Boolean> = _isAsrLoading.asStateFlow()

    private val _asrProgress = MutableStateFlow(0f)
    val asrProgress: StateFlow<Float> = _asrProgress.asStateFlow()

    private val _asrProgressText = MutableStateFlow("")
    val asrProgressText: StateFlow<String> = _asrProgressText.asStateFlow()

    private var asrJob: Job? = null

    private val _transcriptResult = MutableStateFlow<TranscriptResult?>(null)
    val transcriptResult: StateFlow<TranscriptResult?> = _transcriptResult.asStateFlow()

    private val _selectedWordIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedWordIds: StateFlow<Set<Long>> = _selectedWordIds.asStateFlow()

    // 剪辑片段列表
    private val _segments = MutableStateFlow<List<AudioSegment>>(emptyList())
    val segments: StateFlow<List<AudioSegment>> = _segments.asStateFlow()

    // 片段试听预览状态
    private val _previewingSegmentId = MutableStateFlow<String?>(null)
    val previewingSegmentId: StateFlow<String?> = _previewingSegmentId.asStateFlow()

    private var previewJob: Job? = null

    // 优化：预计算哪些字在标记片段中，避免在 UI 渲染循环中进行 O(W*S) 查找
    val wordsInSegmentsIds: StateFlow<Set<Long>> = combine(_transcriptResult, _segments) { transcript, segs ->
        if (transcript == null || segs.isEmpty()) return@combine emptySet()
        // 将片段合并为有序且互不重叠的区间后二分查找，整体复杂度从 O(W*S) 降为 O(W log S)
        val merged = mutableListOf<Pair<Long, Long>>()
        for ((_, _, _, startMs, endMs) in segs.sortedBy { it.startMs }) {
            val last = merged.lastOrNull()
            if (last != null && startMs <= last.second) {
                merged[merged.size - 1] = last.first to maxOf(last.second, endMs)
            } else {
                merged.add(startMs to endMs)
            }
        }
        val starts = merged.map { it.first }
        transcript.words.filter { w ->
            // 命中条件：词完全落在某个区间内；合并区间按 start 升序且不重叠，
            // 故只需检查 start <= w.startMs 的最后一个区间
            val idx = starts.binarySearch(w.startMs).let { if (it >= 0) it else -it - 2 }
            idx >= 0 && w.endMs <= merged[idx].second
        }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // 裁剪区间列表（复用 AudioSegment 结构，与剪辑片段分开存储与导出）
    private val _trimRanges = MutableStateFlow<List<AudioSegment>>(emptyList())
    val trimRanges: StateFlow<List<AudioSegment>> = _trimRanges.asStateFlow()

    // 裁剪预览与导出状态
    private val _isGeneratingTrimPreview = MutableStateFlow(false)
    val isGeneratingTrimPreview: StateFlow<Boolean> = _isGeneratingTrimPreview.asStateFlow()

    private val _trimPreviewResult = MutableStateFlow<ExportResult?>(null)
    val trimPreviewResult: StateFlow<ExportResult?> = _trimPreviewResult.asStateFlow()

    private val _isTrimExporting = MutableStateFlow(false)
    val isTrimExporting: StateFlow<Boolean> = _isTrimExporting.asStateFlow()

    private val _trimExportProgress = MutableStateFlow(0f)
    val trimExportProgress: StateFlow<Float> = _trimExportProgress.asStateFlow()

    // ASR 调试与 VAD 配置
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

    // 波形数据
    private val _waveformPoints = MutableStateFlow<List<Float>>(emptyList())
    val waveformPoints: StateFlow<List<Float>> = _waveformPoints.asStateFlow()

    private val _isExtractingWaveform = MutableStateFlow(false)
    val isExtractingWaveform: StateFlow<Boolean> = _isExtractingWaveform.asStateFlow()

    // 导出状态
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    // 合并预览试听播放器与状态（合并试听无需在系统通知栏展示常驻控制）
    val mergedPreviewPlayer = AudioPlayerManager(application)

    private val _isGeneratingMergedPreview = MutableStateFlow(false)
    val isGeneratingMergedPreview: StateFlow<Boolean> = _isGeneratingMergedPreview.asStateFlow()

    private val _mergedPreviewResult = MutableStateFlow<ExportResult?>(null)
    val mergedPreviewResult: StateFlow<ExportResult?> = _mergedPreviewResult.asStateFlow()

    val isMergedPreviewPlaying: StateFlow<Boolean> = mergedPreviewPlayer.isPlaying
    val mergedPreviewPositionMs: StateFlow<Long> = mergedPreviewPlayer.currentPositionMs
    val mergedPreviewDurationMs: StateFlow<Long> = mergedPreviewPlayer.durationMs
    val mergedPreviewSpeed: StateFlow<Float> = mergedPreviewPlayer.playbackSpeed

    // Toast 提示
    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    // 格式转换相关状态
    private val _convertInputFile = MutableStateFlow<String?>(null)
    val convertInputFile: StateFlow<String?> = _convertInputFile.asStateFlow()
    
    private val _convertState = MutableStateFlow(ConvertState())
    val convertState: StateFlow<ConvertState> = _convertState.asStateFlow()

    // 当前正在执行的转换会话（用于取消与进度跟踪）
    @Volatile private var convertSessionId: Long = -1L
    @Volatile private var convertCancelled = false
    private var convertTotalDurationMs = 0L
    private var convertStartTime = 0L

    // 页面跳转目标（与 HorizontalPager 联动）
    private val _targetPage = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val targetPage: SharedFlow<Int> = _targetPage.asSharedFlow()

    init {
        scanAudios()
        loadLastPlaybackState()
        observeAndSaveState()
    }

    private fun loadLastPlaybackState() {
        viewModelScope.launch {
            // 等待列表加载完成
            _allAudios.filter { it.isNotEmpty() }.first()
            
            val lastId = prefs.getLastPlayedAudioId()
            val lastPos = prefs.getLastPlayedPositionMs()
            val mode = prefs.getLoopMode()
            val speed = prefs.getPlaybackSpeed()
            
            playerManager.setLoopMode(mode)
            playerManager.setPlaybackSpeed(speed)
            
            if (lastId != null) {
                val audio = _allAudios.value.find { it.id == lastId }
                if (audio != null) {
                    playerManager.playAudio(audio, _allAudios.value)
                    playerManager.pause()
                    delay(500.milliseconds)
                    playerManager.seekTo(lastPos)
                    loadAudioDetails(audio)
                }
            }
        }
    }

    private fun observeAndSaveState() {
        viewModelScope.launch {
            favoriteIds.collect { prefs.saveFavoriteIds(it) }
        }
        viewModelScope.launch {
            sortField.collect { prefs.saveSortField(it) }
        }
        viewModelScope.launch {
            sortDirection.collect { prefs.saveSortDirection(it) }
        }
        viewModelScope.launch {
            currentPlayingAudio.collect { audio ->
                prefs.saveLastPlayedAudioId(audio?.id)
            }
        }
        viewModelScope.launch {
            loopMode.collect { prefs.saveLoopMode(it) }
        }
        viewModelScope.launch {
            playbackSpeed.collect { prefs.savePlaybackSpeed(it) }
        }
        // 周期性保存进度
        viewModelScope.launch {
            while (isActive) {
                delay(5000.milliseconds)
                if (isPlaying.value) {
                    prefs.saveLastPlayedPositionMs(currentPositionMs.value)
                }
            }
        }
        // 保存片段和文稿
        viewModelScope.launch {
            segments.collect { list ->
                currentPlayingAudio.value?.id?.let { id ->
                    prefs.saveSegmentsForAudio(id, list)
                }
                // 片段列表任何变动（包括勾选、排序、范围），都使合并预览缓存失效
                _mergedPreviewResult.value = null
            }
        }
        // 保存裁剪区间并使裁剪预览缓存失效
        viewModelScope.launch {
            trimRanges.collect { list ->
                currentPlayingAudio.value?.id?.let { id ->
                    prefs.saveTrimRangesForAudio(id, list)
                }
                _trimPreviewResult.value = null
            }
        }
    }

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
        // 设置转换输入文件
        _convertInputFile.value = inputFile
        
        // 跳转到格式转换页面
        val targetScreen = AppScreen.CONVERT
        _currentScreen.value = targetScreen
        _currentTab.value = PlayerTab.fromIndex(targetScreen.pageIndex)
        _targetPage.tryEmit(targetScreen.pageIndex)
    }

    /**
     * 扫描音频库，优先使用缓存保证「打开即可见」，避免重复扫描：
     * - force=false 且内存已有数据时直接跳过（切页/重复打开音频库零开销）
     * - 重启后首次打开先从磁盘缓存快速恢复展示，再在后台静默刷新
     * - force=true 用于手动刷新按钮以及导入/删除/覆盖等需要立即更新列表的场景
     */
    fun scanAudios(force: Boolean = false) {
        // 内存缓存命中：直接展示，不再重扫
        if (!force && _allAudios.value.isNotEmpty()) return

        // 重启后首次打开：先用磁盘缓存快速恢复，保证立即看到列表
        if (!force && _allAudios.value.isEmpty()) {
            val cached = prefs.getAudioLibraryCache()
            if (cached.isNotEmpty()) {
                _allAudios.value = cached
            }
        }

        viewModelScope.launch {
            _isScanning.value = true
            // 仅扫描已导入的音频，并过滤用户选择「仅删除记录」的文件
            val hiddenPaths = prefs.getHiddenFilePaths()
            val list = audioRepository.scanImportedAudios()
                .filter { it.filePath !in hiddenPaths }
            _allAudios.value = list
            _isScanning.value = false
            // 持久化缓存，下次启动可直接恢复
            prefs.saveAudioLibraryCache(list)
            prefs.cleanupHiddenFilePaths()
        }
    }

    /**
     * 扫描整个设备的音频文件（用于主动导入）
     */
    fun scanDeviceAudios() {
        viewModelScope.launch {
            _isScanningDevice.value = true
            val list = audioRepository.scanLocalAudios()
            _deviceAudios.value = list
            _isScanningDevice.value = false
        }
    }

    /**
     * 导入音频文件
     */
    fun importAudio(uri: android.net.Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            val imported = audioRepository.importAudioFile(uri)
            if (imported != null) {
                emitToast("已导入: ${imported.title}")
                prefs.removeHiddenFilePath(imported.filePath)
                scanAudios(force = true) // 刷新列表
            } else {
                emitToast("导入失败")
            }
            _isScanning.value = false
        }
    }

    fun importMultipleAudios(uris: List<android.net.Uri>) {
        viewModelScope.launch {
            _isScanning.value = true
            var count = 0
            var lastImported: AudioItem? = null
            uris.forEach { uri ->
                val imported = audioRepository.importAudioFile(uri)
                if (imported != null) {
                    count++
                    lastImported = imported
                }
            }
            emitToast("成功导入 $count 个文件")
            lastImported?.let { prefs.removeHiddenFilePath(it.filePath) }
            scanAudios(force = true)
            // 在音频库中闪烁提示新导入的音频，不跳转页面
            lastImported?.let { flashImportedAudio(it.id) }
            _isScanning.value = false
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setBrowseMode(mode: BrowseMode) {
        _browseMode.value = mode
    }

    fun setSort(field: SortField, direction: SortDirection) {
        _sortField.value = field
        _sortDirection.value = direction
    }

    fun setFilters(duration: DurationFilter, size: SizeFilter) {
        _durationFilter.value = duration
        _sizeFilter.value = size
    }

    // --- 多选删除逻辑 ---
    fun toggleAudioSelection(audioId: Long) {
        val current = _selectedAudioIds.value.toMutableSet()
        if (current.contains(audioId)) {
            current.remove(audioId)
        } else {
            current.add(audioId)
        }
        _selectedAudioIds.value = current
    }

    fun clearSelection() {
        _selectedAudioIds.value = emptySet()
    }

    fun deleteSelectedAudios() {
        val idsToDelete = _selectedAudioIds.value
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            var deletedCount = 0
            val allAudiosList = _allAudios.value
            
            idsToDelete.forEach { id ->
                val audio = allAudiosList.find { it.id == id }
                if (audio != null) {
                    // 1. 删除关联数据 (文稿、片段)
                    prefs.deleteAudioData(id)
                    
                    // 2. 如果是内部导入的文件，物理删除
                    val file = File(audio.filePath)
                    val internalDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    if (internalDir != null && audio.filePath.startsWith(internalDir.absolutePath)) {
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                    
                    // 3. 如果当前正在播放，停止播放
                    if (playerManager.currentAudio.value?.id == id) {
                        withContext(Dispatchers.Main) {
                            playerManager.pause()
                            _transcriptResult.value = null
                            _segments.value = emptyList()
                        }
                    }
                    deletedCount++
                }
            }
            
            withContext(Dispatchers.Main) {
                clearSelection()
                // 直接更新缓存列表而非全量重新扫描，避免删除后再次长时间加载
                _allAudios.value = _allAudios.value.filter { it.id !in idsToDelete }
                prefs.saveAudioLibraryCache(_allAudios.value)
                emitToast("已成功删除 $deletedCount 条记录")
            }
        }
    }

    /**
     * 删除单条音频（长按菜单 → 删除确认卡片）
     * @param deleteFile true=连同原文件一起删除；false=仅删除这条记录（保留原文件）
     */
    fun deleteAudio(audioId: Long, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val audio = _allAudios.value.find { it.id == audioId } ?: return@launch
            try {
                // 1. 删除关联数据（文稿、片段、收藏等）
                prefs.deleteAudioData(audioId)

                if (deleteFile) {
                    // 2a. 连原文件一起删除（仅限 App 内部导入目录，避免误删外部文件）
                    val file = File(audio.filePath)
                    val internalDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    if (internalDir != null && audio.filePath.startsWith(internalDir.absolutePath) && file.exists()) {
                        file.delete()
                    }
                    prefs.removeHiddenFilePath(audio.filePath)
                } else {
                    // 2b. 仅删除记录：记住文件路径，防止下次扫描时重新出现
                    prefs.addHiddenFilePath(audio.filePath)
                }

                // 3. 若正在播放该音频则停止
                if (playerManager.currentAudio.value?.id == audioId) {
                    withContext(Dispatchers.Main) {
                        playerManager.pause()
                        _transcriptResult.value = null
                        _segments.value = emptyList()
                        _trimRanges.value = emptyList()
                    }
                }

                // 4. 从列表移除并更新持久化缓存
                withContext(Dispatchers.Main) {
                    _allAudios.value = _allAudios.value.filter { it.id != audioId }
                    clearSelection()
                    prefs.saveAudioLibraryCache(_allAudios.value)
                    emitToast(if (deleteFile) "已删除音频及原文件" else "已从音频库移除（原文件已保留）")
                }
            } catch (e: Exception) {
                emitToast("删除失败: ${e.localizedMessage}")
            }
        }
    }

    fun toggleFavorite(audioId: Long) {
        val current = _favoriteIds.value.toMutableSet()
        if (current.contains(audioId)) {
            current.remove(audioId)
            emitToast("已取消收藏")
        } else {
            current.add(audioId)
            emitToast("已添加至收藏")
        }
        _favoriteIds.value = current
    }

    fun playAudio(audio: AudioItem) {
        try {
            stopAnyPreview()
            playerManager.playAudio(audio, _allAudios.value)
            loadAudioDetails(audio)
        } catch (e: Exception) {
            emitToast("播放失败: ${e.message}")
        }
    }

    /**
     * 处理外部传入的音频/视频 URI (通过 Open With 或 Share)
     */
    fun handleExternalUri(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isScanning.value = true
                val imported = audioRepository.importAudioFile(uri)
                if (imported != null) {
                    emitToast("已从外部应用导入: ${imported.title}")
                    prefs.removeHiddenFilePath(imported.filePath)
                    scanAudios(force = true) // 刷新列表
                    // 仅添加到音频库并闪烁提示新项，不自动加载播放，也不跳转页面
                    flashImportedAudio(imported.id)
                } else {
                    emitToast("导入外部文件失败")
                }
            } catch (e: Exception) {
                emitToast("打开文件失败: ${e.message}")
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun loadAudioDetails(audio: AudioItem) {
        // 重置/加载识别结果与选区
        _transcriptResult.value = prefs.getCachedTranscript(audio.id)
        _selectedWordIds.value = emptySet()
        _segments.value = prefs.getSegmentsForAudio(audio.id)
        _trimRanges.value = prefs.getTrimRangesForAudio(audio.id)

        // 提取音频波形数据
        viewModelScope.launch {
            try {
                _isExtractingWaveform.value = true
                val points = waveformExtractor.extractWaveform(audio.filePath, audio.contentUri, 300)
                _waveformPoints.value = points
            } catch (e: Exception) {
            } finally {
                _isExtractingWaveform.value = false
            }
        }
    }

    /**
     * 触发本地离线 ASR 识别（支持断点续传与即时分片输出）
     * @param resumeIfPossible 若存在未完成的文稿识别，是否从中断处继续生成；若为 false，则重新开始识别
     */
    fun startAsrRecognition(resumeIfPossible: Boolean = true) {
        val current = currentPlayingAudio.value ?: run {
            emitToast("请先选择播放音频")
            return
        }

        if (asrJob?.isActive == true) {
            emitToast("已有正在进行的识别任务")
            return
        }

        asrJob = viewModelScope.launch {
            try {
                _isAsrLoading.value = true
                val currentChunkSec = _asrChunkSeconds.value
                val isSlicing = _enableSlicing.value
                
                val cached = _transcriptResult.value ?: prefs.getCachedTranscript(current.id)
                val canResume = resumeIfPossible && cached != null && !cached.isCompleted && cached.processedDurationMs > 0L
                val startOffsetMs = if (canResume) cached!!.processedDurationMs else 0L
                val existingWords = if (canResume) cached!!.words else emptyList()

                val totalMs = maxOf(1L, current.durationMs)
                // 如果关闭切片，则将 chunkTargetMs 设为总时长
                val chunkTargetMs = if (isSlicing) currentChunkSec * 1000L else totalMs
                val totalChunks = if (isSlicing) (totalMs / chunkTargetMs + 1).toInt() else 1

                if (canResume) {
                    emitToast("正在从 ${startOffsetMs / 1000}s 继续生成文稿...")
                    _asrProgress.value = (startOffsetMs.toFloat() / totalMs).coerceIn(0f, 0.95f)
                    val currentChunk = (startOffsetMs / chunkTargetMs).toInt()
                    _asrProgressText.value = "[$currentChunk/$totalChunks]"
                } else {
                    _asrProgress.value = 0f
                    _asrProgressText.value = "[0/$totalChunks]"
                }

                val result = asrEngine.transcribeAudio(
                    audio = current,
                    startOffsetMs = startOffsetMs,
                    existingWords = existingWords,
                    useVad = true, // VAD 永远打开
                    chunkTargetMs = chunkTargetMs,
                    onPartialResult = { partial ->
                        _transcriptResult.value = partial
                        prefs.saveCachedTranscript(current.id, partial)
                        
                        val processedMs = partial.processedDurationMs
                        val currentChunk = (processedMs / chunkTargetMs).toInt()
                        _asrProgressText.value = "[$currentChunk/$totalChunks]"
                    },
                    onProgress = { progress ->
                        _asrProgress.value = progress
                    }
                )

                _transcriptResult.value = result
                prefs.saveCachedTranscript(current.id, result)
                if (result.words.isNotEmpty()) {
                    emitToast("本地语音识别完成")
                } else {
                    emitToast("未检测到清晰人声语音")
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    emitToast("识别任务已停止")
                } else {
                    emitToast("语音识别失败")
                }
            } finally {
                _isAsrLoading.value = false
                _asrProgressText.value = ""
            }
        }
    }

    fun stopAsrRecognition() {
        if (asrJob?.isActive == true) {
            asrJob?.cancel()
            asrJob = null
            _isAsrLoading.value = false
        }
    }

    /**
     * 删除当前音频的文稿缓存
     */
    fun deleteCurrentTranscript() {
        val current = currentPlayingAudio.value ?: return
        _transcriptResult.value = null
        prefs.saveCachedTranscript(current.id, null)
        emitToast("文稿已删除")
    }

    /**
     * 清理所有已识别的文稿
     */
    fun clearAllTranscripts() {
        // 这个逻辑通常在 PreferencesRepository 层面批量删除 KEY_TRANSCRIPT_PREFIX 开头的项
        prefs.clearAllTranscripts()
        _transcriptResult.value = null
        emitToast("所有文稿缓存已清理")
    }

    /**
     * 清理所有导出的音频文件
     */
    fun clearAllExports() {
        viewModelScope.launch(Dispatchers.IO) {
            val outputDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            var count = 0
            outputDir?.listFiles()?.forEach { 
                if (it.isFile) {
                    it.delete()
                    count++
                }
            }
            withContext(Dispatchers.Main) {
                emitToast("已清理 $count 个导出文件")
            }
        }
    }

    private val _selectedTextRange = MutableStateFlow<androidx.compose.ui.text.TextRange?>(null)
    val selectedTextRange: StateFlow<androidx.compose.ui.text.TextRange?> = _selectedTextRange.asStateFlow()

    fun setSelectedTextRange(range: androidx.compose.ui.text.TextRange?) {
        _selectedTextRange.value = range
    }

    /**
     * 解析选中文本范围对应的时间区间与摘要片段
     * 优化：统一文稿选区→时间区间的换算逻辑，供剪辑片段与裁剪卡片复用
     */
    private fun resolveSelectionRange(
        transcript: TranscriptResult,
        start: Int,
        end: Int
    ): Triple<Long, Long, String>? {
        var currentIdx = 0
        var startMs = -1L
        var endMs = -1L
        var snippet = ""

        for ((_, sentences) in transcript.paragraphs) {
            for ((_, _, _, _, words) in sentences) {
                for ((_, word1, startMs1, endMs1) in words) {
                    val wordStart = currentIdx
                    val wordEnd = currentIdx + word1.length

                    if (start < wordEnd && end > wordStart) {
                        if (startMs == -1L || startMs1 < startMs) {
                            startMs = startMs1
                        }
                        if (endMs == -1L || endMs1 > endMs) {
                            endMs = endMs1
                        }
                        if (snippet.length < 30) {
                            snippet += word1
                        }
                    }

                    currentIdx = wordEnd
                }
            }
            currentIdx += 2 // \n\n
        }

        return if (startMs != -1L && endMs != -1L) Triple(startMs, endMs, snippet) else null
    }

    /**
     * 根据选中文本范围创建标记片段
     * 优化：支持从外部传入全文，确保索引一致性
     */
    fun createSegmentFromTextSelection(fullText: String) {
        val range = _selectedTextRange.value ?: return
        if (range.collapsed) return

        val transcript = _transcriptResult.value ?: return
        val resolved = resolveSelectionRange(transcript, range.min, range.max) ?: return

        val newSegment = AudioSegment(
            audioId = currentPlayingAudio.value?.id ?: 0L,
            title = "剪辑: ${resolved.third.take(15)}",
            startMs = resolved.first,
            endMs = resolved.second,
            colorIndex = _segments.value.size % 5
        )
        _segments.value += newSegment
        emitToast("已生成剪辑片段")
        // 生成后清除选区
        _selectedTextRange.value = null
    }

    /**
     * 根据选中文本范围创建裁剪卡片（删除所选部分）
     */
    fun createTrimRangeFromTextSelection(fullText: String) {
        val range = _selectedTextRange.value ?: return
        if (range.collapsed) return

        val transcript = _transcriptResult.value ?: return
        val resolved = resolveSelectionRange(transcript, range.min, range.max) ?: return

        val newTrim = AudioSegment(
            audioId = currentPlayingAudio.value?.id ?: 0L,
            title = "裁剪: ${resolved.third.take(15)}",
            startMs = resolved.first,
            endMs = resolved.second,
            colorIndex = _trimRanges.value.size % 5
        )
        _trimRanges.value += newTrim
        emitToast("已添加裁剪卡片，可前往【裁剪】页处理")
        // 生成后清除选区
        _selectedTextRange.value = null
    }

    /**
     * 手动在当前播放位置创建一个固定时长（如5秒）的裁剪区间
     */
    fun createManualTrimAtCurrentPos() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }
        val pos = currentPositionMs.value
        val duration = durationMs.value.coerceAtLeast(audio.durationMs)

        val startMs = pos.coerceIn(0L, maxOf(0L, duration - 1000L))
        val endMs = (startMs + 5000L).coerceAtMost(duration)

        val newTrim = AudioSegment(
            audioId = audio.id,
            title = "裁剪段 ${_trimRanges.value.size + 1}",
            startMs = startMs,
            endMs = endMs,
            colorIndex = _trimRanges.value.size % 5
        )
        _trimRanges.value += newTrim
        emitToast("已在当前位置创建裁剪卡片")
    }

    fun updateTrimRange(trimId: String, newStartMs: Long, newEndMs: Long) {
        _trimRanges.value = _trimRanges.value.map {
            if (it.id == trimId) {
                it.copy(
                    startMs = newStartMs.coerceAtLeast(0L),
                    endMs = newEndMs.coerceAtLeast(newStartMs + 100L)
                )
            } else it
        }
    }

    fun renameTrimRange(trimId: String, newTitle: String) {
        _trimRanges.value = _trimRanges.value.map {
            if (it.id == trimId) it.copy(title = newTitle) else it
        }
    }

    fun toggleTrimSelected(trimId: String) {
        _trimRanges.value = _trimRanges.value.map {
            if (it.id == trimId) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun deleteTrimRange(trimId: String) {
        if (_previewingSegmentId.value == trimId) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }
        _trimRanges.value = _trimRanges.value.filter { it.id != trimId }
        emitToast("裁剪卡片已删除")
    }

    /**
     * 裁剪卡片专属试听（预览被剪掉的部分在原音频中的内容）
     */
    fun previewTrimRange(trim: AudioSegment) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }

        // 如果当前正在播放此裁剪段，点击暂停
        if (_previewingSegmentId.value == trim.id && isPlaying.value) {
            playerManager.pause()
            previewJob?.cancel()
            previewJob = null
            _previewingSegmentId.value = null
            return
        }

        // 停止合并预览试听
        mergedPreviewPlayer.pause()

        // 确保播放器加载了当前音频
        if (playerManager.currentAudio.value?.id != audio.id) {
            playerManager.playAudio(audio, _allAudios.value)
        }

        _previewingSegmentId.value = trim.id

        // 强制 Seek 到起点并播放
        playerManager.seekTo(trim.startMs)
        playerManager.play()

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(100.milliseconds) // 等待状态同步
            while (isActive && _previewingSegmentId.value == trim.id) {
                val currentTrim = _trimRanges.value.find { it.id == trim.id }
                if (currentTrim == null) {
                    playerManager.pause()
                    _previewingSegmentId.value = null
                    break
                }

                val pos = playerManager.peekPositionMs()

                // 边界检测：到达终点（预留 30ms 提前量保证听感上精准停在终点）
                // 或落后起点太远（用户手动拖动）则停止
                if (pos >= currentTrim.endMs - 30L || pos < currentTrim.startMs - 500L) {
                    playerManager.pause()
                    if (pos >= currentTrim.endMs - 30L) {
                        playerManager.seekTo(currentTrim.startMs)
                    }
                    _previewingSegmentId.value = null
                    break
                }
                delay(20.milliseconds)
            }
        }
    }

    /**
     * 计算裁剪后保留的区间：将勾选的裁剪区间合并去重后，取原音频的补集
     */
    private fun computeKeepSegments(): List<AudioSegment> {
        val audio = currentPlayingAudio.value ?: return emptyList()
        val totalMs = maxOf(durationMs.value, audio.durationMs)

        val cuts = _trimRanges.value
            .filter { it.isSelected && it.endMs > it.startMs }
            .sortedBy { it.startMs }

        // 合并重叠的裁剪区间
        val merged = mutableListOf<Pair<Long, Long>>()
        for ((_, _, _, startMs, endMs) in cuts) {
            val last = merged.lastOrNull()
            if (last != null && startMs <= last.second) {
                merged[merged.size - 1] = last.first to maxOf(last.second, endMs)
            } else {
                merged.add(startMs to endMs)
            }
        }

        // 取补集作为保留区间
        val keeps = mutableListOf<AudioSegment>()
        var cursor = 0L
        for ((s, e) in merged) {
            if (s > cursor) {
                keeps.add(AudioSegment(audioId = audio.id, title = "保留段", startMs = cursor, endMs = s))
            }
            cursor = maxOf(cursor, e)
        }
        if (cursor < totalMs) {
            keeps.add(AudioSegment(audioId = audio.id, title = "保留段", startMs = cursor, endMs = totalMs))
        }
        // 过滤过短的保留段（<100ms），避免产生杂音碎片
        return keeps.filter { it.endMs - it.startMs >= 100L }
    }

    /**
     * 生成或播放裁剪预览：将原音频中勾选的部分剪掉后拼接播放
     */
    fun startOrToggleTrimPreview() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频")
            return
        }

        // 停止主播放器及单卡片试听
        if (playerManager.isPlaying.value || _previewingSegmentId.value != null) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }

        // 已生成过预览则直接切换播放状态
        val currentPreview = _trimPreviewResult.value
        if (currentPreview != null && currentPreview.isSuccess && File(currentPreview.outputPath).exists()) {
            mergedPreviewPlayer.togglePlayPause()
            return
        }

        viewModelScope.launch {
            _isGeneratingTrimPreview.value = true
            mergedPreviewPlayer.pause()

            val keepSegments = computeKeepSegments()
            if (keepSegments.isEmpty()) {
                _isGeneratingTrimPreview.value = false
                emitToast("裁剪后无剩余内容，请调整裁剪区间")
                return@launch
            }

            val result = audioCutter.generateFastPreview(audio, keepSegments)
            _isGeneratingTrimPreview.value = false
            _trimPreviewResult.value = result

            if (result.isSuccess && File(result.outputPath).exists()) {
                val previewItem = AudioItem(
                    id = 999999L,
                    title = "裁剪预览: ${audio.title}",
                    artist = audio.artist,
                    durationMs = result.durationMs,
                    sizeBytes = File(result.outputPath).length(),
                    filePath = result.outputPath,
                    dateModifiedSec = System.currentTimeMillis() / 1000
                )
                mergedPreviewPlayer.playAudio(previewItem)
                emitToast("裁剪预览就绪")
            } else {
                emitToast("生成裁剪预览失败: ${result.errorMessage}")
            }
        }
    }

    fun closeTrimPreview() {
        mergedPreviewPlayer.pause()
        _trimPreviewResult.value = null
    }

    /**
     * 根据原文件扩展名推断导出格式（保证覆盖后格式不变）
     */
    private fun resolveSourceFormat(audio: AudioItem): ExportAudioFormat {
        return when (File(audio.filePath).extension.lowercase()) {
            "mp3" -> ExportAudioFormat.MP3
            "wav" -> ExportAudioFormat.WAV
            else -> ExportAudioFormat.M4A
        }
    }

    /**
     * 执行裁剪导出（供另存为/分享/覆盖复用），成功返回输出文件路径
     */
    private suspend fun performTrimExport(
        audio: AudioItem,
        keepSegments: List<AudioSegment>,
        fileName: String,
        format: ExportAudioFormat
    ): ExportResult {
        return audioCutter.exportSegments(
            sourceAudio = audio,
            segments = keepSegments,
            customFileName = fileName,
            targetFormat = format
        ) { progress ->
            _trimExportProgress.value = progress
        }
    }

    /**
     * 裁剪另存为：将剪掉所选部分后的内容导出为新文件
     */
    fun saveTrimAs(customName: String) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频可导出")
            return
        }
        val keepSegments = computeKeepSegments()
        if (keepSegments.isEmpty()) {
            emitToast("裁剪后无剩余内容，请调整裁剪区间")
            return
        }
        val fileName = customName.ifBlank { "Trim_${System.currentTimeMillis()}" }

        viewModelScope.launch {
            _isTrimExporting.value = true
            _trimExportProgress.value = 0f
            try {
                val result = performTrimExport(audio, keepSegments, fileName, resolveSourceFormat(audio))
                if (result.isSuccess) {
                    emitToast("另存成功：${result.outputPath}")
                } else {
                    emitToast("另存失败: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                emitToast("另存异常: ${e.localizedMessage}")
            } finally {
                _isTrimExporting.value = false
            }
        }
    }

    /**
     * 裁剪并分享：优先复用已生成的预览文件直接分享，避免重复裁剪导出；无预览时先导出再分享
     */
    fun exportTrimAndShare() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频可导出")
            return
        }

        // 预览已生成且文件存在时直接复用（裁剪区间变动时预览缓存已自动失效）
        val currentPreview = _trimPreviewResult.value
        if (currentPreview != null && currentPreview.isSuccess && File(currentPreview.outputPath).exists()) {
            shareAudioFile(currentPreview.outputPath)
            return
        }

        val keepSegments = computeKeepSegments()
        if (keepSegments.isEmpty()) {
            emitToast("裁剪后无剩余内容，请调整裁剪区间")
            return
        }

        viewModelScope.launch {
            _isTrimExporting.value = true
            _trimExportProgress.value = 0f
            try {
                val result = performTrimExport(audio, keepSegments, "Trim_${System.currentTimeMillis()}", resolveSourceFormat(audio))
                if (result.isSuccess && File(result.outputPath).exists()) {
                    shareAudioFile(result.outputPath)
                } else {
                    emitToast("导出失败: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                emitToast("导出异常: ${e.localizedMessage}")
            } finally {
                _isTrimExporting.value = false
            }
        }
    }

    /**
     * 裁剪并覆盖原文件：将剪掉所选部分后的内容直接替换原音频文件
     */
    fun overwriteOriginalWithTrim() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频")
            return
        }
        val keepSegments = computeKeepSegments()
        if (keepSegments.isEmpty()) {
            emitToast("裁剪后无剩余内容，请调整裁剪区间")
            return
        }

        viewModelScope.launch {
            _isTrimExporting.value = true
            _trimExportProgress.value = 0f
            try {
                // 覆盖前停止所有播放，避免占用文件句柄
                withContext(Dispatchers.Main) {
                    stopAnyPreview()
                    playerManager.pause()
                }

                val format = resolveSourceFormat(audio)
                val result = performTrimExport(audio, keepSegments, "Trim_Overwrite_${System.currentTimeMillis()}", format)
                if (!result.isSuccess) {
                    emitToast("裁剪导出失败: ${result.errorMessage}")
                    return@launch
                }

                val sourceFile = File(audio.filePath)
                val newFile = File(result.outputPath)
                if (!sourceFile.exists() || !sourceFile.isFile) {
                    emitToast("原文件不可直接覆盖，请使用【另存为】")
                    return@launch
                }

                newFile.copyTo(sourceFile, overwrite = true)
                newFile.delete()

                // 刷新音频库以更新时长与大小
                scanAudios(force = true)
                emitToast("已覆盖原文件：${audio.title}")
            } catch (e: Exception) {
                emitToast("覆盖原文件失败: ${e.localizedMessage}")
            } finally {
                _isTrimExporting.value = false
            }
        }
    }

    private fun shareAudioFile(filePath: String) {
        try {
            val context = getApplication<Application>()
            val file = File(filePath)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "分享裁剪音频").apply {
                    // ViewModel 持有的是 Application Context，非 Activity 环境启动需要 NEW_TASK 标志
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
           )
        } catch (e: Exception) {
            emitToast("分享失败: ${e.localizedMessage}")
        }
    }

    /**
     * 复制片段
     */
    fun copySegment(segmentId: String) {
        val original = _segments.value.find { it.id == segmentId } ?: return
        val index = _segments.value.indexOf(original)
        val copy = original.copy(id = java.util.UUID.randomUUID().toString(), title = "${original.title} (副本)")
        val newList = _segments.value.toMutableList()
        newList.add(index + 1, copy)
        _segments.value = newList
        emitToast("已复制片段")
    }

    /**
     * 重新排序片段
     */
    fun reorderSegments(fromIndex: Int, toIndex: Int) {
        val newList = _segments.value.toMutableList()
        if (fromIndex !in newList.indices || toIndex !in newList.indices) return
        val item = newList.removeAt(fromIndex)
        newList.add(toIndex, item)
        _segments.value = newList
    }

    /**
     * 停止所有预览/试听相关的背景任务，确保手动控制时不发生冲突
     */
    fun stopAnyPreview() {
        previewJob?.cancel()
        previewJob = null
        _previewingSegmentId.value = null
        
        mergedPreviewPlayer.pause()
    }

    /**
     * 统一的跳转并播放接口，会自动清理冲突任务并确保播放器就绪
     */
    fun seekToAndPlay(positionMs: Long) {
        stopAnyPreview()
        playerManager.seekTo(positionMs)
        playerManager.play()
    }

    fun toggleMainPlayPause() {
        stopAnyPreview()
        playerManager.togglePlayPause()
    }

    /**
     * 手动拖动进度条调整播放位置（不改变播放/暂停状态）
     */
    fun mainSeekTo(positionMs: Long) {
        stopAnyPreview()
        playerManager.seekTo(positionMs)
    }

    fun mainFastForwardOrRewind(seconds: Int) {
        stopAnyPreview()
        playerManager.fastForwardOrRewind(seconds)
    }

    fun playMainPrevious() {
        stopAnyPreview()
        playerManager.playPrevious()
    }

    fun playMainNext() {
        stopAnyPreview()
        playerManager.playNext()
    }

    /**
     * 片段专属试听与播放/暂停控制
     */
    fun previewSegment(segment: AudioSegment) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }

        // 如果当前正在播放此片段，点击暂停
        if (_previewingSegmentId.value == segment.id && isPlaying.value) {
            playerManager.pause()
            previewJob?.cancel()
            previewJob = null
            _previewingSegmentId.value = null
            return
        }

        // 停止合并预览试听
        if (isMergedPreviewPlaying.value) {
            mergedPreviewPlayer.pause()
        }

        // 确保播放器加载了当前音频
        if (playerManager.currentAudio.value?.id != audio.id) {
            playerManager.playAudio(audio, _allAudios.value)
        }

        _previewingSegmentId.value = segment.id

        // 强制 Seek 到起点并播放
        playerManager.seekTo(segment.startMs)
        playerManager.play()

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(100.milliseconds) // 等待状态同步
            while (isActive && _previewingSegmentId.value == segment.id) {
                val currentSeg = _segments.value.find { it.id == segment.id }
                if (currentSeg == null) {
                    playerManager.pause()
                    _previewingSegmentId.value = null
                    break
                }

                val pos = playerManager.peekPositionMs()

                // 边界检测：到达终点（预留 30ms 提前量保证听感上精准停在终点）
                // 或落后起点太远（用户手动拖动）则停止
                if (pos >= currentSeg.endMs - 30L || pos < currentSeg.startMs - 500L) {
                    playerManager.pause()
                    // 精准停在起点，方便下次播放
                    if (pos >= currentSeg.endMs - 30L) {
                        playerManager.seekTo(currentSeg.startMs)
                    }
                    _previewingSegmentId.value = null
                    break
                }
                delay(20.milliseconds) // 高频检查
            }
        }
    }

    /**
     * 手动在当前播放位置创建一个固定时长（如5秒）的片段
     */
    fun createManualSegmentAtCurrentPos() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("请先选择音频")
            return
        }
        val pos = currentPositionMs.value
        val duration = durationMs.value.coerceAtLeast(audio.durationMs)
        
        val startMs = pos.coerceIn(0L, maxOf(0L, duration - 1000L))
        val endMs = (startMs + 5000L).coerceAtMost(duration)
        
        val newSegment = AudioSegment(
            audioId = audio.id,
            title = "手动片段 ${segments.value.size + 1}",
            startMs = startMs,
            endMs = endMs,
            colorIndex = _segments.value.size % 5
        )
        _segments.value += newSegment
        emitToast("已在当前位置创建片段")
    }
    fun updateSegmentRange(segmentId: String, newStartMs: Long, newEndMs: Long) {
        _segments.value = _segments.value.map {
            if (it.id == segmentId) {
                it.copy(
                    startMs = newStartMs.coerceAtLeast(0L),
                    endMs = newEndMs.coerceAtLeast(newStartMs + 100L)
                )
            } else it
        }
    }

    /**
     * 重命名片段
     */
    fun renameSegment(segmentId: String, newTitle: String) {
        _segments.value = _segments.value.map {
            if (it.id == segmentId) it.copy(title = newTitle) else it
        }
    }

    /**
     * 切换片段导出勾选
     */
    fun toggleSegmentSelected(segmentId: String) {
        _segments.value = _segments.value.map {
            if (it.id == segmentId) it.copy(isSelected = !it.isSelected) else it
        }
    }

    /**
     * 删除片段
     */
    fun deleteSegment(segmentId: String) {
        if (_previewingSegmentId.value == segmentId) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }
        _segments.value = _segments.value.filter { it.id != segmentId }
        emitToast("片段已删除")
    }

    /**
     * 生成或播放合并音频试听预览（极速无损模式）
     */
    fun startOrToggleMergedPreview() {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频")
            return
        }
        val selectedSegs = _segments.value.filter { it.isSelected && it.endMs > it.startMs }
        if (selectedSegs.isEmpty()) {
            emitToast("请至少勾选一个有效片段进行预览")
            return
        }

        // 停止主播放器及单片段预览
        if (playerManager.isPlaying.value || _previewingSegmentId.value != null) {
            playerManager.pause()
            _previewingSegmentId.value = null
            previewJob?.cancel()
        }

        val currentPreview = _mergedPreviewResult.value
        // 只有当预览结果存在、成功且文件确实存在时，才切换播放状态
        if (currentPreview != null && currentPreview.isSuccess && File(currentPreview.outputPath).exists()) {
            mergedPreviewPlayer.togglePlayPause()
            return
        }

        viewModelScope.launch {
            _isGeneratingMergedPreview.value = true
            // 在生成前确保旧的预览播放器停止
            mergedPreviewPlayer.pause()
            
            val result = audioCutter.generateFastPreview(audio, selectedSegs)
            _isGeneratingMergedPreview.value = false
            _mergedPreviewResult.value = result

            if (result.isSuccess && File(result.outputPath).exists()) {
                val previewItem = AudioItem(
                    id = 888888L,
                    title = "预览: ${audio.title}",
                    artist = audio.artist,
                    durationMs = result.durationMs,
                    sizeBytes = File(result.outputPath).length(),
                    filePath = result.outputPath,
                    dateModifiedSec = System.currentTimeMillis() / 1000
                )
                mergedPreviewPlayer.playAudio(previewItem)
                emitToast("预览就绪")
            } else {
                emitToast("生成预览失败: ${result.errorMessage}")
            }
        }
    }

    /**
     * 将预览音频保存到本地音乐库
     */
    fun saveMergedPreviewToLibrary(customName: String? = null) {
        val preview = _mergedPreviewResult.value ?: return
        if (!preview.isSuccess) return
        
        val sourceFile = File(preview.outputPath)
        if (!sourceFile.exists()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outputDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                
                val fileName = customName?.ifBlank { null } ?: "Clip_${System.currentTimeMillis()}"
                val targetFile = File(outputDir, "${fileName}.${sourceFile.extension}")
                
                sourceFile.copyTo(targetFile, overwrite = true)
                
                withContext(Dispatchers.Main) {
                    emitToast("已保存至: ${targetFile.absolutePath}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    emitToast("保存失败: ${e.message}")
                }
            }
        }
    }

    fun playMergedPreview() {
        mergedPreviewPlayer.play()
    }

    fun pauseMergedPreview() {
        mergedPreviewPlayer.pause()
    }

    fun toggleMergedPreviewPlayPause() {
        mergedPreviewPlayer.togglePlayPause()
    }

    fun seekMergedPreview(posMs: Long) {
        mergedPreviewPlayer.seekTo(posMs)
    }

    fun rewindMergedPreview(seconds: Int) {
        mergedPreviewPlayer.fastForwardOrRewind(seconds)
    }

    fun setMergedPreviewSpeed(speed: Float) {
        mergedPreviewPlayer.setPlaybackSpeed(speed)
    }

    fun closeMergedPreview() {
        mergedPreviewPlayer.pause()
        _mergedPreviewResult.value = null
    }

    /**
     * 导出拼接音频（支持格式选择与精准PCM合并）
     */
    fun exportMergedSegments(
        customFileName: String? = null,
        format: ExportAudioFormat = ExportAudioFormat.M4A
    ) {
        val audio = currentPlayingAudio.value ?: run {
            emitToast("当前无音频可导出")
            return
        }
        val selectedSegs = _segments.value.filter { it.isSelected }
        if (selectedSegs.isEmpty()) {
            emitToast("请至少勾选一个导出片段")
            return
        }

        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = 0f
            try {
                val result = audioCutter.exportSegments(
                    sourceAudio = audio,
                    segments = selectedSegs,
                    customFileName = customFileName,
                    targetFormat = format
                ) { progress ->
                    _exportProgress.value = progress
                }
                _exportResult.value = result
                if (result.isSuccess) {
                    emitToast("音频拼接导出成功！格式：${result.format.name}，保存至：${result.outputPath}")
                } else {
                    emitToast("导出失败: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                emitToast("导出异常: ${e.localizedMessage}")
            } finally {
                _isExporting.value = false
            }
        }
    }

    // ==================== 格式转换功能 ====================

    private var conversionPollJob: Job? = null

    /**
     * 开始音频格式转换（带实时进度、已用时间、剩余时间跟踪）
     */
    fun startConversion(quality: ConvertQuality, inputFilePath: String? = null, customFileName: String? = null) {
        val inputFile = inputFilePath
            ?: convertInputFile.value
            ?: currentPlayingAudio.value?.filePath
            ?: run {
                emitToast("没有输入文件")
                return
            }

        if (!File(inputFile).exists()) {
            emitToast("输入文件不存在")
            return
        }

        val outputDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        // 输出文件名：优先使用自定义名称，否则默认为 "converted" 前缀 + 输入文件名
        val baseName = (customFileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "converted_${File(inputFile).nameWithoutExtension}")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val outputFile = "${outputDir?.absolutePath}/$baseName.mp3"

        // 确保状态更新在主线程执行
        viewModelScope.launch(Dispatchers.Main) {
            _convertState.value = ConvertState(
                isConverting = true,
                progress = 0f,
                elapsedTimeMs = 0L,
                remainingTimeMs = 0L,
                inputFilePath = inputFile,
                outputFilePath = outputFile,
                quality = quality
            )
        }

        val startTime = System.currentTimeMillis()
        convertStartTime = startTime
        convertCancelled = false
        convertSessionId = -1L

        // 在IO线程异步执行FFmpeg转换：通过统计回调上报准确进度
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 解析输入媒体总时长，作为进度计算的基准
            convertTotalDurationMs = queryMediaDurationMs(inputFile)

            // 2. 若无法获取总时长，退回"输出文件大小"轮询估算（兜底）
            if (convertTotalDurationMs <= 0L) {
                val inputFileSize = File(inputFile).length()
                conversionPollJob = viewModelScope.launch(Dispatchers.IO) {
                    while (isActive && !convertCancelled) {
                        delay(300.milliseconds)
                        if (!convertCancelled) {
                            updateConversionProgress(outputFile, inputFileSize, quality, convertStartTime)
                        }
                    }
                }
            }

            // 3. 统计回调：FFmpeg 持续上报已处理到的媒体时间（time，毫秒），据此计算准确进度
            val statisticsCallback = StatisticsCallback { stats ->
                val totalMs = convertTotalDurationMs
                val processedMs = stats?.time ?: 0.0
                if (totalMs <= 0L || processedMs <= 0.0) return@StatisticsCallback

                val progress = (processedMs / totalMs).toFloat().coerceIn(0f, 0.99f)
                val elapsed = System.currentTimeMillis() - convertStartTime
                val remaining = if (progress > 0.01f) ((elapsed / progress) * (1f - progress)).toLong() else 0L

                viewModelScope.launch(Dispatchers.Main) {
                    if (convertCancelled) return@launch
                    _convertState.value = _convertState.value.copy(
                        progress = progress,
                        elapsedTimeMs = elapsed,
                        remainingTimeMs = remaining,
                        speed = stats.speed.toFloat()
                    )
                }
            }

            // 4. 完成回调：在 FFmpegKit 线程回调，状态更新需切回主线程
            val completeCallback = FFmpegSessionCompleteCallback { session ->
                // 转换结束，停止兜底轮询
                conversionPollJob?.cancel()

                if (convertCancelled) {
                    File(outputFile).takeIf { it.exists() }?.delete()
                    return@FFmpegSessionCompleteCallback
                }

                val totalTime = System.currentTimeMillis() - convertStartTime

                viewModelScope.launch(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        _convertState.value = ConvertState(
                            isConverting = false,
                            progress = 1f,
                            elapsedTimeMs = totalTime,
                            remainingTimeMs = 0L,
                            speed = 0f,
                            inputFilePath = inputFile,
                            outputFilePath = outputFile,
                            quality = quality
                        )
                    } else {
                        _convertState.value = _convertState.value.copy(
                            isConverting = false,
                            error = "FFmpeg 执行失败"
                        )
                    }
                }
                if (ReturnCode.isSuccess(session.returnCode)) {
                    emitToast("转换成功！")
                } else {
                    emitToast("转换失败")
                }
            }

            // 5. 启动异步转换；session 启动即创建，取消逻辑可直接使用 sessionId
            try {
                val command = buildFFmpegCommand(inputFile, outputFile, quality)
                val session = FFmpegKit.executeAsync(command, completeCallback, null, statisticsCallback)
                convertSessionId = session.sessionId
            } catch (e: Exception) {
                conversionPollJob?.cancel()
                if (!convertCancelled) {
                    viewModelScope.launch(Dispatchers.Main) {
                        _convertState.value = _convertState.value.copy(
                            isConverting = false,
                            error = e.message ?: "转换失败"
                        )
                    }
                    emitToast("转换失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 取消转换
     */
    fun cancelConversion() {
        convertCancelled = true
        conversionPollJob?.cancel()
        // 通过 sessionId 取消正在执行的 FFmpeg 进程
        if (convertSessionId >= 0) {
            FFmpegKit.cancel(convertSessionId)
        } else {
            FFmpegKit.cancel()
        }
        // 确保状态更新在主线程执行
        viewModelScope.launch(Dispatchers.Main) {
            _convertState.value = _convertState.value.copy(
                isConverting = false,
                progress = 0f,
                error = "用户取消"
            )
        }
        emitToast("转换已取消")
    }

    /**
     * 通过输出文件大小估算转换进度、已用时间、剩余时间和速度
     */
    private fun updateConversionProgress(
        outputFile: String, currentInputFileSize: Long,
        quality: ConvertQuality, startTime: Long
    ) {
        try {
            val outputSize = File(outputFile).takeIf { it.exists() }?.length() ?: 0L
            val elapsed = System.currentTimeMillis() - startTime

            // 根据质量级别估算最终文件大小（MP3压缩率）
            val qualityRatio = if (quality == ConvertQuality.HIGH) 0.5f else 0.15f
            val expectedOutputSize = (currentInputFileSize.toFloat() * qualityRatio).toLong().coerceAtLeast(1L)

            val progress = if (expectedOutputSize > 0) {
                (outputSize.toFloat() / expectedOutputSize.toFloat()).coerceIn(0f, 0.95f)
            } else 0f

            val speed = if (elapsed > 0) outputSize.toFloat() / (elapsed / 1000f) else 0f
            val remaining = if (progress > 0.05f) {
                ((elapsed.toFloat() / progress) * (1f - progress)).toLong()
            } else 0L

            // 确保状态更新在主线程执行
            viewModelScope.launch(Dispatchers.Main) {
                _convertState.value = _convertState.value.copy(
                    progress = progress,
                    elapsedTimeMs = elapsed,
                    remainingTimeMs = remaining,
                    speed = speed
                )
            }
        } catch (_: Exception) { /* 轮询过程中忽略异常 */ }
    }
    
    /**
     * 查询媒体文件总时长（毫秒）
     * 优先使用 FFprobe 解析；失败或结果无效时退回 MediaMetadataRetriever
     */
    private fun queryMediaDurationMs(path: String): Long {
        // 1. FFprobe 解析（duration 单位为秒）
        val ffprobeMs = try {
            val info = FFprobeKit.getMediaInformation(path).mediaInformation
            info?.duration?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        } catch (_: Exception) {
            null
        }
        if (ffprobeMs != null && ffprobeMs > 0L) return ffprobeMs

        // 2. 兜底：Android MediaMetadataRetriever（duration 单位为毫秒）
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 构建FFmpeg转换命令
     */
    private fun buildFFmpegCommand(inputFile: String, outputFile: String, quality: ConvertQuality): String {
        return if (quality == ConvertQuality.LOW) {
            // 低质量参数
            "-hide_banner -stats -i \"$inputFile\" -vn -c:a libmp3lame -q:a 7 -ar 44100 -ac 2 \"$outputFile\""
        } else {
            // 高质量参数
            "-hide_banner -stats -i \"$inputFile\" -vn -c:a libmp3lame -q:a 2 -ar 44100 -ac 2 \"$outputFile\""
        }
    }
    
    /**
     * 获取转换完成的文件
     */
    fun getConvertedFile(): File? {
        val outputPath = _convertState.value.outputFilePath
        return outputPath?.let { File(it) }
    }
    
    /**
     * 分享转换后的文件
     */
    fun shareConvertedFile(context: Context) {
        val file = getConvertedFile() ?: run {
            emitToast("没有可分享的文件")
            return
        }
        
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        
        context.startActivity(Intent.createChooser(intent, "分享音频文件"))
    }
    
    /**
     * 保存转换后的文件到音频库
     * 转换输出目录已位于应用外部音乐目录，刷新音频库即可收录
     */
    fun saveConvertedToLibrary(context: Context) {
        val file = getConvertedFile() ?: run {
            emitToast("没有可保存的文件")
            return
        }
        if (!file.exists()) {
            emitToast("文件不存在")
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            scanAudios(force = true) // 刷新音频库列表以收录新文件
            emitToast("已保存到音频库")
        }
    }
    
    fun emitToast(msg: String) {
        _toastEvent.tryEmit(msg)
    }

    override fun onCleared() {
        playerManager.release()
        mergedPreviewPlayer.release()
        OfflineAsrEngine.releaseResources()
    }
}
