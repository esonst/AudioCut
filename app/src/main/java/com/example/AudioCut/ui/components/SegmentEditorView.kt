package com.example.audiocut.ui.components

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.data.model.AudioSegment
import com.example.audiocut.ffmpeg.ExportResult
import com.example.audiocut.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 长按连续步进手势 Modifier
 */
fun Modifier.continuousPress(
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
                    val job = launch {
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

/**
 * 剪辑页面顶部控制区域：预览触发 + 统一预览卡片（覆盖/保存/分享）
 */
@Composable
fun SegmentEditorHeader(
    segmentsCount: Int,
    selectedSegments: List<AudioSegment>,
    isExporting: Boolean,
    exportProgress: Float,
    isGeneratingMergedPreview: Boolean,
    mergedPreviewResult: ExportResult?,
    isMergedPreviewPlaying: Boolean,
    mergedPreviewPositionMs: Long,
    mergedPreviewDurationMs: Long,
    onStartOrToggleMergedPreview: () -> Unit,
    onSeekMergedPreview: (Long) -> Unit,
    onRewindMergedPreview: (Int) -> Unit,
    onCloseMergedPreview: () -> Unit,
    onRenamePreviewFile: (String) -> Unit,
    onOverwriteOriginal: () -> Unit,
    onSavePreview: (String, Uri?) -> Unit,
    onSharePreview: () -> Unit,
    onConvertPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedCount = selectedSegments.size
    val totalExportDuration = selectedSegments.sumOf { it.durationMs }

    Column(modifier = modifier.fillMaxWidth()) {
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
                        Text(text = "标记片段列表 ($segmentsCount)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                        Text(text = "已选 $selectedCount 段 · 预计时长: ${AudioItem.formatDuration(totalExportDuration)}", fontSize = 12.sp, color = TextSecondary)
                    }

                    OutlinedButton(
                        onClick = onStartOrToggleMergedPreview,
                        enabled = !isExporting && selectedCount > 0 && !isGeneratingMergedPreview,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryTeal)
                    ) {
                        if (isGeneratingMergedPreview) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SecondaryTeal)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("生成中", fontSize = 12.sp)
                        } else {
                            Icon(if (isMergedPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(15.dp))
                            Text(if (mergedPreviewResult != null) "试听" else "预览", fontSize = 12.sp)
                        }
                    }
                }

                if (mergedPreviewResult != null && mergedPreviewResult.isSuccess) {
                    Spacer(modifier = Modifier.height(12.dp))
                    AudioPreviewCard(
                        title = "剪辑预览 ($selectedCount 个片段)",
                        previewFileName = File(mergedPreviewResult.outputPath).name,
                        color = SecondaryTeal,
                        isPlaying = isMergedPreviewPlaying,
                        currentPositionMs = mergedPreviewPositionMs,
                        durationMs = if (mergedPreviewDurationMs > 0) mergedPreviewDurationMs else totalExportDuration,
                        isBusy = isExporting,
                        progress = exportProgress,
                        onTogglePlayPause = onStartOrToggleMergedPreview,
                        onSeekTo = onSeekMergedPreview,
                        onRewind = onRewindMergedPreview,
                        onClose = onCloseMergedPreview,
                        onRenameFile = onRenamePreviewFile,
                        onOverwrite = onOverwriteOriginal,
                        onSave = onSavePreview,
                        onShare = onSharePreview,
                        onConvert = onConvertPreview
                    )
                }
            }
        }
    }
}

/**
 * 预览卡片（【剪辑】与【裁剪】共用同一个实现，仅主题色不同）
 * 顶部展示预览文件名标签 + 铅笔重命名；底部提供 覆盖 / 保存 / 分享 三个操作
 */
