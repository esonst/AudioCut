package com.example.mp3player.ui.components

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
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
import com.example.mp3player.data.model.formatTranscriptTimestamp
import com.example.mp3player.data.model.transcriptTimestampLineLength
import com.example.mp3player.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.time.Duration.Companion.milliseconds

/**
 * 高性能文稿视图 (v4.0)
 * - 排版优化后每段开头显示 [hh:mm:ss] 时间戳行（灰色小字样式，复制时自动剔除）
 * - 播放时自动滚动，保持正在播放的文字始终在可视区域内
 * - 统一手势：出现选择滑块时单击其它位置仅取消选择（不跳转播放）；
 *   没有选择滑块时单击文字才跳转播放进度
 * - 长按选词 / 拖动手柄调整选区期间隐藏悬浮菜单，松手稳定后才显示，
 *   避免菜单窗口拦截手势导致选区边界乱跳
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
    onSelectionDragChanged: (Boolean) -> Unit = {},
    isPlaying: Boolean = false,
    isPageVisible: Boolean = true
) {
    val showTimestamps = transcriptResult.isLayoutOptimized

    val annotatedString = remember(transcriptResult) {
        buildAnnotatedString {
            transcriptResult.paragraphs.forEachIndexed { pIdx, paragraph ->
                if (showTimestamps) {
                    withStyle(style = SpanStyle(color = TextSecondary)) {
                        append(formatTranscriptTimestamp(paragraph.startMs))
                    }
                    append("\n")
                }
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

    // 内部自主管理 TextFieldValue，避免外部同步导致的飘移
    var textFieldValue by remember(annotatedString) {
        mutableStateOf(TextFieldValue(annotatedString))
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current

    // 预计算所有单词的 TextRange 映射（已计入时间戳行偏移），提升高亮/点击查找性能
    val wordRangeMap = remember(transcriptResult) {
        val map = mutableMapOf<Long, TextRange>()
        var currentIdx = 0
        transcriptResult.paragraphs.forEachIndexed { pIdx, paragraph ->
            if (showTimestamps) {
                currentIdx += transcriptTimestampLineLength(paragraph.startMs)
            }
            paragraph.sentences.forEach { sentence ->
                sentence.words.forEach { word ->
                    val start = currentIdx
                    val end = currentIdx + word.word.length
                    map[word.id] = TextRange(start, end)
                    currentIdx = end
                }
            }
            if (pIdx < transcriptResult.paragraphs.size - 1) {
                currentIdx += 2 // 段落换行符 \n\n
            }
        }
        map
    }

    // 词 id → 起始时间（点击跳转播放用）
    val wordIdToStartMs = remember(transcriptResult) {
        transcriptResult.words.associate { it.id to it.startMs }
    }

    // 时间戳行在文本中的区间（复制时剔除，避免拷贝出 [hh:mm:ss]）
    val timestampRanges = remember(transcriptResult) {
        val ranges = mutableListOf<TextRange>()
        if (showTimestamps) {
            var currentIdx = 0
            transcriptResult.paragraphs.forEachIndexed { pIdx, paragraph ->
                val tsLen = transcriptTimestampLineLength(paragraph.startMs)
                ranges.add(TextRange(currentIdx, currentIdx + tsLen - 1))
                currentIdx += tsLen
                paragraph.sentences.forEach { sentence ->
                    sentence.words.forEach { word -> currentIdx += word.word.length }
                }
                if (pIdx < transcriptResult.paragraphs.size - 1) {
                    currentIdx += 2
                }
            }
        }
        ranges
    }

    val activeWordRange = remember(activeWordId, wordRangeMap) {
        wordRangeMap[activeWordId]
    }

    val segmentRanges = remember(wordsInSegmentsIds, wordRangeMap) {
        wordsInSegmentsIds.mapNotNull { id -> wordRangeMap[id] }
    }

    val clipboardManager = LocalClipboardManager.current
    val selection = textFieldValue.selection

    // 手指是否仍按在文稿上：长按/拖动期间隐藏悬浮菜单，抬起后才显示，
    // 避免菜单窗口干扰手势导致选区边界乱跳
    var isTouchingText by remember { mutableStateOf(false) }

    // 根 View 级触摸监听：捕捉主窗口内任意位置的按下（包括选择滑块手柄区域，
    // 手柄触摸可能不经过下方 Compose 指针路径），只要还有手指按着就隐藏悬浮菜单，
    // 全部抬起后才允许重新显示。返回 false 不消费事件，不影响 Compose 自身手势。
    var anyPointerDown by remember { mutableStateOf(false) }
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        val listener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> anyPointerDown = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> anyPointerDown = false
            }
            false
        }
        rootView.setOnTouchListener(listener)
        onDispose {
            rootView.setOnTouchListener(null)
        }
    }

    // 页面不可见（Pager 滑动离开文稿页）时立即清空选择：悬浮菜单是独立窗口，
    // 不会随页面不可见自动消失，必须在离开时主动取消选择，避免卡片残留在其它界面
    LaunchedEffect(isPageVisible) {
        if (!isPageVisible) {
            textFieldValue = textFieldValue.copy(selection = TextRange.Zero)
            onSelectionChanged(TextRange.Zero)
        }
    }

    // 选区正在变化（长按滑动选词、拉动手柄调整边界）：期间隐藏悬浮菜单。
    // 不依赖触摸事件路径（手柄触摸区域可能超出容器边界收不到按下事件），以选区状态本身为准。
    var isAdjustingSelection by remember { mutableStateOf(false) }

    // 选区连续变化期间视为正在调整边界，停止变化 450ms 后（即松手）视为结束
    LaunchedEffect(Unit) {
        snapshotFlow { textFieldValue.selection }
            .drop(1)
            .collectLatest {
                isAdjustingSelection = true
                onSelectionDragChanged(true)
                delay(350.milliseconds)
                isAdjustingSelection = false
                onSelectionDragChanged(false)
            }
    }

    // 播放时自动滚动：正在播放的文字保持在屏幕约 3/4 高度处（视口 75% 锚点），
    // 文字滚过锚点线或滚出顶部时平滑滚动回锚点；文稿已滚到底或顶时停在边界
    LaunchedEffect(activeWordId, isPlaying, textLayoutResult, annotatedString) {
        if (!isPlaying) return@LaunchedEffect
        val layout = textLayoutResult ?: return@LaunchedEffect
        val range = activeWordRange ?: return@LaunchedEffect
        if (scrollState.maxValue <= 0) return@LaunchedEffect

        val viewportHeight = (layout.size.height - scrollState.maxValue).coerceAtLeast(1).toFloat()
        val current = scrollState.value.toFloat()
        val anchor = viewportHeight * 0.75f // 3/4 锚点
        val line = layout.getLineForOffset(range.start)
        val lineTop = layout.getLineTop(line)
        val lineBottom = layout.getLineBottom(line)
        // 行滚出视口顶部，或行底部滚过 3/4 锚点线时，滚动使行顶部对齐 3/4 锚点；
        // 超出滚动边界（已到底/顶）时停在边界
        val target = if (lineTop < current || lineBottom > current + anchor) {
            (lineTop - anchor).coerceIn(0f, scrollState.maxValue.toFloat())
        } else {
            current
        }
        if (target != current) {
            scrollState.animateScrollTo(target.toInt())
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

    val paddingPx = with(density) { 16.dp.toPx() }

    CompositionLocalProvider(LocalTextToolbar provides emptyTextToolbar) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .pointerInput(transcriptResult) {
                    // 统一手势处理：触摸跟踪 + 单击识别（有选区→取消选择；无选区→点击文字跳转播放）
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isTouchingText = true
                        val downPos = down.position
                        val downTime = down.uptimeMillis
                        var isTap = true
                        var lastEventTime = downTime
                        do {
                            val event = awaitPointerEvent()
                            lastEventTime = event.changes.firstOrNull()?.uptimeMillis ?: lastEventTime
                            if (event.changes.any {
                                    it.pressed && (it.position - downPos).getDistance() > viewConfiguration.touchSlop
                                }) {
                                isTap = false
                            }
                        } while (event.changes.any { it.pressed })
                        isTouchingText = false

                        // 拖动/滑动（滚动、拉选）不算单击
                        if (!isTap) return@awaitEachGesture
                        // 长按选词后抬起不算单击（避免刚选出的选区立刻被清掉）
                        if (lastEventTime - downTime > viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture

                        // 出现选择滑块：单击取消选择，不跳转播放进度
                        if (!textFieldValue.selection.collapsed) {
                            textFieldValue = textFieldValue.copy(selection = TextRange.Zero)
                            onSelectionChanged(TextRange.Zero)
                            return@awaitEachGesture
                        }

                        // 无选区：单击文字跳转播放进度（点击在边距/空白处不处理）
                        val layout = textLayoutResult ?: return@awaitEachGesture
                        val contentOffset = Offset(
                            x = downPos.x - paddingPx,
                            y = downPos.y - paddingPx + scrollState.value.toFloat()
                        )
                        if (contentOffset.x < 0f || contentOffset.y < 0f ||
                            contentOffset.x > layout.size.width.toFloat() ||
                            contentOffset.y > layout.size.height.toFloat()
                        ) return@awaitEachGesture

                        val position = layout.getOffsetForPosition(contentOffset)
                        val entry = wordRangeMap.entries.firstOrNull { (_, range) ->
                            position >= range.start && position < range.end
                        }
                        if (entry != null) {
                            wordIdToStartMs[entry.key]?.let { onWordClick(it) }
                        }
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
                        ) {
                            innerTextField()
                        }
                    }
                }
            )

            // 自定义浮动菜单（拖动选择/拉动手柄期间不显示，边界稳定后（松手）才出现）
            if (isPageVisible && !selection.collapsed && textLayoutResult != null && !isTouchingText && !anyPointerDown && !isAdjustingSelection) {
                val layoutResult = textLayoutResult!!
                val rect = layoutResult.getCursorRect(selection.end.coerceAtMost(annotatedString.length - 1))

                val paddingPxInt = paddingPx.toInt()
                val menuOffsetY = with(density) { 60.dp.toPx() }.toInt()

                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(
                        x = rect.left.toInt() + paddingPxInt,
                        y = rect.top.toInt() + paddingPxInt - scrollState.value - menuOffsetY
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
                                val cleanText = copyTextExcludingTimestamps(
                                    annotatedString.text, selection.min, selection.max, timestampRanges
                                )
                                clipboardManager.setText(AnnotatedString(cleanText))
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

/**
 * 从选中区间剔除时间戳行内容（时间戳 + 行尾换行），其余原文保留
 */
private fun copyTextExcludingTimestamps(
    fullText: String,
    selMin: Int,
    selMax: Int,
    timestampRanges: List<TextRange>
): String {
    val sb = StringBuilder()
    var cursor = selMin
    for (range in timestampRanges) {
        val s = maxOf(range.start, selMin)
        val e = minOf(range.end, selMax)
        if (s >= e) continue
        sb.append(fullText, cursor, s)
        cursor = e
    }
    sb.append(fullText, cursor, selMax)
    return sb.toString()
}
