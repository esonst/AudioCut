package com.example.audiocut.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.data.model.AudioSegment
import com.example.audiocut.navigation.AppScreen
import com.example.audiocut.ui.components.AudioPreviewCard
import com.example.audiocut.ui.theme.*
import com.example.audiocut.viewmodel.MainViewModel
import com.example.audiocut.viewmodel.TrimViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 裁剪页面（风格与操作与【剪辑】保持一致，主题色为淡红色）
 * 支持从【文稿】长按文本【删除】生成裁剪卡片，预览时将原音频对应部分剪掉并拼接剩余内容
 * 提供 覆盖原文件 / 另存为 / 分享 三种导出方式
 */
@Composable
fun TrimScreen(mainViewModel: MainViewModel, trimViewModel: TrimViewModel, modifier: Modifier = Modifier) {
    val currentAudio by mainViewModel.currentPlayingAudio.collectAsState()
    val durationMs by mainViewModel.durationMs.collectAsState()
    val currentPositionMs by mainViewModel.currentPositionMs.collectAsState()
    val trimRanges by trimViewModel.trimRanges.collectAsState()
    val previewingTrimId by trimViewModel.previewingSegmentId.collectAsState()
    val isPlaying by mainViewModel.isPlaying.collectAsState()

    // 裁剪预览状态
    val isGeneratingTrimPreview by trimViewModel.isGeneratingTrimPreview.collectAsState()
    val trimPreviewResult by trimViewModel.trimPreviewResult.collectAsState()
    val isPreviewPlaying by trimViewModel.isMergedPreviewPlaying.collectAsState()
    val previewPositionMs by trimViewModel.mergedPreviewPositionMs.collectAsState()
    val previewDurationMs by trimViewModel.mergedPreviewDurationMs.collectAsState()

    // 裁剪导出状态
    val isTrimExporting by trimViewModel.isTrimExporting.collectAsState()
    val trimExportProgress by trimViewModel.trimExportProgress.collectAsState()

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
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )

            // 右上角【裁剪】按钮：点击执行裁剪预览
            OutlinedButton(
                onClick = { trimViewModel.startOrToggleTrimPreview() },
                enabled = !isGeneratingTrimPreview && !isTrimExporting,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TrimRed),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (isGeneratingTrimPreview) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TrimRed)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("生成中", fontSize = 12.sp)
                } else {
                    Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(15.dp))
                    Text("裁剪", fontSize = 12.sp)
                }
            }
        }

        Text(
            text = "时长: ${AudioItem.formatDuration(totalDuration)} · 裁剪卡片数: ${trimRanges.size}",
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
                Text("请先在音频库中选择需要裁剪的音频", fontSize = 14.sp, color = TextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                if (trimRanges.isEmpty()) {
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
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = TrimRed,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "暂无裁剪卡片",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryDark
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "您可以前往【文稿】页面长按滑动选中文本后点击【删除】一键创建裁剪卡片，在此进行 0.1s 微调后预览剪掉所选部分后的效果",
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
                                            mainViewModel.navigateTo(AppScreen.TRANSCRIPT)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TrimRed),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("前往文稿选词标记", fontSize = 13.sp)
                                    }

                                    // 空白状态下的新增裁剪卡片按钮：在当前播放位置创建裁剪卡片
                                    IconButton(onClick = { trimViewModel.createManualTrimAtCurrentPos() }) {
                                        Icon(Icons.Default.AddCircleOutline, contentDescription = "新建裁剪卡片", tint = TrimRed)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        TrimEditorHeaderCard(
                            trimsCount = trimRanges.size,
                            selectedTrims = trimRanges.filter { it.isSelected },
                            totalDurationMs = totalDuration,
                            isGeneratingPreview = isGeneratingTrimPreview,
                            trimPreviewResult = trimPreviewResult,
                            isPreviewPlaying = isPreviewPlaying,
                            previewPositionMs = previewPositionMs,
                            previewDurationMs = previewDurationMs,
                            isTrimExporting = isTrimExporting,
                            trimExportProgress = trimExportProgress,
                            onStartOrTogglePreview = { trimViewModel.startOrToggleTrimPreview() },
                            onSeekPreview = { trimViewModel.seekMergedPreview(it) },
                            onRewindPreview = { trimViewModel.rewindMergedPreview(it) },
                            onClosePreview = { trimViewModel.closeTrimPreview() },
                            onRenamePreviewFile = { trimViewModel.renamePreviewFile(it) },
                            onOverwriteOriginal = { trimViewModel.overwriteOriginalWithTrim() },
                            onSavePreview = { name, uri -> trimViewModel.saveTrimPreviewAs(name, uri) },
                            onShare = { trimViewModel.exportTrimAndShare() }
                        )
                    }

                    itemsIndexed(trimRanges, key = { _, trim -> trim.id }) { _, trim ->
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var renameText by remember { mutableStateOf(trim.title) }

                        TrimItemCard(
                            trim = trim,
                            maxDurationMs = totalDuration,
                            isCurrentlyPreviewing = previewingTrimId == trim.id && isPlaying,
                            onToggleSelect = { trimViewModel.toggleTrimSelected(trim.id) },
                            onUpdateRange = { start, end -> trimViewModel.updateTrimRange(trim.id, start, end) },
                            onPreview = { trimViewModel.previewTrimRange(trim) },
                            onRenameClick = {
                                renameText = trim.title
                                showRenameDialog = true
                            },
                            onDelete = { trimViewModel.deleteTrimRange(trim.id) }
                        )

                        if (showRenameDialog) {
                            AlertDialog(
                                onDismissRequest = { showRenameDialog = false },
                                title = { Text("重命名") },
                                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
                                confirmButton = {
                                    Button(onClick = {
                                        trimViewModel.renameTrimRange(trim.id, renameText)
                                        showRenameDialog = false
                                    }) { Text("确定") }
                                },
                                dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
                            )
                        }
                    }

                    // 新增裁剪卡片按钮：放在裁剪卡片列表下方
                    item {
                        OutlinedButton(
                            onClick = { trimViewModel.createManualTrimAtCurrentPos() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TrimRed)
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("新建裁剪卡片", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 裁剪页面顶部控制卡片：预览（剪掉所选部分拼接播放）、覆盖原文件、保存、分享
 * 预览卡片与【剪辑】共用 AudioPreviewCard，仅主题色不同（TrimRed）
 */
@Composable
private fun TrimEditorHeaderCard(
    trimsCount: Int,
    selectedTrims: List<AudioSegment>,
    totalDurationMs: Long,
    isGeneratingPreview: Boolean,
    trimPreviewResult: com.example.audiocut.ffmpeg.ExportResult?,
    isPreviewPlaying: Boolean,
    previewPositionMs: Long,
    previewDurationMs: Long,
    isTrimExporting: Boolean,
    trimExportProgress: Float,
    onStartOrTogglePreview: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onRewindPreview: (Int) -> Unit,
    onClosePreview: () -> Unit,
    onRenamePreviewFile: (String) -> Unit,
    onOverwriteOriginal: () -> Unit,
    onSavePreview: (String, Uri?) -> Unit,
    onShare: () -> Unit
) {
    val selectedCount = selectedTrims.size
    val cutDuration = selectedTrims.sumOf { it.durationMs }
    val remainDuration = (totalDurationMs - cutDuration).coerceAtLeast(0L)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "裁剪卡片列表 ($trimsCount)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Text(
                        text = "已选 $selectedCount 段 · 将剪掉 ${AudioItem.formatDuration(cutDuration)} · 剩余 ${AudioItem.formatDuration(remainDuration)}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                OutlinedButton(
                    onClick = onStartOrTogglePreview,
                    enabled = !isTrimExporting && selectedCount > 0 && !isGeneratingPreview,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TrimRed)
                ) {
                    if (isGeneratingPreview) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TrimRed)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("生成中", fontSize = 12.sp)
                    } else {
                        Icon(if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(15.dp))
                        Text(if (trimPreviewResult != null) "试听" else "预览", fontSize = 12.sp)
                    }
                }
            }

            // 只有生成预览成功后才显示统一预览卡片（覆盖 / 保存 / 分享）
            if (trimPreviewResult != null && trimPreviewResult.isSuccess) {
                Spacer(modifier = Modifier.height(12.dp))
                AudioPreviewCard(
                    title = "裁剪预览 (剪掉 $selectedCount 段)",
                    previewFileName = File(trimPreviewResult.outputPath).name,
                    color = TrimRed,
                    isPlaying = isPreviewPlaying,
                    currentPositionMs = previewPositionMs,
                    durationMs = if (previewDurationMs > 0) previewDurationMs else remainDuration,
                    isBusy = isTrimExporting,
                    progress = trimExportProgress,
                    onTogglePlayPause = onStartOrTogglePreview,
                    onSeekTo = onSeekPreview,
                    onRewind = onRewindPreview,
                    onClose = onClosePreview,
                    onRenameFile = onRenamePreviewFile,
                    onOverwrite = onOverwriteOriginal,
                    onSave = onSavePreview,
                    onShare = onShare
                )
            }
        }
    }
}

