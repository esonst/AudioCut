package com.example.audiocut.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.ui.theme.*

/**
 * 浮动播放控制栏（置于音频库、文稿等界面底部，支持快速控制完整音频播放、上一曲、下一曲、快退5s、快进5s）
 */
@Composable
fun FloatingPlayerBar(
    currentAudio: AudioItem?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onFastForward5s: () -> Unit,
    onRewind5s: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onClickBar: () -> Unit,
    modifier: Modifier = Modifier,
    onSeekTo: (Long) -> Unit = {}
) {
    AnimatedVisibility(
        visible = currentAudio != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        if (currentAudio == null) return@AnimatedVisibility

        // 取两者最大值：任一来源异常（为 0 或极小值）时用另一个兜底，避免跳转/进度显示被压到 0
        val totalDuration = maxOf(durationMs, currentAudio.durationMs)
        val progress = if (totalDuration > 0) {
            (currentPositionMs.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp), spotColor = PrimaryLight.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 顶部可拖动/点按的播放进度条，支持手动调整进度
                var dragFraction by remember { mutableStateOf<Float?>(null) }
                val shownFraction = dragFraction ?: progress

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .pointerInput(totalDuration) {
                            detectTapGestures { offset ->
                                if (totalDuration > 0) {
                                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    onSeekTo((fraction * totalDuration).toLong())
                                }
                            }
                        }
                        .pointerInput(totalDuration) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    dragFraction?.let { fraction ->
                                        if (totalDuration > 0) {
                                            onSeekTo((fraction * totalDuration).toLong())
                                        }
                                    }
                                    dragFraction = null
                                },
                                onDragCancel = { dragFraction = null }
                            ) { change, _ ->
                                change.consume()
                                if (totalDuration > 0) {
                                    dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    LinearProgressIndicator(
                        progress = { shownFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = PrimaryLight,
                        trackColor = SurfaceVariantLight
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 左侧：点击跳转播放页面
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onClickBar)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrimaryLight.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = PrimaryLight,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentAudio.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${AudioItem.formatDuration(currentPositionMs)} / ${AudioItem.formatDuration(totalDuration)}",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                    }

                    // 右侧：音频控制器（上一曲、快退5s、播放/暂停、快进5s、下一曲）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // 上一曲
                        IconButton(
                            onClick = {
                                onPlayPrevious()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一曲",
                                tint = PrimaryDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 快退 5s
                        IconButton(
                            onClick = {
                                onRewind5s()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay5,
                                contentDescription = "快退5秒",
                                tint = PrimaryDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 播放 / 暂停
                        IconButton(
                            onClick = {
                                onTogglePlayPause()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PrimaryLight)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 快进 5s
                        IconButton(
                            onClick = {
                                onFastForward5s()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward5,
                                contentDescription = "快进5秒",
                                tint = PrimaryDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 下一曲
                        IconButton(
                            onClick = {
                                onPlayNext()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一曲",
                                tint = PrimaryDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
