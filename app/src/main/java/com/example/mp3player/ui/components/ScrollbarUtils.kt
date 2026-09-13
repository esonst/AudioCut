package com.example.mp3player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mp3player.ui.theme.PrimaryLight

/**
 * 为 LazyColumn 绘制简单的垂直滚动条
 */
fun Modifier.drawVerticalScrollbar(
    state: LazyListState,
    width: Dp = 6.dp
): Modifier = composed {
    val targetAlpha = if (state.isScrollInProgress) 1f else 0f
    val duration = if (state.isScrollInProgress) 150 else 500
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration),
        label = "ScrollbarAlpha"
    )

    drawWithContent {
        drawContent()
        val firstVisibleElementIndex = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
        val needDrawScrollbar = state.isScrollInProgress || alpha > 0.0f

        if (needDrawScrollbar && firstVisibleElementIndex != null) {
            val elementCount = state.layoutInfo.totalItemsCount
            val visibleElementsCount = state.layoutInfo.visibleItemsInfo.size
            
            if (elementCount > visibleElementsCount) {
                val scrollbarHeight = (size.height * visibleElementsCount / elementCount).coerceAtLeast(30f)
                val scrollbarOffsetY = (firstVisibleElementIndex.toFloat() / elementCount * size.height)

                drawRect(
                    color = PrimaryLight.copy(alpha = alpha),
                    topLeft = Offset(size.width - width.toPx(), scrollbarOffsetY),
                    size = Size(width.toPx(), scrollbarHeight)
                )
            }
        }
    }
}
