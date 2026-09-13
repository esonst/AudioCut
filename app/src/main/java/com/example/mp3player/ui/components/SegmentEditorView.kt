package com.example.mp3player.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.mp3player.data.model.AudioItem
import com.example.mp3player.data.model.AudioSegment
import com.example.mp3player.ffmpeg.ExportAudioFormat
import com.example.mp3player.ffmpeg.ExportResult
import com.example.mp3player.ui.theme.*
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
                        delay(initialDelayMs)
                        while (isActive) {
                            currentOnStep()
                            delay(repeatIntervalMs)
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
 * 剪辑页面顶部控制区域：预览、导出、新建片段及合并预览控制器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentEditorHeader(
    segmentsCount: Int,
    selectedSegments: List<AudioSegment>,
    isExporting: Boolean,
    exportProgress: Float,
    exportResult: ExportResult?,
    onExportMerged: (String, ExportAudioFormat) -> Unit,
    modifier: Modifier = Modifier,
    isGeneratingMergedPreview: Boolean = false,
    mergedPreviewResult: ExportResult? = null,
    isMergedPreviewPlaying: Boolean = false,
    mergedPreviewPositionMs: Long = 0L,
    mergedPreviewDurationMs: Long = 0L,
    mergedPreviewSpeed: Float = 1.0f,
    onStartOrToggleMergedPreview: () -> Unit = {},
    onPlayMergedPreview: () -> Unit = {},
    onPauseMergedPreview: () -> Unit = {},
    onSeekMergedPreview: (Long) -> Unit = {},
    onRewindMergedPreview: (Int) -> Unit = {},
    onSetMergedPreviewSpeed: (Float) -> Unit = {},
    onCloseMergedPreview: () -> Unit = {},
    onSaveToLibrary: (String) -> Unit = {},
    onConvertFormat: () -> Unit = {}
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }

                if (mergedPreviewResult != null && mergedPreviewResult.isSuccess) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MergedPreviewPlayerCard(
                        isPlaying = isMergedPreviewPlaying,
                        currentPositionMs = mergedPreviewPositionMs,
                        durationMs = if (mergedPreviewDurationMs > 0) mergedPreviewDurationMs else totalExportDuration,
                        playbackSpeed = mergedPreviewSpeed,
                        segmentCount = selectedCount,
                        onTogglePlayPause = onStartOrToggleMergedPreview,
                        onSeekTo = onSeekMergedPreview,
                        onRewind = onRewindMergedPreview,
                        onSetSpeed = onSetMergedPreviewSpeed,
                        onClose = onCloseMergedPreview,
                        onSaveToLibrary = onSaveToLibrary,
                        isExporting = isExporting,
                        onExportMerged = onExportMerged,
                        onConvertFormat = onConvertFormat,
                        mergedPreviewResult = mergedPreviewResult
                    )
                }
            }
        }
    }

}

