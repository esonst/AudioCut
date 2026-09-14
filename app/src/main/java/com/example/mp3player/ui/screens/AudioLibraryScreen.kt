package com.example.mp3player.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.mp3player.data.model.AudioItem
import com.example.mp3player.data.model.BrowseMode
import com.example.mp3player.ui.components.FilterSortBottomSheet
import com.example.mp3player.ui.components.FloatingPlayerBar
import com.example.mp3player.ui.components.ScanImportDialog
import com.example.mp3player.ui.components.drawVerticalScrollbar
import com.example.mp3player.ui.theme.*
import com.example.mp3player.utils.RingtoneHelper
import com.example.mp3player.navigation.AppScreen
import com.example.mp3player.viewmodel.AudioLibraryViewModel
import com.example.mp3player.viewmodel.MainViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * 音频库页面：自动扫描、全部/文件夹模式、筛选排序、权限处理
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AudioLibraryScreen(mainViewModel: MainViewModel, viewModel: AudioLibraryViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val displayAudios by viewModel.displayAudios.collectAsState()
    val browseMode by viewModel.browseMode.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortDirection by viewModel.sortDirection.collectAsState()
    val durationFilter by viewModel.durationFilter.collectAsState()
    val sizeFilter by viewModel.sizeFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val currentPlayingAudio by viewModel.currentPlayingAudio.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val selectedAudioIds by viewModel.selectedAudioIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val highlightedAudioId by viewModel.highlightedAudioId.collectAsState()

    val favoriteAudios = remember(displayAudios, favoriteIds) {
        displayAudios.filter { favoriteIds.contains(it.id) }
    }

    val listState = rememberLazyListState()

    var showFilterSheet by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }

    // 新导入音频闪烁定位：滚动到高亮项
    LaunchedEffect(highlightedAudioId, displayAudios, browseMode) {
        val id = highlightedAudioId ?: return@LaunchedEffect
        if (browseMode == BrowseMode.ALL) {
            val index = displayAudios.indexOfFirst { it.id == id }
            if (index >= 0) listState.animateScrollToItem(index)
        }
    }

    // 文件选择器：仅允许音/视频文件，并指定初始目录为内部存储根目录
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.OpenMultipleDocuments() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                return super.createIntent(context, input).apply {
                    // EXTRA_INITIAL_URI 自 API 26 引入，旧系统上忽略该附加参数即可
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            putExtra(
                                DocumentsContract.EXTRA_INITIAL_URI,
                                DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:"
                                )
                            )
                        } catch (_: Exception) {
                            // 个别厂商 ROM 不支持 EXTRA_INITIAL_URI 时保持默认行为
                        }
                    }
                }
            }
        },
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importMultipleAudios(uris)
            }
        }
    )

    // 权限处理 (Android 13+ READ_MEDIA_AUDIO / 传统存储权限)
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest)

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.scanAudios()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 页面标题与刷新按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = PrimaryDark)
                        }
                        Text(
                            text = "已选中 ${selectedAudioIds.size} 项",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDark
                        )
                    }
                    
                    Row {
                        IconButton(onClick = { 
                            // 弹出确认删除对话框逻辑（此处直接删除，实际建议加 Dialog）
                            viewModel.deleteSelectedAudios() 
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                        }
                    }
                } else {
                    Text(
                        text = "本地音频库",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var showImportMenu by remember { mutableStateOf(false) }
                        
                        Box {
                            IconButton(onClick = { showImportMenu = true }) {
                                Icon(Icons.Default.AddCircle, contentDescription = "Import", tint = PrimaryDark)
                            }
                            DropdownMenu(
                                expanded = showImportMenu,
                                onDismissRequest = { showImportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("从文件导入") },
                                    leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                                    onClick = {
                                        showImportMenu = false
                                        filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("扫描本地设备") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    onClick = {
                                        showImportMenu = false
                                        showScanDialog = true
                                    }
                                )
                            }
                        }

                        IconButton(onClick = {
                            viewModel.scanAudios(force = true)
                        }) {
                            if (isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = PrimaryDark)
                            }
                        }
                        IconButton(onClick = {
                            mainViewModel.navigateTo(AppScreen.SETTINGS)
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = PrimaryDark)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 搜索框与筛选按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("搜索音频名称、艺术家、文件夹...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.setSearchQuery("")
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White,
                        unfocusedBorderColor = BorderLight,
                        focusedBorderColor = PrimaryLight
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 筛选按钮
                IconButton(
                    onClick = {
                        showFilterSheet = true
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = PrimaryLight)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 浏览模式切换 (全部音频 / 我的收藏)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariantLight)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ModeTabItem(
                    label = "全部 (${displayAudios.size})",
                    isSelected = browseMode == BrowseMode.ALL,
                    onClick = { viewModel.setBrowseMode(BrowseMode.ALL) },
                    modifier = Modifier.weight(1f)
                )

                ModeTabItem(
                    label = "我的收藏 (${favoriteAudios.size})",
                    isSelected = browseMode == BrowseMode.FAVORITES,
                    onClick = { viewModel.setBrowseMode(BrowseMode.FAVORITES) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 未获得权限提示卡片
            if (!permissionsState.allPermissionsGranted) {
                PermissionBanner(onRequestPermission = { permissionsState.launchMultiplePermissionRequest() })
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 音频列表展示
            if (displayAudios.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.MusicOff,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isScanning) "正在扫描已导入音频..." else "音频库为空，点击右上角 + 导入音频",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                        if (!isScanning) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showScanDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("立即扫描设备")
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .drawVerticalScrollbar(listState),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            when (browseMode) {
                                BrowseMode.ALL -> {
                                    items(displayAudios, key = { it.id }) { audio ->
                                        AudioItemRowOptimized(
                                            audio = audio,
                                            isCurrent = currentPlayingAudio?.id == audio.id,
                                            isPlaying = isPlaying,
                                            isSelected = selectedAudioIds.contains(audio.id),
                                            isSelectionMode = isSelectionMode,
                                            isHighlighted = highlightedAudioId == audio.id,
                                            isFavorite = favoriteIds.contains(audio.id),
                                            onToggleFavorite = { viewModel.toggleFavorite(audio.id) },
                                            onLongClick = { viewModel.toggleAudioSelection(audio.id) },
                                            onShare = { shareAudioFile(context, audio.filePath) },
                                            onSetRingtone = { RingtoneHelper.setAsRingtone(context, audio.filePath) },
                                            onDelete = { deleteFile -> viewModel.deleteAudio(audio.id, deleteFile) },
                                            onClick = {
                                                if (isSelectionMode) {
                                                    viewModel.toggleAudioSelection(audio.id)
                                                } else {
                                                    viewModel.playAudio(audio)
                                                }
                                            }
                                        )
                                    }
                                }
                                BrowseMode.FAVORITES -> {
                                    if (favoriteAudios.isEmpty()) {
                                        item {
                                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                                Text("暂无已收藏音频", color = TextSecondary)
                                            }
                                        }
                                    } else {
                                        items(favoriteAudios, key = { it.id }) { audio ->
                                            AudioItemRowOptimized(
                                                audio = audio,
                                                isCurrent = currentPlayingAudio?.id == audio.id,
                                                isPlaying = isPlaying,
                                                isSelected = selectedAudioIds.contains(audio.id),
                                                isSelectionMode = isSelectionMode,
                                                isHighlighted = highlightedAudioId == audio.id,
                                                isFavorite = true,
                                                onToggleFavorite = { viewModel.toggleFavorite(audio.id) },
                                                onLongClick = { viewModel.toggleAudioSelection(audio.id) },
                                                onShare = { shareAudioFile(context, audio.filePath) },
                                                onSetRingtone = { RingtoneHelper.setAsRingtone(context, audio.filePath) },
                                                onDelete = { deleteFile -> viewModel.deleteAudio(audio.id, deleteFile) },
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        viewModel.toggleAudioSelection(audio.id)
                                                    } else {
                                                        viewModel.playAudio(audio)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 浮动播放控制栏（控制完整音频播放/暂停、上一曲、下一曲、快退5s、快进5s）
        FloatingPlayerBar(
            currentAudio = currentPlayingAudio,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            onTogglePlayPause = { mainViewModel.toggleMainPlayPause() },
            onFastForward5s = { mainViewModel.mainFastForwardOrRewind(5) },
            onRewind5s = { mainViewModel.mainFastForwardOrRewind(-5) },
            onPlayPrevious = { mainViewModel.playMainPrevious() },
            onPlayNext = { mainViewModel.playMainNext() },
            onSeekTo = { mainViewModel.mainSeekTo(it) },
            onClickBar = { mainViewModel.navigateTo(AppScreen.TRANSCRIPT) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 80.dp)
        )
    }

    // 筛选与排序弹窗
    if (showFilterSheet) {
        FilterSortBottomSheet(
            currentSortField = sortField,
            currentSortDirection = sortDirection,
            currentDurationFilter = durationFilter,
            currentSizeFilter = sizeFilter,
            onSortChange = { f, d -> viewModel.setSort(f, d) },
            onFilterChange = { dur, sz -> viewModel.setFilters(dur, sz) },
            onDismiss = { showFilterSheet = false }
        )
    }

    if (showScanDialog) {
        ScanImportDialog(
            viewModel = viewModel,
            onDismiss = { showScanDialog = false }
        )
    }
}

@Composable
private fun ModeTabItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) PrimaryDark else TextSecondary
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AudioItemRowOptimized(
    audio: AudioItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    isHighlighted: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onShare: () -> Unit = {},
    onSetRingtone: () -> Unit = {},
    onDelete: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    // 静态内容使用 remember 避免重组
    val metadata = remember(audio) { "${audio.artist} · ${audio.formattedDuration} · ${audio.formattedSize}" }

    // 长按菜单：收藏 / 取消收藏、多选删除
    var showLongPressMenu by remember { mutableStateOf(false) }

    // 删除确认卡片状态
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteFileChecked by remember { mutableStateOf(false) }

    // 新导入闪烁动画：交替高亮背景三次
    var flashOn by remember { mutableStateOf(false) }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            repeat(3) {
                flashOn = true
                delay(250.milliseconds)
                flashOn = false
                delay(250.milliseconds)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showLongPressMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> Color(0xFFE0F2FE)
                flashOn -> Color(0xFFBFDBFE)
                isCurrent -> Color(0xFFEFF6FF)
                else -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryLight)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCurrent) PrimaryLight else SurfaceVariantLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCurrent && isPlaying) Icons.Default.GraphicEq else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = if (isCurrent) Color.White else PrimaryDark,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = audio.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) PrimaryLight else PrimaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = metadata,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
            }

            if (isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "已收藏",
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(16.dp)
                )
            }

            if (isCurrent) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(18.dp))
            }
        }

        // 长按弹出菜单：收藏 / 取消收藏、分享、制作为铃声、多选删除
        DropdownMenu(
            expanded = showLongPressMenu,
            onDismissRequest = { showLongPressMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B)
                    )
                },
                onClick = {
                    showLongPressMenu = false
                    onToggleFavorite()
                }
            )
            DropdownMenuItem(
                text = { Text("分享音频") },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = PrimaryLight) },
                onClick = {
                    showLongPressMenu = false
                    onShare()
                }
            )
            DropdownMenuItem(
                text = { Text("制作为铃声") },
                leadingIcon = { Icon(Icons.Default.NotificationAdd, contentDescription = null, tint = PrimaryLight) },
                onClick = {
                    showLongPressMenu = false
                    onSetRingtone()
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) },
                onClick = {
                    showLongPressMenu = false
                    deleteFileChecked = false
                    showDeleteConfirm = true
                }
            )
            DropdownMenuItem(
                text = { Text("多选删除") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) },
                onClick = {
                    showLongPressMenu = false
                    onLongClick()
                }
            )
        }

        // 删除确认卡片：可勾选是否连原文件一起删除
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("删除音频") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "确定要从音频库中删除「${audio.title}」？",
                            fontSize = 14.sp,
                            color = PrimaryDark
                        )
                        // 勾选后连原文件一起删除；不勾选仅删除这条记录（原文件保留在设备上）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { deleteFileChecked = !deleteFileChecked },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = deleteFileChecked,
                                onCheckedChange = { deleteFileChecked = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color.Red)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "同时删除原文件",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Red
                                )
                                Text(
                                    text = "勾选后将彻底移除文件，不可恢复；不勾选则仅删除该条记录",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            onDelete(deleteFileChecked)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
            )
        }
    }
}

