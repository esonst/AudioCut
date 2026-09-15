package com.example.audiocut.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.navigation.PlayerTab
import com.example.audiocut.ui.components.SegmentEditorHeader
import com.example.audiocut.ui.components.SegmentItemCard
import com.example.audiocut.ui.theme.*
import com.example.audiocut.viewmodel.ClipViewModel
import com.example.audiocut.viewmodel.MainViewModel

/**
 * 剪辑页面（独立的标记片段管理与音频切片合成导出界面）
 * 包含顶部 Tab 联动、片段滑动调节与 0.1s 精确微调、片段试听与多格式拼接导出
 */
@Composable
fun ClipScreen(mainViewModel: MainViewModel, clipViewModel: ClipViewModel, modifier: Modifier = Modifier) {
    val currentAudio by mainViewModel.currentPlayingAudio.collectAsState()
    val isPlaying by mainViewModel.isPlaying.collectAsState()
    val durationMs by mainViewModel.durationMs.collectAsState()
    val currentPositionMs by mainViewModel.currentPositionMs.collectAsState()
    val segments by clipViewModel.segments.collectAsState()
    val previewingSegmentId by clipViewModel.previewingSegmentId.collectAsState()
    val isExporting by clipViewModel.isExporting.collectAsState()
    val exportProgress by clipViewModel.exportProgress.collectAsState()

    // 合并试听状态
    val isGeneratingMergedPreview by clipViewModel.isGeneratingMergedPreview.collectAsState()
    val mergedPreviewResult by clipViewModel.mergedPreviewResult.collectAsState()
    val isMergedPreviewPlaying by clipViewModel.isMergedPreviewPlaying.collectAsState()
    val mergedPreviewPositionMs by clipViewModel.mergedPreviewPositionMs.collectAsState()
    val mergedPreviewDurationMs by clipViewModel.mergedPreviewDurationMs.collectAsState()

    val totalDuration = if (durationMs > 0) durationMs else currentAudio?.durationMs ?: 0L

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundLight)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentAudio?.title ?: "未选择音频",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            
            // 右上角【合并】按钮：点击执行合并预览
