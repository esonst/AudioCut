package com.example.audiocut.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.navigation.AppScreen
import com.example.audiocut.ui.components.FloatingPlayerBar
import com.example.audiocut.ui.components.ModelInstallDialogHost
import com.example.audiocut.ui.components.OptimizedTranscriptView
import com.example.audiocut.ui.theme.*
import com.example.audiocut.viewmodel.ClipViewModel
import com.example.audiocut.viewmodel.MainViewModel
import com.example.audiocut.viewmodel.TranscriptViewModel
import kotlin.math.abs

/**
 * 文稿与音频剪辑页面
 * 针对长音频进行了性能优化：
 * 1. 展平渲染：将段落作为 LazyColumn 的独立 items，按需加载和重构。
 * 2. 局部重组：计算 activeWordId，避免播放进度更新时全量文稿重组。
 * 3. 预计算：在 ViewModel 中预处理片段包含状态，避免 UI 线程 O(W*S) 计算。
 */
@Composable
fun TranscriptScreen(
    mainViewModel: MainViewModel,
    transcriptViewModel: TranscriptViewModel,
    clipViewModel: ClipViewModel,
    isPageVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val currentAudio by mainViewModel.currentPlayingAudio.collectAsState()
    val isPlaying by mainViewModel.isPlaying.collectAsState()
    val currentPositionMs by mainViewModel.currentPositionMs.collectAsState()
    val durationMs by mainViewModel.durationMs.collectAsState()
    val isAsrLoading by transcriptViewModel.isAsrLoading.collectAsState()
    val asrProgress by transcriptViewModel.asrProgress.collectAsState()
    val transcriptResult by transcriptViewModel.transcriptResult.collectAsState()
    val segments by clipViewModel.segments.collectAsState()
    val asrProgressText by transcriptViewModel.asrProgressText.collectAsState()
    val asrChunkSeconds by transcriptViewModel.asrChunkSeconds.collectAsState()
    val asrLog by transcriptViewModel.asrLog.collectAsState()
    val modelInstallState by transcriptViewModel.modelInstallState.collectAsState()
    val isOptimizing by transcriptViewModel.isOptimizing.collectAsState()
    val layoutOptimizationEnabled by transcriptViewModel.layoutOptimizationEnabled.collectAsState()
    val asrStartMs by transcriptViewModel.asrStartMs.collectAsState()
    val asrEndMs by transcriptViewModel.asrEndMs.collectAsState()
    // 正在编辑的识别范围字段（开始/结束），非空时弹出滚动时间选择卡片
    var editingRangeField by remember { mutableStateOf<RangeField?>(null) }

    // 文稿交互状态，支持跨段落位置记录
    val wordBoundsMap = remember { mutableStateMapOf<Long, Rect>() }
    var parentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragStartWordId by remember { mutableStateOf<Long?>(null) }

    val transcriptListState = rememberLazyListState()

    // 关键优化：根据当前播放时间计算出活跃的字 ID
    // 只有活跃字所在的段落和之前活跃字所在的段落会受播放进度影响重组
    val activeWordId by remember(currentPositionMs, transcriptResult) {
        derivedStateOf {
            transcriptResult?.words?.find { currentPositionMs in it.startMs..it.endMs }?.id
        }
    }

    // 预计算哪些字在标记片段中，避免 UI 渲染循环中 O(WS) 查找
    val wordsInSegmentsIds by remember(transcriptResult, segments) {
        derivedStateOf {
            val transcript = transcriptResult ?: return@derivedStateOf emptySet<Long>()
            if (segments.isEmpty()) return@derivedStateOf emptySet<Long>()
            val merged = mutableListOf<Pair<Long, Long>>()
            for (seg in segments.sortedBy { it.startMs }) {
                val last = merged.lastOrNull()
                if (last != null && seg.startMs <= last.second) {
                    merged[merged.size - 1] = last.first to maxOf(last.second, seg.endMs)
                } else {
                    merged.add(seg.startMs to seg.endMs)
                }
            }
            val starts = merged.map { it.first }
            transcript.words.filter { w ->
                val idx = starts.binarySearch(w.startMs).let { if (it >= 0) it else -it - 2 }
                idx >= 0 && w.endMs <= merged[idx].second
            }.map { it.id }.toSet()
        }
    }

    // 取两者最大值：任一来源异常（为 0 或极小值）时用另一个兜底
    val totalDuration = maxOf(durationMs, currentAudio?.durationMs ?: 0L)

    // ========= ASR 时间计算（基于识别范围跨度） =========
    val rangeStart = asrStartMs.coerceIn(0L, totalDuration)
    val rangeEnd = if (asrEndMs > 0L) asrEndMs.coerceIn(rangeStart, totalDuration) else totalDuration
    val rangeSpanMs = (rangeEnd - rangeStart).coerceAtLeast(1L)
    val elapsedMs = (rangeSpanMs * asrProgress).toLong()
    val remainMs = rangeSpanMs - elapsedMs

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // ====== 顶部行：左侧音频名称时长，右上角按钮 ======
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentAudio?.title ?: "未选择音频",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "时长: ${AudioItem.formatDuration(totalDuration)}",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }

                // 右上角：开始 / 停止按钮
                if(currentAudio != null) {
                    if (isAsrLoading) {
                        Button(
                            onClick = { transcriptViewModel.stopAsrRecognition() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("停止", fontSize = 13.sp, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = { transcriptViewModel.startAsrRecognition(resumeIfPossible = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("开始识别", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 识别范围：开始 / 结束时间标签，点击弹出滚动时间选择卡片
            if (currentAudio != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("识别范围", fontSize = 11.sp, color = TextSecondary)
                    RangeTimeLabel(
                        text = "开始 ${formatHms(rangeStart / 1000)}",
                        onClick = { editingRangeField = RangeField.START },
                        enabled = !isAsrLoading,
                        modifier = Modifier.weight(1f)
                    )
                    RangeTimeLabel(
                        text = "结束 ${formatHms(rangeEnd / 1000)}",
                        onClick = { editingRangeField = RangeField.END },
                        enabled = !isAsrLoading,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (currentAudio == null) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text("请先在音频库中选择音频进行识别", fontSize = 14.sp, color = TextMuted)
                }
            } else {
                // 文稿卡片最大高度限制在悬浮播放栏上边缘上方（悬浮栏约 62dp + 间隙），
                // 保证识别文稿卡片内容不会超出悬浮框被遮挡
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val maxCardHeight = (maxHeight - 80.dp).coerceAtLeast(150.dp)
                    // LazyColumn 视口底部停在悬浮播放栏上边缘上方（悬浮栏约 62dp + 间隙），
                    // 文稿滚动到任意位置都不会被悬浮播放栏遮挡
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                    // ASR 控制区域（只放进度条 + 时间）
                    item {
                        AsrControlCard(
                            isAsrLoading = isAsrLoading,
                            asrProgress = asrProgress,
                            elapsedMs = elapsedMs,
                            remainMs = remainMs,
                            asrProgressText = asrProgressText,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    }

                    // 处理日志（分块/VAD/识别/智能分句）
//                    if (asrLog.isNotEmpty()) {
//                        item {
//                            AsrLogCard(
//                                logs = asrLog,
//                                modifier = Modifier.padding(bottom = 14.dp)
//                            )
//                        }
//                    }

                    // 文稿渲染（按段落拆分 item）
                    transcriptResult?.let { result ->
                        if (result.words.isNotEmpty()) {
                            item {
                                TranscriptHeaderView(
                                    transcriptViewModel = transcriptViewModel,
                                    isOptimizing = isOptimizing,
                                    wordCount = result.words.size,
                                    fullText = result.fullText,
                                    showLayoutOptimization = layoutOptimizationEnabled
                                )
                            }

                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column {
                                        OptimizedTranscriptView(
                                            transcriptResult = result,
                                            activeWordId = activeWordId,
                                            wordsInSegmentsIds = wordsInSegmentsIds,
                                            onSelectionChanged = { transcriptViewModel.setSelectedTextRange(it) },
                                            onWordClick = { startMs ->
                                                // 点击文字只跳转播放进度，不改变播放状态（暂停保持暂停、播放保持播放）
                                                mainViewModel.mainSeekTo(startMs)
                                            },
                                            onCreateSegment = { transcriptViewModel.createSegmentFromSelection() },
                                            onCreateTrimRange = { transcriptViewModel.createTrimFromSelection() },
                                            isPlaying = isPlaying,
                                            isPageVisible = isPageVisible,
                                            modifier = Modifier.heightIn(max = maxCardHeight)
                                        )
                                    }
                                }
                            }

//                            item {
//                                Spacer(modifier = Modifier.height(16.dp).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)).background(Color.White))
//                            }
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
            onTogglePlayPause = { mainViewModel.toggleMainPlayPause() },
            onFastForward5s = { mainViewModel.mainFastForwardOrRewind(5) },
            onRewind5s = { mainViewModel.mainFastForwardOrRewind(-5) },
            onPlayPrevious = { mainViewModel.playMainPrevious() },
            onPlayNext = { mainViewModel.playMainNext() },
            onSeekTo = { mainViewModel.mainSeekTo(it) },
            onClickBar = { mainViewModel.navigateTo(AppScreen.TRANSCRIPT) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // 模型下载/导入对话框（开始识别时模型缺失 → 提示下载或导入）
    ModelInstallDialogHost(
        state = modelInstallState,
        onDownload = { transcriptViewModel.downloadPromptedModel() },
        onImport = { uri -> transcriptViewModel.importPromptedModel(uri) },
        onDismiss = { transcriptViewModel.dismissModelDialog() },
        onStopDownload = { transcriptViewModel.stopModelDownload() }
    )

    // 时间滚动选择卡片（开始 / 结束）
    val editingField = editingRangeField
    if (editingField != null && currentAudio != null) {
        val editingStart = editingField == RangeField.START
        TimeScrollPickerDialog(
            title = if (editingStart) "设置开始时间" else "设置结束时间",
            initialSeconds = (if (editingStart) rangeStart else rangeEnd) / 1000,
            otherSeconds = (if (editingStart) rangeEnd else rangeStart) / 1000,
            audioDurationSec = totalDuration / 1000,
            isStart = editingStart,
            onConfirm = { totalSec ->
                val ms = totalSec * 1000L
                if (editingStart) transcriptViewModel.setAsrStartMs(ms)
                else transcriptViewModel.setAsrEndMs(ms)
                editingRangeField = null
            },
            onReset = {
                transcriptViewModel.resetAsrRange()
                editingRangeField = null
            },
            onDismiss = { editingRangeField = null }
        )
    }
}

@Composable fun AsrControlCard(
    isAsrLoading: Boolean,
    asrProgress: Float,
    elapsedMs: Long,
    remainMs: Long,
    asrProgressText: String,
    modifier: Modifier = Modifier
) {
    if (isAsrLoading) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                if (isAsrLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { asrProgress },
                            modifier = Modifier.weight(1f).height(4.dp),
                            color = PrimaryLight,
                            trackColor = SurfaceVariantLight
                        )
                        if (asrProgressText.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = asrProgressText, fontSize = 11.sp, color = PrimaryLight, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun AsrLogCard(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = PrimaryLight,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "处理日志",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 固定高度可滚动日志区（嵌套在外层 LazyColumn 中）
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                items(logs) { log ->
                    Text(
                        text = log,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptHeaderView(
    transcriptViewModel: TranscriptViewModel,
    isOptimizing: Boolean,
    wordCount: Int,
    fullText: String,
    showLayoutOptimization: Boolean = true
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        color = Color.White
    ) {
        Column(modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatQuote, contentDescription = null, tint = PrimaryLight, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "识别文稿 ($wordCount 字)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        transcriptViewModel.deleteCurrentTranscript()
                    }) {
                        Text("删除文稿", fontSize = 12.sp, color = Color.Red.copy(alpha = 0.7f))
                    }
                    // 排版优化：使用 punct 模型删除标点后重新添加标点（需先在设置中开启排版优化）
                    if (showLayoutOptimization) {
                    TextButton(
                        onClick = { transcriptViewModel.optimizeTranscriptLayout() },
                        enabled = !isOptimizing
                    ) {
                        if (isOptimizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = PrimaryLight
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = if (isOptimizing) "优化中..." else "排版优化",
                            fontSize = 12.sp,
                            color = if (isOptimizing) TextMuted else PrimaryLight
                        )
                    }
                    }
                }
            }
        }
    }

}


/** 识别范围可编辑的时间字段 */
private enum class RangeField { START, END }

/**
 * 识别范围时间标签：点击弹出滚动时间选择卡片
 */
@Composable
private fun RangeTimeLabel(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) PrimaryLight.copy(alpha = 0.10f) else SurfaceVariantLight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) PrimaryLight else TextMuted,
            maxLines = 1
        )
    }
}

/**
 * 时间滚动选择卡片：hh:mm:ss 三列滚轮，上下滚动调整
 */
@Composable
private fun TimeScrollPickerDialog(
    title: String,
    initialSeconds: Long,
    otherSeconds: Long,
    audioDurationSec: Long,
    isStart: Boolean,
    onConfirm: (totalSeconds: Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var totalSeconds by remember { mutableStateOf(initialSeconds.coerceAtLeast(0L)) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatHms(totalSeconds),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    TimeWheelColumn(
                        value = (totalSeconds / 3600).toInt(),
                        max = 999,
                        label = "时",
                        onValueChange = { h -> totalSeconds = h * 3600L + totalSeconds % 3600L }
                    )
                    WheelColon()
                    TimeWheelColumn(
                        value = ((totalSeconds % 3600) / 60).toInt(),
                        max = 59,
                        label = "分",
                        onValueChange = { m -> totalSeconds = (totalSeconds / 3600) * 3600 + m * 60L + totalSeconds % 60L }
                    )
                    WheelColon()
                    TimeWheelColumn(
                        value = (totalSeconds % 60).toInt(),
                        max = 59,
                        label = "秒",
                        onValueChange = { s -> totalSeconds = (totalSeconds / 60) * 60 + s }
                    )
                }
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(errorText!!, fontSize = 12.sp, color = Color(0xFFDC2626))
                }
                if (audioDurationSec > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("音频总时长 ${formatHms(audioDurationSec)}", fontSize = 11.sp, color = TextMuted)
                }
                TextButton(
                    onClick = { onReset() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("重置为整个音频", fontSize = 12.sp, color = PrimaryLight)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    isStart && totalSeconds >= otherSeconds ->
                        errorText = "开始时间必须早于结束时间"
                    !isStart && totalSeconds <= otherSeconds ->
                        errorText = "结束时间必须晚于开始时间"
                    audioDurationSec > 0 && totalSeconds > audioDurationSec ->
                        errorText = "时间不能超过音频总时长"
                    else -> onConfirm(totalSeconds)
                }
            }) { Text("确定", color = PrimaryLight) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 时:分:秒 之间的冒号，高度与滚轮一致并对齐中央选中行 */
@Composable
private fun WheelColon() {
    Box(
        modifier = Modifier.width(10.dp).height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = ":",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryDark
        )
    }
}

/**
 * 单个时间滚轮列：上下滚动调整数值，松手由官方 SnapFlingBehavior 自动吸附到中央项
 */
@Composable
private fun TimeWheelColumn(
    value: Int,
    max: Int,
    label: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemHeight = 40.dp
    val halfItems = 1
    val listState = rememberLazyListState()
    // 吸附交给官方实现：拖拽 / 惯性滚动结束后，最近一项自动停在视口中央
    val flingBehavior = rememberSnapFlingBehavior(listState)

    // 打开时一次性把初始值停在中央（contentPadding 上方留 halfItems 项，scrollToItem 后该项恰在中央槽）
    LaunchedEffect(Unit) {
        listState.scrollToItem(value.coerceIn(0, max))
    }

    // 选中项 = 视口中央最近的一项（item.offset 为内容坐标，需加上 viewportStartOffset 换算到视口中心）
    val selectedIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) {
                -1
            } else {
                val center = info.viewportStartOffset + info.viewportSize.height / 2
                info.visibleItemsInfo.minByOrNull { item ->
                    abs(item.offset + item.size / 2 - center)
                }?.index ?: -1
            }
        }
    }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in 0..max) onValueChange(selectedIndex)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(itemHeight * (halfItems * 2 + 1))
        ) {
            // 中央高亮条
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PrimaryLight.copy(alpha = 0.12f))
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = flingBehavior,
                contentPadding = PaddingValues(vertical = itemHeight * halfItems)
            ) {
                items(max + 1) { index ->
                    val selected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = index.toString().padStart(2, '0'),
                            fontSize = if (selected) 20.sp else 16.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) PrimaryDark else TextMuted
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = TextSecondary)
    }
}

/** 秒数格式化为 hh:mm:ss */
private fun formatHms(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