/**
 * 分享音频文件（通过 FileProvider + 系统分享面板）
 */
private fun shareAudioFile(context: Context, filePath: String) {
    try {
        val file = java.io.File(filePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享音频"))
    } catch (e: Exception) {}
}

@Composable
private fun PermissionBanner(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "需要音频媒体存储权限",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF92400E)
                )
                Text(
                    text = "请授予读取本地音频权限以扫描和播放歌曲",
                    fontSize = 11.sp,
                    color = Color(0xFFB45309)
                )
            }
            Button(
                onClick = {
                    onRequestPermission()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("授权", fontSize = 12.sp)
            }
        }
    }
}



// ========== Preview 模拟数据 & 预览函数 ==========
private fun mockAudioItem(id: Long, title: String, artist: String = "未知艺术家"): AudioItem {
    return AudioItem(
        id = id,
        title = title,
        artist = artist,
        filePath = "/storage/0/$id.mp3",
        durationMs = 204000L
    )
}

@Preview(showBackground = true, name = "音频库-正常列表")
@Composable
fun PreviewAudioLibraryNormal() {
    Mp3PlayerTheme {
        val mockAudios = listOf(
            mockAudioItem(21, "春风十里"),
            mockAudioItem(22, "夜的钢琴曲"),
            mockAudioItem(23, "起风了", "买辣椒也用券"),
            mockAudioItem(24, "晴天", "周杰伦")
        )
        AudioLibraryPreviewWrapper(
            displayAudios = mockAudios,
            browseMode = BrowseMode.ALL,
            favoriteIds = setOf(21),
            isScanning = false,
            hasPermission = true,
            isSelectionMode = false,
            selectedAudioIds = emptySet(),
            currentPlayingId = 21,
            isPlaying = true
        )
    }
}