/**
 * 预览播放器卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergedPreviewPlayerCard(
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    segmentCount: Int,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveToLibrary: (String) -> Unit = {},
    isExporting: Boolean = false,
    onExportMerged: (String, ExportAudioFormat) -> Unit = { _, _ -> },
    onConvertFormat: () -> Unit = {},
    mergedPreviewResult: com.example.mp3player.ffmpeg.ExportResult? = null
) {
    val context = LocalContext.current
    var showSaveNameDialog by remember { mutableStateOf(false) }
    var saveFileName by remember { mutableStateOf("Clip_${System.currentTimeMillis() / 1000}") }
    var showExportDialog by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf(ExportAudioFormat.M4A) }
    var exportFileName by remember { mutableStateOf("剪辑合并音频_${System.currentTimeMillis() / 1000}") }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SecondaryTeal.copy(alpha = 0.08f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, SecondaryTeal.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "剪辑预览 ($segmentCount 个片段)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SecondaryTeal)
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
            }

            Slider(
                value = currentPositionMs.toFloat(),
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = 0f..max(1f, durationMs.toFloat()),
                colors = SliderDefaults.colors(
                    thumbColor = SecondaryTeal,
                    activeTrackColor = SecondaryTeal,
                    inactiveTrackColor = Color(0xFFE5E7EB)
                ),
                modifier = Modifier.height(24.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = AudioItem.formatDuration(currentPositionMs), fontSize = 10.sp, color = SecondaryTeal)
                Text(text = AudioItem.formatDuration(durationMs), fontSize = 10.sp, color = SecondaryTeal)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onRewind(-5) }) { Icon(Icons.Default.Replay5, null) }
                FilledIconButton(onClick = onTogglePlayPause, colors = IconButtonDefaults.filledIconButtonColors(containerColor = SecondaryTeal)) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                }
                IconButton(onClick = { onRewind(5) }) { Icon(Icons.Default.Forward5, null) }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { showSaveNameDialog = true },
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SecondaryTeal),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("覆盖原文件", fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        exportFileName = "剪辑合并音频_${System.currentTimeMillis() / 1000}"
                        showExportDialog = true
                    },
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("另存为", fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { mergedPreviewResult?.let { shareAudioFile(context, it.outputPath) } },
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("分享", fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { onConvertFormat() },
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("转换格式", fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }

    if (showSaveNameDialog) {
        AlertDialog(
            onDismissRequest = { showSaveNameDialog = false },
            title = { Text("保存音频") },
            text = { OutlinedTextField(value = saveFileName, onValueChange = { saveFileName = it }, label = { Text("文件名") }) },
            confirmButton = { Button(onClick = { onSaveToLibrary(saveFileName.trim()); showSaveNameDialog = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showSaveNameDialog = false }) { Text("取消") } }
        )
    }

    if (showExportDialog) {
        val defaultExportDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)?.absolutePath
            ?: context.filesDir.absolutePath
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("另存为") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = exportFileName, onValueChange = { exportFileName = it }, label = { Text("文件名") }, singleLine = true)
                    Text("选择格式:")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportAudioFormat.values().forEach { fmt ->
                            FilterChip(selected = selectedFormat == fmt, onClick = { selectedFormat = fmt }, label = { Text(fmt.name) })
                        }
                    }
                    Text("保存至: $defaultExportDir", fontSize = 11.sp, color = TextSecondary)
                }
            },
            confirmButton = {
                Button(onClick = { onExportMerged(exportFileName.trim(), selectedFormat); showExportDialog = false }) { Text("开始导出") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } }
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
                            onClick = { onUpdateRange((segment.startMs - 100L).coerceAtLeast(0L), segment.endMs) },
                            modifier = Modifier.size(24.dp).continuousPress { 
                                onUpdateRange((segment.startMs - 100L).coerceAtLeast(0L), segment.endMs)
                            }
                        ) { Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp)) }
                        
                        Text("起点: ${formatPrecise(segment.startMs)}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        
                        IconButton(
                            onClick = { onUpdateRange((segment.startMs + 100L).coerceAtMost(segment.endMs - 100L), segment.endMs) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange((segment.startMs + 100L).coerceAtMost(segment.endMs - 100L), segment.endMs)
                            }
                        ) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                    }

                    // 终点调节
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onUpdateRange(segment.startMs, (segment.endMs - 100L).coerceAtLeast(segment.startMs + 100L)) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange(segment.startMs, (segment.endMs - 100L).coerceAtLeast(segment.startMs + 100L))
                            }
                        ) { Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp)) }
                        
                        Text("终点: ${formatPrecise(segment.endMs)}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        
                        IconButton(
                            onClick = { onUpdateRange(segment.startMs, (segment.endMs + 100L).coerceAtMost(maxDurationMs)) },
                            modifier = Modifier.size(24.dp).continuousPress {
                                onUpdateRange(segment.startMs, (segment.endMs + 100L).coerceAtMost(maxDurationMs))
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

private fun shareAudioFile(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享剪辑音频"))
    } catch (e: Exception) {}
}
