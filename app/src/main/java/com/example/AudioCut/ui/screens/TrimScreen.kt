package com.example.audiocut.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.navigation.AppScreen
import com.example.audiocut.ui.components.EditorListHeaderCard
import com.example.audiocut.ui.components.EmptyGuideCard
import com.example.audiocut.ui.components.SegmentItemCard
import com.example.audiocut.ui.theme.*
import com.example.audiocut.viewmodel.MainViewModel
import com.example.audiocut.viewmodel.TrimViewModel

/**
 * 裁剪页面（风格与操作与【剪辑】保持一致，主题色为淡红色，卡片 / 头部 / 时间滚轮均为共用组件）
 * 支持从【文稿】长按文本【删除】生成裁剪卡片，预览时将原音频对应部分剪掉并拼接剩余内容
 * 提供 覆盖原文件 / 另存为 / 分享 三种导出方式
 */
@Composable
fun TrimScreen(mainViewModel: MainViewModel, trimViewModel: TrimViewModel, modifier: Modifier = Modifier) {
    val currentAudio by mainViewModel.currentPlayingAudio.collectAsState()
    val durationMs by mainViewModel.durationMs.collectAsState()
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
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
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
                        EmptyGuideCard(
                            icon = Icons.Default.DeleteSweep,
                            accent = TrimRed,
                            title = "暂无裁剪卡片",
                            description = "您可以前往【文稿】页面长按滑动选中文本后点击【删除】一键创建裁剪卡片，在此精确调整后预览剪掉所选部分后的效果"
                        ) {
                            Button(
                                onClick = { mainViewModel.navigateTo(AppScreen.TRANSCRIPT) },
                                colors = ButtonDefaults.buttonColors(containerColor = TrimRed),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("前往文稿选词标记", fontSize = 13.sp)
                            }
                            IconButton(onClick = { trimViewModel.createManualTrimAtCurrentPos() }) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "新建裁剪卡片", tint = TrimRed)
                            }
                        }
                    }
                } else {
                    item {
                        val selectedTrims = trimRanges.filter { it.isSelected }
                        val cutDuration = selectedTrims.sumOf { it.durationMs }
                        val remainDuration = (totalDuration - cutDuration).coerceAtLeast(0L)
                        EditorListHeaderCard(
                            title = "裁剪卡片列表 (${trimRanges.size})",
                            summary = "已选 ${selectedTrims.size} 段 · 将剪掉 ${AudioItem.formatDuration(cutDuration)} · 剩余 ${AudioItem.formatDuration(remainDuration)}",
                            accent = TrimRed,
                            selectedCount = selectedTrims.size,
                            isBusy = isTrimExporting,
                            progress = trimExportProgress,
                            isGeneratingPreview = isGeneratingTrimPreview,
                            isPreviewPlaying = isPreviewPlaying,
                            previewResult = trimPreviewResult,
                            previewTitle = "裁剪预览 (剪掉 ${selectedTrims.size} 段)",
                            previewPositionMs = previewPositionMs,
                            previewDurationMs = if (previewDurationMs > 0) previewDurationMs else remainDuration,
                            onTogglePreview = { trimViewModel.startOrToggleTrimPreview() },
                            onSeekPreview = { trimViewModel.seekMergedPreview(it) },
                            onRewindPreview = { trimViewModel.rewindMergedPreview(it) },
                            onClosePreview = { trimViewModel.closeTrimPreview() },
                            onRenamePreviewFile = { trimViewModel.renamePreviewFile(it) },
                            onOverwriteOriginal = { trimViewModel.overwriteOriginalWithTrim() },
                            onSavePreview = { name, uri -> trimViewModel.saveTrimPreviewAs(name, uri) },
                            onSharePreview = { trimViewModel.exportTrimAndShare() },
                            onConvertPreview = { mainViewModel.navigateToConvertFormat(trimPreviewResult?.outputPath) }
                        )
                    }

                    itemsIndexed(trimRanges, key = { _, trim -> trim.id }) { _, trim ->
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var renameText by remember { mutableStateOf(trim.title) }

                        SegmentItemCard(
                            segment = trim,
                            maxDurationMs = totalDuration,
                            isCurrentlyPreviewing = previewingTrimId == trim.id && isPlaying,
                            onToggleSelect = { trimViewModel.toggleTrimSelected(trim.id) },
                            onUpdateRange = { start, end -> trimViewModel.updateTrimRange(trim.id, start, end) },
                            onPreview = { trimViewModel.previewTrimRange(trim) },
                            onRenameClick = {
                                renameText = trim.title
                                showRenameDialog = true
                            },
                            onDelete = { trimViewModel.deleteTrimRange(trim.id) },
                            accent = TrimRed,
                            deleteColor = TrimRedDark,
                            cardContainer = TrimRed.copy(alpha = 0.08f),
                            cardBorder = BorderStroke(1.dp, TrimRed.copy(alpha = 0.3f)),
                            cardShape = RoundedCornerShape(16.dp)
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