@Preview(showBackground = true, name = "音频库-空页面")
@Composable
fun PreviewAudioLibraryEmpty() {
    Mp3PlayerTheme {
        AudioLibraryPreviewWrapper(
            displayAudios = emptyList(),
            browseMode = BrowseMode.ALL,
            favoriteIds = emptySet(),
            isScanning = false,
            hasPermission = true,
            isSelectionMode = false,
            selectedAudioIds = emptySet(),
            currentPlayingId = null,
            isPlaying = false
        )
    }
}

@Preview(showBackground = true, name = "音频库-多选模式")
@Composable
fun PreviewAudioLibrarySelectMode() {
    Mp3PlayerTheme {
        val mockAudios = listOf(
            mockAudioItem(21, "春风十里"),
            mockAudioItem(22, "夜的钢琴曲"),
            mockAudioItem(23, "起风了", "买辣椒也用券")
        )
        AudioLibraryPreviewWrapper(
            displayAudios = mockAudios,
            browseMode = BrowseMode.ALL,
            favoriteIds = emptySet(),
            isScanning = false,
            hasPermission = true,
            isSelectionMode = true,
            selectedAudioIds = setOf(21, 23),
            currentPlayingId = null,
            isPlaying = false
        )
    }
}

@Preview(showBackground = true, name = "音频库-无权限提示")
@Composable
fun PreviewAudioLibraryNoPermission() {
    Mp3PlayerTheme {
        AudioLibraryPreviewWrapper(
            displayAudios = emptyList(),
            browseMode = BrowseMode.ALL,
            favoriteIds = emptySet(),
            isScanning = false,
            hasPermission = false,
            isSelectionMode = false,
            selectedAudioIds = emptySet(),
            currentPlayingId = null,
            isPlaying = false
        )
    }
}