/**
 * 裁剪卡片（淡红色风格，操作与剪辑片段卡片一致：勾选、试听、0.1s 微调、重命名、删除）
 */
@Composable
private fun TrimItemCard(
    trim: AudioSegment,
    maxDurationMs: Long,
    isCurrentlyPreviewing: Boolean,
    onToggleSelect: () -> Unit,
    onUpdateRange: (Long, Long) -> Unit,
    onPreview: () -> Unit,
    onRenameClick: () -> Unit,
    onDelete: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    fun formatPrecise(ms: Long): String {
        val totalSeconds = ms / 1000.0
        val min = (totalSeconds / 60).toInt()
        val sec = totalSeconds % 60
        return "%02d:%04.1f".format(min, sec)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TrimRed.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, TrimRed.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = trim.isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = TrimRed)
                )
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(TrimRed))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = trim.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clickable { isExpanded = !isExpanded }
                )

                IconButton(onClick = onPreview) {
                    Icon(
                        if (isCurrentlyPreviewing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        tint = TrimRed
                    )
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }

            if (isExpanded) {
                RangeSlider(
                    value = (trim.startMs.toFloat())..(trim.endMs.toFloat()),
                    onValueChange = { range ->
                        val newStart = (range.start.toLong() / 100L) * 100L
                        val newEnd = (range.endInclusive.toLong() / 100L) * 100L
                        onUpdateRange(newStart, newEnd)
                    },
                    valueRange = 0f..maxDurationMs.toFloat().coerceAtLeast(trim.endMs.toFloat() + 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = TrimRed,
                        activeTrackColor = TrimRed,
                        inactiveTrackColor = Color(0xFFE5E7EB)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 起点调节
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onUpdateRange((trim.startMs - 50L).coerceAtLeast(0L), trim.endMs) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange((trim.startMs - 50L).coerceAtLeast(0L), trim.endMs)
                            }
                        ) { Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp)) }

                        Text("起点: ${formatPrecise(trim.startMs)}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))

                        IconButton(
                            onClick = { onUpdateRange((trim.startMs + 50L).coerceAtMost(trim.endMs - 50L), trim.endMs) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange((trim.startMs + 50L).coerceAtMost(trim.endMs - 50L), trim.endMs)
                            }
                        ) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                    }

                    // 终点调节
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onUpdateRange(trim.startMs, (trim.endMs - 50L).coerceAtLeast(trim.startMs + 50L)) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange(trim.startMs, (trim.endMs - 50L).coerceAtLeast(trim.startMs + 50L))
                            }
                        ) { Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp)) }

                        Text("终点: ${formatPrecise(trim.endMs)}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))

                        IconButton(
                            onClick = { onUpdateRange(trim.startMs, (trim.endMs + 50L).coerceAtMost(maxDurationMs)) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange(trim.startMs, (trim.endMs + 50L).coerceAtMost(maxDurationMs))
                            }
                        ) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onRenameClick) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = TrimRedDark) }
                }
            }
        }
    }
}

/**
 * 长按连续步进手势 Modifier（与剪辑片段卡片一致的 0.1s 微调体验）
 */
private fun Modifier.continuousPress(
    initialDelayMs: Long = 300L,
    repeatIntervalMs: Long = 50L,
    onStep: () -> Unit
): Modifier = composed {
    val currentOnStep by rememberUpdatedState(onStep)
    pointerInput(Unit) {
        coroutineScope {
            while (true) {
                awaitPointerEventScope {
                    awaitFirstDown(requireUnconsumed = false)
                    val job = this@coroutineScope.launch {
                        currentOnStep()
                        delay(initialDelayMs.milliseconds)
                        while (isActive) {
                            currentOnStep()
                            delay(repeatIntervalMs.milliseconds)
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                }
            }
        }
    }
}
