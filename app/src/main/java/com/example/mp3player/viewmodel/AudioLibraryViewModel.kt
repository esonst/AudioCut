package com.example.mp3player.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.example.mp3player.core.AppEventBus
import com.example.mp3player.data.model.*
import com.example.mp3player.data.repository.AudioRepository
import com.example.mp3player.data.repository.PreferencesRepository
import com.example.mp3player.player.AudioPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 音乐库界面 ViewModel
 * 负责音频扫描、列表展示、搜索筛选、收藏、导入删除、多选管理
 */
class AudioLibraryViewModel(
    application: Application,
    eventBus: AppEventBus,
    private val audioRepository: AudioRepository,
    private val prefs: PreferencesRepository,
    private val playerManager: AudioPlayerManager
) : BaseViewModel(application, eventBus) {

    // ==================== 音频列表数据 ====================
    private val _allAudios = MutableStateFlow<List<AudioItem>>(emptyList())
    val allAudios: StateFlow<List<AudioItem>> = _allAudios.asStateFlow()

    // ==================== 搜索与筛选 ====================
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

    // ==================== 设备扫描导入 ====================
    private val _deviceAudios = MutableStateFlow<List<AudioItem>>(emptyList())
    val deviceAudios: StateFlow<List<AudioItem>> = _deviceAudios.asStateFlow()
    private val _isScanningDevice = MutableStateFlow(false)
    val isScanningDevice: StateFlow<Boolean> = _isScanningDevice.asStateFlow()

    // ==================== 多选删除 ====================
    private val _selectedAudioIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedAudioIds: StateFlow<Set<Long>> = _selectedAudioIds.asStateFlow()
    val isSelectionMode: StateFlow<Boolean> = _selectedAudioIds.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ==================== 收藏 ====================
    private val _favoriteIds = MutableStateFlow(prefs.getFavoriteIds())
    val favoriteIds: StateFlow<Set<Long>> = _favoriteIds.asStateFlow()

    // ==================== 新导入高亮提示 ====================
    private val _highlightedAudioId = MutableStateFlow<Long?>(null)
    val highlightedAudioId: StateFlow<Long?> = _highlightedAudioId.asStateFlow()
    private var highlightJob: kotlinx.coroutines.Job? = null

    // ==================== 播放器状态（透传，供 UI 使用） ====================
    val currentPlayingAudio: StateFlow<AudioItem?> = playerManager.currentAudio
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val currentPositionMs: StateFlow<Long> = playerManager.currentPositionMs
    val durationMs: StateFlow<Long> = playerManager.durationMs

    // ==================== 过滤排序后的展示列表 ====================
    val displayAudios: StateFlow<List<AudioItem>> = combine(
        _allAudios, _searchQuery, _sortField, _sortDirection,
        combine(_durationFilter, _sizeFilter) { d, s -> Pair(d, s) }
    ) { audios, query, field, direction, filters ->
        val filtered = audioRepository.filterAudios(audios, query, filters.first, filters.second)
        audioRepository.sortAudios(filtered, field, direction)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 文件夹分组
    val groupedAudios: StateFlow<Map<String, List<AudioItem>>> = displayAudios.map {
        audioRepository.groupByFolder(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        scanAudios()
        observeSaveState()
        // 监听全局音频库变化事件（覆盖/保存/清理导出后），自动刷新列表以同步最新元信息与失效条目
        viewModelScope.launch {
            eventBus.audioLibraryChanged.collect {
                scanAudios(force = true, notify = false)
            }
        }
        // 列表加载完成后恢复播放状态
        viewModelScope.launch {
            _allAudios.filter { it.isNotEmpty() }.first()
            // 恢复播放状态的逻辑移到 MainViewModel 调用
            // 这里通过 eventBus 通知？不，直接在 MainActivity 中观察 allAudios 后调用
        }
    }

    private fun observeSaveState() {
        viewModelScope.launch { favoriteIds.collect { prefs.saveFavoriteIds(it) } }
        viewModelScope.launch { sortField.collect { prefs.saveSortField(it) } }
        viewModelScope.launch { sortDirection.collect { prefs.saveSortDirection(it) } }
    }

    /** 新导入音频闪烁高亮 */
    fun flashImportedAudio(audioId: Long) {
        highlightJob?.cancel()
        _highlightedAudioId.value = audioId
        highlightJob = viewModelScope.launch {
            delay(2500.milliseconds)
            if (_highlightedAudioId.value == audioId) _highlightedAudioId.value = null
        }
    }

    /** 扫描音频库；notify=false 表示本次扫描由库变化事件触发，避免事件循环 */
    fun scanAudios(force: Boolean = false, notify: Boolean = true) {
        if (!force && _allAudios.value.isNotEmpty()) return

        if (!force && _allAudios.value.isEmpty()) {
            val cached = prefs.getAudioLibraryCache()
            if (cached.isNotEmpty()) _allAudios.value = cached
        }

        viewModelScope.launch {
            _isScanning.value = true
            val hiddenPaths = prefs.getHiddenFilePaths()
            val list = audioRepository.scanImportedAudios()
                .filter { it.filePath !in hiddenPaths }
            _allAudios.value = list
            _isScanning.value = false

            prefs.saveAudioLibraryCache(list)
            prefs.cleanupHiddenFilePaths()
            if (notify) eventBus.notifyAudioLibraryChanged()
        }
    }

    /** 扫描设备所有音频 */
    fun scanDeviceAudios() {
        viewModelScope.launch {
            _isScanningDevice.value = true
            _deviceAudios.value = audioRepository.scanLocalAudios()
            _isScanningDevice.value = false
        }
    }

    /** 导入单个音频 */
    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            val imported = audioRepository.importAudioFile(uri)
            if (imported != null) {
                emitToast("已导入: ${imported.title}")
                prefs.removeHiddenFilePath(imported.filePath)
                scanAudios(force = true)
            } else {
                emitToast("导入失败")
            }
            _isScanning.value = false
        }
    }

    /** 批量导入音频 */
    fun importMultipleAudios(uris: List<Uri>) {
        viewModelScope.launch {
            _isScanning.value = true
            var count = 0
            var lastImported: AudioItem? = null
            uris.forEach { uri ->
                audioRepository.importAudioFile(uri)?.let {
                    count++
                    lastImported = it
                }
            }
            emitToast("成功导入 $count 个文件")
            lastImported?.let { prefs.removeHiddenFilePath(it.filePath) }
            scanAudios(force = true)
            lastImported?.let { flashImportedAudio(it.id) }
            _isScanning.value = false
        }
    }

    /** 处理外部分享/打开的音频 */
    fun handleExternalUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _isScanning.value = true
                val imported = audioRepository.importAudioFile(uri)
                if (imported != null) {
                    emitToast("已从外部应用导入: ${imported.title}")
                    prefs.removeHiddenFilePath(imported.filePath)
                    scanAudios(force = true)
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

    // ==================== 筛选排序设置 ====================
    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setBrowseMode(mode: BrowseMode) { _browseMode.value = mode }
    fun setSort(field: SortField, direction: SortDirection) {
        _sortField.value = field
        _sortDirection.value = direction
    }
    fun setFilters(duration: DurationFilter, size: SizeFilter) {
        _durationFilter.value = duration
        _sizeFilter.value = size
    }

    // ==================== 多选删除 ====================
    fun toggleAudioSelection(audioId: Long) {
        val current = _selectedAudioIds.value.toMutableSet()
        if (current.contains(audioId)) current.remove(audioId) else current.add(audioId)
        _selectedAudioIds.value = current
    }

    fun clearSelection() { _selectedAudioIds.value = emptySet() }

    fun deleteSelectedAudios() {
        val idsToDelete = _selectedAudioIds.value
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            var deletedCount = 0
            val allAudiosList = _allAudios.value

            idsToDelete.forEach { id ->
                val audio = allAudiosList.find { it.id == id } ?: return@forEach
                prefs.deleteAudioData(id)

                // 内部文件物理删除
                val file = File(audio.filePath)
                val internalDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                if (internalDir != null && audio.filePath.startsWith(internalDir.absolutePath)) {
                    if (file.exists()) file.delete()
                }

                if (playerManager.currentAudio.value?.id == id) {
                    withContext(Dispatchers.Main) { playerManager.pause() }
                }
                deletedCount++
            }

            withContext(Dispatchers.Main) {
                clearSelection()
                _allAudios.value = _allAudios.value.filter { it.id !in idsToDelete }
                prefs.saveAudioLibraryCache(_allAudios.value)
                eventBus.notifyAudioLibraryChanged()
                emitToast("已成功删除 $deletedCount 条记录")
            }
        }
    }

    /** 删除单条音频 */
    fun deleteAudio(audioId: Long, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val audio = _allAudios.value.find { it.id == audioId } ?: return@launch
            try {
                prefs.deleteAudioData(audioId)

                if (deleteFile) {
                    val file = File(audio.filePath)
                    val internalDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    if (internalDir != null && audio.filePath.startsWith(internalDir.absolutePath) && file.exists()) {
                        file.delete()
                    }
                    prefs.removeHiddenFilePath(audio.filePath)
                } else {
                    prefs.addHiddenFilePath(audio.filePath)
                }

                if (playerManager.currentAudio.value?.id == audioId) {
                    withContext(Dispatchers.Main) { playerManager.pause() }
                }

                withContext(Dispatchers.Main) {
                    _allAudios.value = _allAudios.value.filter { it.id != audioId }
                    clearSelection()
                    prefs.saveAudioLibraryCache(_allAudios.value)
                    eventBus.notifyAudioLibraryChanged()
                    emitToast(if (deleteFile) "已删除音频及原文件" else "已从音频库移除（原文件已保留）")
                }
            } catch (e: Exception) {
                emitToast("删除失败: ${e.localizedMessage}")
            }
        }
    }

    /** 切换收藏 */
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

    /** 播放音频 */
    fun playAudio(audio: AudioItem) {
        try {
            eventBus.sendStopAllPreview()
            playerManager.playAudio(audio, _allAudios.value)
        } catch (e: Exception) {
            emitToast("播放失败: ${e.message}")
        }
    }
}
