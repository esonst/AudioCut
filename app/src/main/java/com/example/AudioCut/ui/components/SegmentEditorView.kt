package com.example.audiocut.ui.components

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.data.model.AudioSegment
import com.example.audiocut.ffmpeg.ExportResult
import com.example.audiocut.ui.theme.*
import java.io.File

/** 片段卡片正在编辑的边界 */
private enum class RangeBound { START, END }

/**
 * 剪辑 / 裁剪共用的列表顶部控制卡片：标题与统计、预览 / 试听按钮，
 * 预览成功后内嵌统一的 [AudioPreviewCard]（覆盖 / 保存 / 分享 / 格式转换）。
 */
@Composable
fun EditorListHeaderCard(
    title: String,
    summary: String,
    accent: Color,
    selectedCount: Int,
    isBusy: Boolean,
    progress: Float = 0f,
    isGeneratingPreview: Boolean,
    previewProgress: Float = 0f,
    isPreviewPlaying: Boolean,
    previewResult: ExportResult?,
    previewTitle: String,
    previewPositionMs: Long,
    previewDurationMs: Long,
    onTogglePreview: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onRewindPreview: (Int) -> Unit,
    onClosePreview: () -> Unit,
    onRenamePreviewFile: (String) -> Unit,
    onOverwriteOriginal: () -> Unit,
    onSavePreview: (String, Uri?) -> Unit,
    onSharePreview: () -> Unit,
    onConvertPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Text(text = summary, fontSize = 12.sp, color = TextSecondary)
                }

                OutlinedButton(
                    onClick = onTogglePreview,
                    enabled = !isBusy && selectedCount > 0 && !isGeneratingPreview,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                ) {
                    if (isGeneratingPreview) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = accent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("生成中", fontSize = 12.sp)
                    } else {
                        Icon(if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(15.dp))
                        Text(if (previewResult != null) "试听" else "预览", fontSize = 12.sp)
                    }
                }
            }

            // 点预览生成中：显示线性进度条，完成后消失
            if (isGeneratingPreview) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { previewProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = accent
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "正在生成预览... ${(previewProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = accent,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            if (previewResult != null && previewResult.isSuccess) {
                Spacer(modifier = Modifier.height(12.dp))
                AudioPreviewCard(
                    title = previewTitle,
                    previewFileName = File(previewResult.outputPath).name,
                    color = accent,
                    isPlaying = isPreviewPlaying,
                    currentPositionMs = previewPositionMs,
                    durationMs = previewDurationMs,
                    isBusy = isBusy,
                    progress = progress,
                    onTogglePlayPause = onTogglePreview,
                    onSeekTo = onSeekPreview,
                    onRewind = onRewindPreview,
                    onClose = onClosePreview,
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

/**
 * 剪辑 / 裁剪共用的空列表引导卡片：图标 + 标题 + 说明 + 操作按钮区
 */
@Composable
fun EmptyGuideCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = actions
            )
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
 * 标记片段 / 裁剪卡片共用实现：勾选、试听、拖动 RangeSlider 或点击时间标签
 * （滚轮精确到 50ms）调整起终点、重命名 / 复制 / 删除。主题色与卡片外观可参数化。
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
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    accent: Color = PrimaryLight,
    deleteColor: Color = Color.Red,
    cardContainer: Color = SurfaceVariantLight,
    cardBorder: BorderStroke? = null,
    cardShape: Shape = RoundedCornerShape(12.dp)
) {
    val tagColor = TagColors[segment.colorIndex % TagColors.size]
    var isExpanded by remember { mutableStateOf(false) }
    var editingBound by remember { mutableStateOf<RangeBound?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = cardContainer),
        border = cardBorder,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = segment.isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = accent)
                )
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accent))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = segment.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clickable { isExpanded = !isExpanded }
                )

                IconButton(onClick = onPreview) {
                    Icon(if (isCurrentlyPreviewing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = accent)
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }

            if (isExpanded) {
                // 拖动进度条粗调（吸附到 50ms）
                RangeSlider(
                    value = (segment.startMs.toFloat())..(segment.endMs.toFloat()),
                    onValueChange = { range ->
                        val newStart = (range.start.toLong() / 50L) * 50L
                        val newEnd = (range.endInclusive.toLong() / 50L) * 50L
                        onUpdateRange(newStart, newEnd)
                    },
                    valueRange = 0f..maxDurationMs.toFloat().coerceAtLeast(segment.endMs.toFloat() + 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = Color(0xFFE5E7EB)
                    )
                )

                // 起点 / 终点时间标签：点击弹出滚轮精确设置（不再提供 +/- 微调）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ClickableTimeLabel(
                        text = "起点 ${formatRangePoint(segment.startMs)}",
                        accent = accent,
                        onClick = { editingBound = RangeBound.START }
                    )
                    ClickableTimeLabel(
                        text = "终点 ${formatRangePoint(segment.endMs)}",
                        accent = accent,
                        onClick = { editingBound = RangeBound.END }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onRenameClick) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) }
                    if (onCopy != null) {
                        IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                    }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = deleteColor) }
                }
            }
        }
    }

    // 起 / 终点滚轮设置卡片
    val bound = editingBound
    if (bound != null) {
        val editStart = bound == RangeBound.START
        TimeWheelPickerDialog(
            title = if (editStart) "设置起点" else "设置终点",
            initialMs = if (editStart) segment.startMs else segment.endMs,
            maxMs = maxDurationMs,
            stepMs = 50L,
            showMillis = true,
            otherMs = if (editStart) segment.endMs else segment.startMs,
            isStart = editStart,
            accent = accent,
            onConfirm = { ms ->
                if (editStart) onUpdateRange(ms, segment.endMs)
                else onUpdateRange(segment.startMs, ms)
                editingBound = null
            },
            onDismiss = { editingBound = null }
        )
    }
}
