package com.example.audiocut.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.example.audiocut.core.AppEventBus
import com.example.audiocut.data.model.*
import com.example.audiocut.data.repository.AudioRepository
import com.example.audiocut.data.repository.PreferencesRepository
import com.example.audiocut.player.AudioPlayerManager
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
            if (cached.isNotEmpty()) {
                _allAudios.value = cached
            } else {
                // 保底恢复：缓存缺失/解析失败时，直接用持久化的引用 URI 构造条目，
                // 保证重启后引用导入的文件一定可见（不依赖媒体权限与解析结果）
                val placeholders = prefs.getImportedAudioUris().mapNotNull { uriStr ->
                    runCatching { Uri.parse(uriStr) }.getOrNull()?.let { uri ->
                        AudioItem(
                            id = uri.hashCode().toLong(),
                            title = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "导入文件",
                            contentUri = uri,
                            dateModifiedSec = System.currentTimeMillis() / 1000
                        )
                    }
                }
                if (placeholders.isNotEmpty()) _allAudios.value = placeholders
            }
        }

        viewModelScope.launch {
            _isScanning.value = true
            val hiddenPaths = prefs.getHiddenFilePaths()

            // 完整库 = 外部引用（不拷贝）+ 应用内部目录（历史拷贝/导出产物）
            // 引用集合持久化保留：仅用户删除时移除；解析失败（权限未就绪/源暂不可达）不丢弃记录，
            // 权限恢复后 force 重扫会自动重新出现
            val referencedUris = prefs.getImportedAudioUris()
            val list = audioRepository.scanLibraryAudios(referencedUris)
                .filter { it.filePath !in hiddenPaths }

            if (list.isNotEmpty() || force) {
                if (force) {
                    // 主动刷新（用户点刷新/授权后重扫/删除后同步）：
                    // 以本次实际解析结果为准——能解析的保留，失效引用与外部已删除文件被清理
                    _allAudios.value = list
                    prefs.saveAudioLibraryCache(list)
                    // 同步清理本次解析失败的失效引用记录（分享授权失效/源文件被移除）；
                    // 仅在具备媒体读取权限时清理，避免权限被临时关闭时误删媒体库引用记录
                    if (hasMediaReadPermission()) {
                        val validUris = list.mapNotNull { it.contentUri?.toString() }
                            .filter { it in referencedUris }
                            .toSet()
                        prefs.saveImportedAudioUris(validUris)
                    }
                } else {
                    // 启动等非 force 扫描：与当前显示（缓存）合并，
                    // 保留「引用集合中仍存在、但本次未解析出」的缓存条目，避免重启后列表丢失
                    val current = _allAudios.value
                    val scannedKeys = list.mapNotNull { it.contentUri?.toString() ?: it.filePath }.toSet()
                    val keptReferenced = current.filter { item ->
                        val key = item.contentUri?.toString()
                        key != null && key !in scannedKeys && key in referencedUris
                    }
                    val merged = (list + keptReferenced)
                        .distinctBy { it.contentUri?.toString() ?: it.filePath }
                        .sortedByDescending { it.dateModifiedSec }
                    _allAudios.value = merged
                    prefs.saveAudioLibraryCache(merged)
                }
            }
            // 启动首次扫描为空（权限未就绪/媒体库暂不可用）：保留缓存显示，不清空

            _isScanning.value = false

            prefs.cleanupHiddenFilePaths()
            if (notify) eventBus.notifyAudioLibraryChanged()
        }
    }

    /** 当前是否具备媒体读取权限（刷新清理失效引用前校验，避免误删） */
    private fun hasMediaReadPermission(): Boolean {
        return try {
            val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication<Application>(),
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
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

    /** 导入单个音频（引用方式，不拷贝文件） */
    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            val imported = audioRepository.importAudioByReference(uri)
            if (imported != null) {
                // 已拷贝为内部副本的（分享等临时授权）不记录 URI 引用
                if (imported.contentUri?.scheme != "file") prefs.addImportedAudioUri(uri.toString())
                emitToast("已导入: ${imported.title}")
                prefs.removeHiddenFilePath(imported.filePath)
                scanAudios(force = true)
            } else {
                emitToast("导入失败")
            }
            _isScanning.value = false
        }
    }

    /** 批量导入音频（引用方式，不拷贝文件） */
    fun importMultipleAudios(uris: List<Uri>) {
        viewModelScope.launch {
            _isScanning.value = true
            var count = 0
            var lastImported: AudioItem? = null
            uris.forEach { uri ->
                audioRepository.importAudioByReference(uri)?.let {
                    if (it.contentUri?.scheme != "file") prefs.addImportedAudioUri(uri.toString())
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

    /** 处理外部分享/打开的音频（引用方式，不拷贝文件） */
    fun handleExternalUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _isScanning.value = true
                val imported = audioRepository.importAudioByReference(uri)
                if (imported != null) {
                    // 已拷贝为内部副本的（分享等临时授权）不记录 URI 引用
                    if (imported.contentUri?.scheme != "file") prefs.addImportedAudioUri(uri.toString())
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
                // 引用导入的条目：移除持久化的 Uri 引用记录
                audio.contentUri?.toString()?.let { prefs.removeImportedAudioUri(it) }

                // 内部文件物理删除（引用导入的原始文件不属于应用目录，仅删除记录）
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

    /** 重命名音频库条目（物理重命名文件/更新媒体库/文档重命名；id 变化时迁移关联数据） */
    fun renameAudio(audioId: Long, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val audio = _allAudios.value.find { it.id == audioId } ?: return@launch
            try {
                val renamed = audioRepository.renameAudioItem(audio, newName)
                if (renamed == null) {
                    emitToast("重命名失败（名称已存在或文件不支持）")
                    return@launch
                }

                // 文档 Uri 重命名后 URI 变化：更新引用集合
                audio.contentUri?.let { oldUri ->
                    if (renamed.contentUri?.toString() != oldUri.toString()) {
                        prefs.removeImportedAudioUri(oldUri.toString())
                        if (renamed.contentUri?.scheme != "file") {
                            prefs.addImportedAudioUri(renamed.contentUri.toString())
                        }
                    }
                }

                // id 变化（物理/文档重命名）：迁移文稿、片段、裁剪范围、收藏
                if (renamed.id != audio.id) {
                    prefs.migrateAudioData(audio.id, renamed.id)
                }

                withContext(Dispatchers.Main) {
                    emitToast("已重命名为: ${renamed.title}")
                    scanAudios(force = true)
                }
            } catch (e: Exception) {
                emitToast("重命名失败: ${e.localizedMessage}")
            }
        }
    }

    /** 删除单条音频 */
    fun deleteAudio(audioId: Long, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val audio = _allAudios.value.find { it.id == audioId } ?: return@launch
            try {
                prefs.deleteAudioData(audioId)
                // 引用导入的条目：无论是否删除原文件，都移除持久化的 Uri 引用记录
                audio.contentUri?.toString()?.let { prefs.removeImportedAudioUri(it) }

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
