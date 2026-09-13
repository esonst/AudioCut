package com.example.mp3player.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.mp3player.data.model.TranscriptResult
import com.example.mp3player.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.time.Duration.Companion.milliseconds

/**
 * 高性能文稿视图 (v3.1 - 修复选择飘移、拖动期间隐藏悬浮菜单避免干扰手势)
 */
@Composable
fun OptimizedTranscriptView(
    transcriptResult: TranscriptResult,
    activeWordId: Long?,
    wordsInSegmentsIds: Set<Long>,
    onSelectionChanged: (TextRange) -> Unit,
    onWordClick: (Long) -> Unit,
    onCreateSegment: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreateTrimRange: (String) -> Unit = {},
    onSelectionDragChanged: (Boolean) -> Unit = {}
) {
    val annotatedString = remember(transcriptResult) {
        buildAnnotatedString {
            transcriptResult.paragraphs.forEachIndexed { pIdx, paragraph ->
                paragraph.sentences.forEach { sentence ->
                    sentence.words.forEach { word ->
                        append(word.word)
                    }
                }
                if (pIdx < transcriptResult.paragraphs.size - 1) {
                    append("\n\n")
                }
            }
        }
    }

    // 核心修复：内部自主管理 TextFieldValue，避免外部同步导致的飘移
    var textFieldValue by remember(annotatedString) {
        mutableStateOf(TextFieldValue(annotatedString))
    }
    
    // 监听外部通知（如清空选区）
    // 注意：不再通过 externalSelection 参数同步，而是保持内部闭环

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // 预计算所有单词的 TextRange 映射，提升高亮查找性能
    val wordRangeMap = remember(transcriptResult) {
        val map = mutableMapOf<Long, TextRange>()
        var currentIdx = 0
        transcriptResult.paragraphs.forEach { paragraph ->
            paragraph.sentences.forEach { sentence ->
                sentence.words.forEach { word ->
                    val start = currentIdx
                    val end = currentIdx + word.word.length
                    map[word.id] = TextRange(start, end)
                    currentIdx = end
                }
            }
            currentIdx += 2 // 段落换行符 \n\n
        }
        map
    }

    val activeWordRange = remember(activeWordId, wordRangeMap) {
        wordRangeMap[activeWordId]
    }
    
    val segmentRanges = remember(wordsInSegmentsIds, wordRangeMap) {
        wordsInSegmentsIds.mapNotNull { id -> wordRangeMap[id] }
    }

    val clipboardManager = LocalClipboardManager.current
    val selection = textFieldValue.selection

    // 手指是否仍按在文稿上：拖动选择期间隐藏悬浮菜单，抬起后才显示，避免菜单窗口干扰拖动手势导致选区边界乱跳
    var isTouchingText by remember { mutableStateOf(false) }

    // 选区正在变化（长按滑动选词、或拉动手柄调整边界）：期间隐藏悬浮菜单。
    // 不依赖触摸事件路径（手柄触摸区域可能超出容器边界收不到按下事件），以选区状态本身为准。
    var isAdjustingSelection by remember { mutableStateOf(false) }

    // 选区连续变化期间视为正在调整边界，停止变化 300ms 后（即松手）视为结束
    LaunchedEffect(Unit) {
        snapshotFlow { textFieldValue.selection }
            .drop(1)
            .collectLatest {
                isAdjustingSelection = true
                onSelectionDragChanged(true)
                delay(300.milliseconds)
                isAdjustingSelection = false
                onSelectionDragChanged(false)
            }
    }

    // 屏蔽系统默认菜单
    val emptyTextToolbar = remember {
        object : TextToolbar {
            override fun hide() {}
            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {}
            override val status: TextToolbarStatus = TextToolbarStatus.Hidden
        }
    }

    CompositionLocalProvider(LocalTextToolbar provides emptyTextToolbar) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // 观察整个文稿区域（包括选择手柄）的按下/抬起，不消费事件，
                    // 用于拉动边界期间隐藏悬浮菜单，避免菜单窗口拦截手势导致边界乱跳
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        isTouchingText = true
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        isTouchingText = false
                    }
                }
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    onSelectionChanged(it.selection)
                },
                readOnly = true,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 28.sp,
                    color = TextPrimary
                ),
                onTextLayout = { textLayoutResult = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                decorationBox = { innerTextField ->
                    // 容器层级对齐
                    Box(modifier = Modifier.padding(16.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    textLayoutResult?.let { layout ->
                                        segmentRanges.forEach { range ->
                                            val path = layout.getPathForRange(range.start, range.end)
                                            drawPath(path, color = SecondaryTeal.copy(alpha = 0.15f))
                                        }
                                        activeWordRange?.let { range ->
                                            val path = layout.getPathForRange(range.start, range.end)
                                            drawPath(path, color = PrimaryLight.copy(alpha = 0.25f))
                                        }
                                    }
                                }
                                .pointerInput(transcriptResult) {
                                    detectTapGestures { offset ->
                                        // 点击文字时清空选区并关闭菜单
                                        if (!textFieldValue.selection.collapsed) {
                                            textFieldValue = textFieldValue.copy(selection = TextRange.Zero)
                                            onSelectionChanged(TextRange.Zero)
                                        }

                                        textLayoutResult?.let { layout ->
                                            val position = layout.getOffsetForPosition(offset)
                                            
                                            var currentIdx = 0
                                            outer@for ((_, sentences) in transcriptResult.paragraphs) {
                                                for ((_, _, _, _, words) in sentences) {
                                                    for ((_, word1, startMs) in words) {
                                                        val start = currentIdx
                                                        val end = currentIdx + word1.length
                                                        if (position in start until end) {
                                                            onWordClick(startMs)
                                                            break@outer
                                                        }
                                                        currentIdx = end
                                                    }
                                                }
                                                currentIdx += 2
                                            }
                                        }
                                    }
                                }
                        ) {
                            innerTextField()
                        }
                    }
                }
            )

            // 自定义浮动菜单（拖动选择/拉动手柄期间不显示，边界稳定后（松手）才出现）
            if (!selection.collapsed && textLayoutResult != null && !isTouchingText && !isAdjustingSelection) {
                val layoutResult = textLayoutResult!!
                val rect = layoutResult.getCursorRect(selection.end.coerceAtMost(annotatedString.length - 1))
                
                // 考虑 density 进行精确计算
                val paddingPx = with(density) { 16.dp.toPx() }.toInt()
                val menuOffsetY = with(density) { 60.dp.toPx() }.toInt()
                
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(
                        x = (rect.left.toInt() + paddingPx),
                        y = (rect.top.toInt() + paddingPx - scrollState.value - menuOffsetY)
                    ),
                    onDismissRequest = { /* 内部逻辑不自动关闭，除非取消选择 */ }
                ) {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MenuItem(Icons.Default.ContentCopy, "复制") {
                                val text = annotatedString.text.substring(selection.min, selection.max)
                                clipboardManager.setText(AnnotatedString(text))
                                textFieldValue = textFieldValue.copy(selection = TextRange.Zero)
                                onSelectionChanged(TextRange.Zero)
                            }
                            VerticalDivider(modifier = Modifier.height(16.dp).width(1.dp), color = BorderLight)
                            MenuItem(Icons.Default.SelectAll, "全选") {
                                textFieldValue = textFieldValue.copy(selection = TextRange(0, annotatedString.length))
                                onSelectionChanged(textFieldValue.selection)
                            }
                            VerticalDivider(modifier = Modifier.height(16.dp).width(1.dp), color = BorderLight)
                            MenuItem(Icons.Default.Delete, "删除", isBold = true) {
                                onCreateTrimRange(annotatedString.text)
                                textFieldValue = textFieldValue.copy(selection = TextRange.Zero)
                                onSelectionChanged(TextRange.Zero)
                            }
                            VerticalDivider(modifier = Modifier.height(16.dp).width(1.dp), color = BorderLight)
                            MenuItem(Icons.Default.ContentCut, "剪辑", isBold = true) {
                                onCreateSegment(annotatedString.text)
                                textFieldValue = textFieldValue.copy(selection = TextRange.Zero)
                                onSelectionChanged(TextRange.Zero)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isBold: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = PrimaryDark)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = if (isBold) PrimaryLight else PrimaryDark
        )
    }
}

private fun findWordRange(wordId: Long?, transcriptResult: TranscriptResult): TextRange? {
    if (wordId == null) return null
    var currentIdx = 0
    for ((_, sentences) in transcriptResult.paragraphs) {
        for ((_, _, _, _, words) in sentences) {
            for (word in words) {
                val start = currentIdx
                val end = currentIdx + word.word.length
                if (word.id == wordId) {
                    return TextRange(start, end)
                }
                currentIdx = end
            }
        }
        currentIdx += 2
    }
    return null
}
