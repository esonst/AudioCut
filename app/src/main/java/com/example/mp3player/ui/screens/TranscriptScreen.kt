package com.example.mp3player.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mp3player.data.model.AudioItem
import com.example.mp3player.ui.components.*
import com.example.mp3player.ui.theme.*
import com.example.mp3player.viewmodel.AppScreen
import com.example.mp3player.viewmodel.MainViewModel
import androidx.compose.ui.tooling.preview.Preview
import com.example.mp3player.data.model.AudioSegment
import com.example.mp3player.data.model.TranscriptResult
import com.example.mp3player.data.model.TranscriptWord


/**
 * 文稿与音频剪辑页面
 * 针对长音频进行了性能优化：
 * 1. 展平渲染：将段落作为 LazyColumn 的独立 items，按需加载和重构。
 * 2. 局部重组：计算 activeWordId，避免播放进度更新时全量文稿重组。
 * 3. 预计算：在 ViewModel 中预处理片段包含状态，避免 UI 线程 O(W*S) 计算。
 */
@Composable fun TranscriptScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val currentAudio by viewModel.currentPlayingAudio.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val isAsrLoading by viewModel.isAsrLoading.collectAsState()
    val asrProgress by viewModel.asrProgress.collectAsState()
    val transcriptResult by viewModel.transcriptResult.collectAsState()
    val wordsInSegmentsIds by viewModel.wordsInSegmentsIds.collectAsState()
    val segments by viewModel.segments.collectAsState()
    val asrProgressText by viewModel.asrProgressText.collectAsState()
    val asrChunkSeconds by viewModel.asrChunkSeconds.collectAsState()

    // 文本选择拖动期间隐藏浮动播放卡片
    var isDraggingSelection by remember { mutableStateOf(false) }

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

    // 取两者最大值：任一来源异常（为 0 或极小值）时用另一个兜底
    val totalDuration = maxOf(durationMs, currentAudio?.durationMs ?: 0L)

    // ========= ASR 时间计算 =========
    val elapsedMs = (totalDuration * asrProgress).toLong()
    val remainMs = totalDuration - elapsedMs

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
                            onClick = { viewModel.stopAsrRecognition() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("停止", fontSize = 13.sp, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startAsrRecognition(resumeIfPossible = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("开始识别", fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (currentAudio == null) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text("请先在音频库中选择音频进行识别", fontSize = 14.sp, color = TextMuted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 150.dp)
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

                    // 文稿渲染（按段落拆分 item）
                    transcriptResult?.let { result ->
                        if (result.words.isNotEmpty()) {
                            item {
                                TranscriptHeaderView(
                                    viewModel = viewModel,
                                    wordCount = result.words.size,
                                    fullText = result.fullText
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
                                            onSelectionChanged = { viewModel.setSelectedTextRange(it) },
                                            onWordClick = { startMs ->
                                                viewModel.seekToAndPlay(startMs)
                                            },
                                            onCreateSegment = { viewModel.createSegmentFromTextSelection(it) },
                                            onCreateTrimRange = { viewModel.createTrimRangeFromTextSelection(it) },
                                            onSelectionDragChanged = { isDraggingSelection = it },
                                            modifier = Modifier.heightIn(max = 600.dp)
                                        )
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(16.dp).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)).background(Color.White))
                            }
                        }
                    }
                }
            }
        }

        if (!isDraggingSelection) {
            FloatingPlayerBar(
                currentAudio = currentAudio,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onTogglePlayPause = { viewModel.toggleMainPlayPause() },
                onFastForward5s = { viewModel.mainFastForwardOrRewind(5) },
                onRewind5s = { viewModel.mainFastForwardOrRewind(-5) },
                onPlayPrevious = { viewModel.playMainPrevious() },
                onPlayNext = { viewModel.playMainNext() },
                onSeekTo = { viewModel.mainSeekTo(it) },
                onClickBar = { viewModel.navigateTo(AppScreen.TRANSCRIPT) }, // 跳转到自身（或根据需要调整）
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 80.dp)
            )
        }
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
fun TranscriptHeaderView(
    viewModel: MainViewModel,
    wordCount: Int,
    fullText: String
) {
    var showCopyDialog by remember { mutableStateOf(false) }

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
                        viewModel.deleteCurrentTranscript()
                    }) {
                        Text("删除文稿", fontSize = 12.sp, color = Color.Red.copy(alpha = 0.7f))
                    }
                    TextButton(onClick = {
                        viewModel.navigateTo(AppScreen.CLIP)
                    }) {
                        Text("进入剪辑", fontSize = 12.sp, color = PrimaryLight)
                    }
                }
            }
        }
    }

    if (showCopyDialog) {
        val clipboardManager = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text("复制文稿") },
            text = { Text("确认复制识别到的全文内容到剪贴板？", fontSize = 14.sp) },
            confirmButton = {
                Button(onClick = {
                    clipboardManager.setText(AnnotatedString(fullText))
                    showCopyDialog = false
                }) { Text("复制全文") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyDialog = false }) { Text("关闭") }
            }
        )
    }
}