//            OutlinedButton(
//                onClick = { clipViewModel.startOrToggleMergedPreview() },
//                enabled = !isGeneratingMergedPreview,
//                shape = RoundedCornerShape(10.dp),
//                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryLight),
//                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
//            ) {
//                if (isGeneratingMergedPreview) {
//                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PrimaryLight)
//                    Spacer(modifier = Modifier.width(6.dp))
//                    Text("生成中", fontSize = 12.sp)
//                } else {
//                    Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = null, modifier = Modifier.size(15.dp))
//                    Text("合并", fontSize = 12.sp)
//                }
//            }
        }

        Text(
            text = "时长: ${AudioItem.formatDuration(totalDuration)} · 标记片段数: ${segments.size}",
            fontSize = 12.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (currentAudio == null) {
            Box(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("请先在音频库中选择需要剪辑的音频", fontSize = 14.sp, color = TextMuted)
            }
        } else {
            val listState = rememberLazyListState()
            var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                if (segments.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = null,
                                    tint = PrimaryLight,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "暂无标记片段",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryDark
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "您可以前往【文稿】页面长按滑动选中文本一键创建片段，在此进行 0.1s 微调、试听和拼接导出",
                                    fontSize = 13.sp,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            mainViewModel.switchTab(PlayerTab.TRANSCRIPT)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("前往文稿选词标记", fontSize = 13.sp)
                                    }

                                    // 空白状态下的新增片段按钮：在当前播放位置创建片段卡片
                                    IconButton(onClick = { clipViewModel.createManualSegmentAtCurrentPos() }) {
                                        Icon(Icons.Default.AddCircleOutline, contentDescription = "新建片段", tint = PrimaryLight)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        SegmentEditorHeader(
                            segmentsCount = segments.size,
                            selectedSegments = segments.filter { it.isSelected },
                            isExporting = isExporting,
                            exportProgress = exportProgress,
                            isGeneratingMergedPreview = isGeneratingMergedPreview,
                            mergedPreviewResult = mergedPreviewResult,
                            isMergedPreviewPlaying = isMergedPreviewPlaying,
                            mergedPreviewPositionMs = mergedPreviewPositionMs,
                            mergedPreviewDurationMs = mergedPreviewDurationMs,
                            onStartOrToggleMergedPreview = { clipViewModel.startOrToggleMergedPreview() },
                            onSeekMergedPreview = { clipViewModel.seekMergedPreview(it) },
                            onRewindMergedPreview = { clipViewModel.rewindMergedPreview(it) },
                            onCloseMergedPreview = { clipViewModel.closeMergedPreview() },
                            onRenamePreviewFile = { clipViewModel.renamePreviewFile(it) },
                            onOverwriteOriginal = { clipViewModel.overwriteOriginalWithMerged() },
                            onSavePreview = { name, uri -> clipViewModel.savePreviewToLocation(name, uri) },
                            onSharePreview = { clipViewModel.shareMergedPreview() },
                            onConvertPreview = { mainViewModel.navigateToConvertFormat(mergedPreviewResult?.outputPath) }
                        )
                    }

                    itemsIndexed(segments, key = { _, seg -> seg.id }) { index, segment ->
                        val isDragging = draggingItemIndex == index
                        
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var renameText by remember { mutableStateOf(segment.title) }

                        SegmentItemCard(
                            segment = segment,
                            maxDurationMs = totalDuration,
                            currentPositionMs = currentPositionMs,
                            isCurrentlyPreviewing = previewingSegmentId == segment.id && isPlaying,
                            onToggleSelect = { clipViewModel.toggleSegmentSelected(segment.id) },
                            onUpdateRange = { start, end -> clipViewModel.updateSegmentRange(segment.id, start, end) },
                            onRenameClick = { 
                                renameText = segment.title
                                showRenameDialog = true 
                            },
                            onCopy = { clipViewModel.copySegment(segment.id) },
                            onDelete = { clipViewModel.deleteSegment(segment.id) },
                            onPreview = { clipViewModel.previewSegment(segment) },
                            modifier = Modifier
                                .animateItem() // 实现丝滑移位占位
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffset else 0f
                                    scaleX = if (isDragging) 1.02f else 1f
                                    scaleY = if (isDragging) 1.02f else 1f
                                    alpha = if (isDragging) 0.95f else 1f
                                }
                                .zIndex(if (isDragging) 1f else 0f)
                                .pointerInput(segments) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingItemIndex = index },
                                        onDragEnd = {
                                            draggingItemIndex = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            draggingItemIndex = null
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset += dragAmount.y
                                            
                                            // 优化索引交换逻辑
                                            val threshold = 60f
                                            if (dragOffset > threshold && index < segments.size - 1) {
                                                clipViewModel.reorderSegments(index, index + 1)
                                                draggingItemIndex = index + 1
                                                dragOffset -= threshold * 1.2f
                                            } else if (dragOffset < -threshold && index > 0) {
                                                clipViewModel.reorderSegments(index, index - 1)
                                                draggingItemIndex = index - 1
                                                dragOffset += threshold * 1.2f
                                            }
                                        }
                                    )
                                }
                        )

                        if (showRenameDialog) {
                            AlertDialog(
                                onDismissRequest = { showRenameDialog = false },
                                title = { Text("重命名") },
                                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
                                confirmButton = { 
                                    Button(onClick = { 
                                        clipViewModel.renameSegment(segment.id, renameText)
                                        showRenameDialog = false 
                                    }) { Text("确定") }
                                },
                                dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
                            )
                        }
                    }

                    // 新增片段按钮：放在剪辑卡片列表下方
                    item {
                        OutlinedButton(
                            onClick = { clipViewModel.createManualSegmentAtCurrentPos() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryLight)
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("新建片段", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