@Composable
fun AudioPreviewCard(
    title: String,
    previewFileName: String,
    color: Color,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    isBusy: Boolean,
    progress: Float = 0f,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind: (Int) -> Unit,
    onClose: () -> Unit,
    onRenameFile: (String) -> Unit,
    onOverwrite: () -> Unit,
    onSave: (String, Uri?) -> Unit,
    onShare: () -> Unit,
    onConvert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fileExt = previewFileName.substringAfterLast('.', "").ifEmpty { "m4a" }
    val fileBaseName = previewFileName.substringBeforeLast('.', previewFileName)

    val defaultSaveDir = remember {
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.absolutePath
            ?: context.filesDir.absolutePath
    }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(fileBaseName) }
    var showOverwriteConfirm by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveLocationUri by remember { mutableStateOf<Uri?>(null) }

    // 预览文件名变化（如重命名后）同步刷新重命名输入框默认值
    LaunchedEffect(previewFileName) {
        renameText = previewFileName.substringBeforeLast('.', previewFileName)
    }

    // 系统文件选择器：让用户设置保存位置（SAF）
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> saveLocationUri = uri }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                }
            }

            // 预览文件名标签 + 铅笔重命名
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = color.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)) {
                        Text(
                            text = previewFileName,
                            fontSize = 12.sp,
                            color = color,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = {
                                renameText = previewFileName.substringBeforeLast('.', previewFileName)
                                showRenameDialog = true
                            },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名预览文件", tint = color, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Slider(
                value = currentPositionMs.toFloat(),
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = 0f..maxOf(1f, durationMs.toFloat()),
                colors = SliderDefaults.colors(
                    thumbColor = color,
                    activeTrackColor = color,
                    inactiveTrackColor = Color(0xFFE5E7EB)
                ),
                modifier = Modifier.height(24.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = AudioItem.formatDuration(currentPositionMs), fontSize = 10.sp, color = color)
                Text(text = AudioItem.formatDuration(durationMs), fontSize = 10.sp, color = color)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onRewind(-5) }) { Icon(Icons.Default.Replay5, null) }
                FilledIconButton(onClick = onTogglePlayPause, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                }
                IconButton(onClick = { onRewind(5) }) { Icon(Icons.Default.Forward5, null) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 底部操作按钮：覆盖 / 保存 / 分享 / 格式转换
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { showOverwriteConfirm = true },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("覆盖", fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        saveLocationUri = null
                        showSaveDialog = true
                    },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.FileCopy, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存", fontSize = 12.sp, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onShare,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("分享", fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onConvert,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("格式转换", fontSize = 12.sp, maxLines = 1)
                }
            }

            if (isBusy) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = color
                )
            }
        }
    }

    // 重命名预览文件对话框
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名预览文件") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    suffix = { Text(".$fileExt", fontSize = 13.sp, color = TextSecondary) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameFile(renameText.trim())
                        showRenameDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

    // 覆盖确认对话框
    if (showOverwriteConfirm) {
        AlertDialog(
            onDismissRequest = { showOverwriteConfirm = false },
            title = { Text("覆盖当前文件") },
            text = { Text("确认用当前预览结果覆盖原文件？此操作不可撤销，并将清除该音频的文稿、剪辑片段与裁剪记录。", fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        onOverwrite()
                        showOverwriteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = color)
                ) { Text("确认覆盖") }
            },
            dismissButton = { TextButton(onClick = { showOverwriteConfirm = false }) { Text("取消") } }
        )
    }

    // 保存对话框：仅设置保存位置，文件名沿用预览卡片上重命名后的名字
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存预览音频") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("文件名: \n$previewFileName", fontSize = 13.sp, color = PrimaryDark)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("保存位置", fontSize = 13.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { saveFileLauncher.launch(previewFileName) }) {
                            Text("选择位置", color = color)
                        }
                    }
                    Text(
                        text = saveLocationUri?.let { "已选择: $it" } ?: "默认保存至: $defaultSaveDir",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = saveLocationUri
                        saveLocationUri = null
                        onSave(previewFileName.substringBeforeLast('.', previewFileName), uri)
                        showSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = color)
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } }
        )
    }
}

/**
 * 标记片段卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentItemCard(
    segment: AudioSegment,
    maxDurationMs: Long,
    isCurrentlyPreviewing: Boolean,
    onToggleSelect: () -> Unit,
    onUpdateRange: (Long, Long) -> Unit,
    onRenameClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    currentPositionMs: Long = 0L,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    val tagColor = TagColors[segment.colorIndex % TagColors.size]
    var isExpanded by remember { mutableStateOf(false) }
    
    fun formatPrecise(ms: Long): String {
        val totalSeconds = ms / 1000.0
        val min = (totalSeconds / 60).toInt()
        val sec = totalSeconds % 60
        return "%02d:%04.1f".format(min, sec)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = segment.isSelected, onCheckedChange = { onToggleSelect() })
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(tagColor))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = segment.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable { isExpanded = !isExpanded })
                
                IconButton(onClick = onPreview) {
                    Icon(if (isCurrentlyPreviewing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = PrimaryLight)
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }

            if (isExpanded) {
                RangeSlider(
                    value = (segment.startMs.toFloat())..(segment.endMs.toFloat()),
                    onValueChange = { range -> 
                        val newStart = (range.start.toLong() / 100L) * 100L
                        val newEnd = (range.endInclusive.toLong() / 100L) * 100L
                        onUpdateRange(newStart, newEnd) 
                    },
                    valueRange = 0f..maxDurationMs.toFloat().coerceAtLeast(segment.endMs.toFloat() + 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = tagColor,
                        activeTrackColor = tagColor,
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
                            onClick = { onUpdateRange((segment.startMs - 50L).coerceAtLeast(0L), segment.endMs) },
                            modifier = Modifier.size(24.dp).continuousPress { 
                                onUpdateRange((segment.startMs - 50L).coerceAtLeast(0L), segment.endMs)
                            }
                        ) { Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp)) }
                        
                        Text("起点: ${formatPrecise(segment.startMs)}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        
                        IconButton(
                            onClick = { onUpdateRange((segment.startMs + 50L).coerceAtMost(segment.endMs - 50L), segment.endMs) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange((segment.startMs + 50L).coerceAtMost(segment.endMs - 50L), segment.endMs)
                            }
                        ) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                    }

                    // 终点调节
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onUpdateRange(segment.startMs, (segment.endMs - 50L).coerceAtLeast(segment.startMs + 50L)) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange(segment.startMs, (segment.endMs - 50L).coerceAtLeast(segment.startMs + 50L))
                            }
                        ) { Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp)) }
                        
                        Text("终点: ${formatPrecise(segment.endMs)}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        
                        IconButton(
                            onClick = { onUpdateRange(segment.startMs, (segment.endMs + 50L).coerceAtMost(maxDurationMs)) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange(segment.startMs, (segment.endMs + 50L).coerceAtMost(maxDurationMs))
                            }
                        ) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onRenameClick) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = Color.Red) }
                }
            }
        }
    }
}
