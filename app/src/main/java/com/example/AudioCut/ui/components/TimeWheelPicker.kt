package com.example.audiocut.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.ui.theme.PrimaryDark
import com.example.audiocut.ui.theme.PrimaryLight
import com.example.audiocut.ui.theme.SurfaceVariantLight
import com.example.audiocut.ui.theme.TextMuted
import com.example.audiocut.ui.theme.TextSecondary
import kotlin.math.abs

/**
 * 通用时间滚轮选择卡片：时 / 分 / 秒，可选毫秒列（毫秒按 [stepMs] 步进，最小 50ms）。
 * 文稿（仅时分秒）、剪辑 / 裁剪（精确到 50ms）等所有需要选择时间点的界面共用。
 *
 * 内部以毫秒为唯一单位；用户滚动只改本卡片内的临时值，点击「确定」才通过 [onConfirm] 写回。
 *
 * @param initialMs 打开时的时间点（毫秒）
 * @param maxMs     >0 时作为「音频总时长」上限校验与提示
 * @param stepMs    毫秒列步进（如 50ms）；[showMillis]=false 时应传 1000
 * @param showMillis 是否显示毫秒列
 * @param otherMs   另一边界时间点，配合 [isStart] 做先后顺序校验
 * @param isStart   true=正在编辑起点（必须早于另一边界）；false=终点；null=不校验先后
 * @param onReset   非空时显示重置按钮（如文稿页「重置为整个音频」）
 */
@Composable
fun TimeWheelPickerDialog(
    title: String,
    initialMs: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxMs: Long = 0L,
    stepMs: Long = 50L,
    showMillis: Boolean = true,
    otherMs: Long? = null,
    isStart: Boolean? = null,
    accent: Color = PrimaryLight,
    resetText: String = "重置为整个音频",
    onReset: (() -> Unit)? = null
) {
    val step = stepMs.coerceAtLeast(1L)
    var currentMs by remember { mutableStateOf((initialMs.coerceAtLeast(0L) / step) * step) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val hour = (currentMs / 3_600_000L).toInt()
    val minute = ((currentMs % 3_600_000L) / 60_000L).toInt()
    val second = ((currentMs % 60_000L) / 1_000L).toInt()
    val msCount = (1_000L / step).toInt()
    val msIndex = ((currentMs % 1_000L) / step).toInt().coerceIn(0, msCount - 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatTimePoint(currentMs, showMillis),
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
                        selectedIndex = hour,
                        itemCount = 1000,
                        label = "时",
                        accent = accent,
                        onIndexChange = { h ->
                            currentMs = h * 3_600_000L + currentMs % 3_600_000L
                        }
                    )
                    WheelColon(accent = accent)
                    TimeWheelColumn(
                        selectedIndex = minute,
                        itemCount = 60,
                        label = "分",
                        accent = accent,
                        onIndexChange = { m ->
                            currentMs = (currentMs / 3_600_000L) * 3_600_000L +
                                m * 60_000L + currentMs % 60_000L
                        }
                    )
                    WheelColon(accent = accent)
                    TimeWheelColumn(
                        selectedIndex = second,
                        itemCount = 60,
                        label = "秒",
                        accent = accent,
                        onIndexChange = { s ->
                            currentMs = (currentMs / 60_000L) * 60_000L + s * 1_000L + currentMs % 1_000L
                        }
                    )
                    if (showMillis) {
                        WheelColon(text = ".", accent = accent)
                        TimeWheelColumn(
                            selectedIndex = msIndex,
                            itemCount = msCount,
                            label = "毫秒",
                            accent = accent,
                            columnWidth = 66.dp,
                            indexLabel = { idx -> "%03d".format((idx * step).toInt()) },
                            onIndexChange = { idx ->
                                currentMs = (currentMs / 1_000L) * 1_000L + idx * step
                            }
                        )
                    }
                }
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(errorText!!, fontSize = 12.sp, color = Color(0xFFDC2626))
                }
                if (maxMs > 0L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "音频总时长 ${formatTimePoint(maxMs, showMillis)}",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
                if (onReset != null) {
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(resetText, fontSize = 12.sp, color = accent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    isStart == true && otherMs != null && currentMs >= otherMs ->
                        errorText = "开始时间必须早于结束时间"
                    isStart == false && otherMs != null && currentMs <= otherMs ->
                        errorText = "结束时间必须晚于开始时间"
                    maxMs > 0L && currentMs > maxMs ->
                        errorText = "时间不能超过音频总时长"
                    else -> onConfirm(currentMs)
                }
            }) { Text("确定", color = accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/**
 * 可点击的时间标签（浅色胶囊），点击弹出 [TimeWheelPickerDialog]。
 */
@Composable
fun ClickableTimeLabel(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = PrimaryLight
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) accent.copy(alpha = 0.10f) else SurfaceVariantLight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) accent else TextMuted,
            maxLines = 1
        )
    }
}

/** 时:分:秒（可选 .毫秒）格式化，毫秒为单位 */
fun formatTimePoint(ms: Long, withMillis: Boolean): String {
    val h = ms / 3_600_000L
    val m = (ms % 3_600_000L) / 60_000L
    val s = (ms % 60_000L) / 1_000L
    return if (withMillis) {
        "%02d:%02d:%02d.%03d".format(h, m, s, ms % 1_000L)
    } else {
        "%02d:%02d:%02d".format(h, m, s)
    }
}

/**
 * 片段起 / 终点标签用的紧凑格式：mm:ss.cs（百分秒，50ms 步进下尾数只会是 0/5）；
 * 超过 1 小时带小时段 h:mm:ss.cs。
 */
fun formatRangePoint(ms: Long): String {
    val totalCentis = ms / 10L
    val cs = totalCentis % 100L
    val totalSec = totalCentis / 100L
    val s = totalSec % 60L
    val m = (totalSec % 3_600L) / 60L
    val h = totalSec / 3_600L
    return if (h > 0L) {
        "%d:%02d:%02d.%02d".format(h, m, s, cs)
    } else {
        "%02d:%02d.%02d".format(m, s, cs)
    }
}

/** 列间分隔符（默认冒号），高度与滚轮一致并对齐中央选中行 */
@Composable
private fun WheelColon(
    accent: Color,
    text: String = ":"
) {
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

/**
 * 单个时间滚轮列：上下滚动调整，松手由官方 SnapFlingBehavior 吸附到中央项。
 */
@Composable
private fun TimeWheelColumn(
    selectedIndex: Int,
    itemCount: Int,
    label: String,
    accent: Color,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columnWidth: Dp = 58.dp,
    indexLabel: (Int) -> String = { it.toString().padStart(2, '0') }
) {
    val itemHeight = 40.dp
    val halfItems = 1
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(listState)

    // 打开时把初始值停在中央（contentPadding 上下各留 halfItems 项，scrollToItem 后该项恰在中央槽）
    LaunchedEffect(Unit) {
        listState.scrollToItem(selectedIndex.coerceIn(0, itemCount - 1))
    }

    // 选中项 = 视口中央最近的一项（item.offset 为内容坐标，需加 viewportStartOffset 换算）
    val centeredIndex by remember {
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
    LaunchedEffect(centeredIndex) {
        if (centeredIndex in 0 until itemCount) onIndexChange(centeredIndex)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(columnWidth)
                .height(itemHeight * (halfItems * 2 + 1))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.12f))
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = flingBehavior,
                contentPadding = PaddingValues(vertical = itemHeight * halfItems)
            ) {
                items(itemCount) { index ->
                    val selected = index == centeredIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = indexLabel(index),
                            fontSize = if (selected) 19.sp else 15.sp,
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