/**
 * 预览专用包装组件：剥离ViewModel，传入静态模拟状态，复用原有UI代码
 */
@Composable
private fun AudioLibraryPreviewWrapper(
    displayAudios: List<AudioItem>,
    browseMode: BrowseMode,
    favoriteIds: Set<Long>,
    isScanning: Boolean,
    hasPermission: Boolean,
    isSelectionMode: Boolean,
    selectedAudioIds: Set<Long>,
    currentPlayingId: Long?,
    isPlaying: Boolean
) {
    // 模拟其它状态固定值
    val searchQuery = ""
    val currentAudio = displayAudios.find { it.id == currentPlayingId }
    val currentPositionMs = 40_000L
    val durationMs = currentAudio?.durationMs ?: 100_000L
    val highlightedAudioId: Long? = null

    val favoriteAudios = remember(displayAudios, favoriteIds) {
        displayAudios.filter { favoriteIds.contains(it.id) }
    }

    val listState = rememberLazyListState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = PrimaryDark)
                        }
                        Text(
                            text = "已选中 ${selectedAudioIds.size} 项",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDark
                        )
                    }
                    Row {
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                        }
                    }
                } else {
                    Text(
                        text = "本地音频库",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var showImportMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showImportMenu = true }) {
                                Icon(Icons.Default.AddCircle, contentDescription = "Import", tint = PrimaryDark)
                            }
                            DropdownMenu(
                                expanded = showImportMenu,
                                onDismissRequest = { showImportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("从文件导入") },
                                    leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                                    onClick = { showImportMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("扫描本地设备") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    onClick = { showImportMenu = false; showScanDialog = true }
                                )
                            }
                        }
                        IconButton(onClick = {}) {
                            if (isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = PrimaryDark)
                            }
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = PrimaryDark)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {},
                    placeholder = { Text("搜索音频名称、艺术家、文件夹...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {},
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White,
                        unfocusedBorderColor = BorderLight,
                        focusedBorderColor = PrimaryLight
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { showFilterSheet = true },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = PrimaryLight)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariantLight)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ModeTabItem(
                    label = "全部 (${displayAudios.size})",
                    isSelected = browseMode == BrowseMode.ALL,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
                ModeTabItem(
                    label = "我的收藏 (${favoriteAudios.size})",
                    isSelected = browseMode == BrowseMode.FAVORITES,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!hasPermission) {
                PermissionBanner(onRequestPermission = {})
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (displayAudios.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.MusicOff,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isScanning) "正在扫描已导入音频..." else "音频库为空，点击右上角 + 导入音频",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                        if (!isScanning) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showScanDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("立即扫描设备")
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .drawVerticalScrollbar(listState),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            val listData = when (browseMode) {
                                BrowseMode.ALL -> displayAudios
                                BrowseMode.FAVORITES -> favoriteAudios
                            }
                            if (listData.isEmpty()) {
                                item {
                                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("暂无已收藏音频", color = TextSecondary)
                                    }
                                }
                            } else {
                                items(listData, key = { it.id }) { audio ->
                                    AudioItemRowOptimized(
                                        audio = audio,
                                        isCurrent = currentAudio?.id == audio.id,
                                        isPlaying = isPlaying,
                                        isSelected = selectedAudioIds.contains(audio.id),
                                        isSelectionMode = isSelectionMode,
                                        isHighlighted = highlightedAudioId == audio.id,
                                        isFavorite = favoriteIds.contains(audio.id),
                                        onToggleFavorite = {},
                                        onLongClick = {},
                                        onShare = {},
                                        onSetRingtone = {},
                                        onClick = {}
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingPlayerBar(
            currentAudio = currentAudio,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            onTogglePlayPause = {},
            onFastForward5s = {},
            onRewind5s = {},
            onPlayPrevious = {},
            onPlayNext = {},
            onSeekTo = {},
            onClickBar = {},
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 80.dp)
        )
    }
}

// 子组件单独预览（可选，推荐加上）
@Preview(showBackground = true)
@Composable
fun PreviewAudioItemRow() {
    Mp3PlayerTheme {
        AudioItemRowOptimized(
            audio = mockAudioItem(21, "测试歌曲", "测试歌手"),
            isCurrent = true,
            isPlaying = true,
            isFavorite = true,
            onClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewPermissionBanner() {
    Mp3PlayerTheme {
        PermissionBanner(onRequestPermission = {})
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewModeTab() {
    Mp3PlayerTheme {
        Row(modifier = Modifier.fillMaxWidth().background(SurfaceVariantLight).padding(3.dp)) {
            ModeTabItem(label = "全部 (10)", isSelected = true, onClick = {}, modifier = Modifier.weight(1f))
            ModeTabItem(label = "我的收藏 (3)", isSelected = false, onClick = {}, modifier = Modifier.weight(1f))
        }
    }
}


