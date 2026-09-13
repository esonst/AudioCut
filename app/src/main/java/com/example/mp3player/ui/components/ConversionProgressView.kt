package com.example.mp3player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mp3player.data.model.ConvertState
import com.example.mp3player.ui.theme.*
import java.util.Locale

/**
 * 转换进度卡片：转换中展示进度与时间信息；完成后提示"转换完成"，点击卡片即可关闭
 */
@Composable
fun ConversionProgressView(
    convertState: ConvertState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
    ) {
    val dismissable = convertState.isCompleted || convertState.isFailed

    if (!convertState.isCompleted) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                convertState.isCompleted -> Color(0xFFF0FDF4)
                convertState.isFailed -> Color(0xFFFEF2F2)
                else -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = dismissable) { onDismiss() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (convertState.isFailed) {
                // 转换失败提示（点击卡片关闭）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(Color(0xFFEF4444), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("!", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "转换失败",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDC2626)
                    )
                }
                convertState.error?.let {
                    Text(
                        text = "错误: $it",
                        fontSize = 11.sp,
                        color = Color(0xFFDC2626)
                    )
                }
                Text(
                    text = "点击卡片关闭",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            } else{
                // 转换中：进度条 + 百分比 + 时间信息（去掉冗余标题，紧凑排列）
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { convertState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = PrimaryLight,
                        trackColor = Color(0xFFE5E7EB)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "转换进度 ${(convertState.progress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "转换中...",
                            fontSize = 11.sp,
                            color = PrimaryLight
                        )
                    }
                }

                // 时间信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("已用时间", fontSize = 10.sp, color = TextSecondary)
                        Text(
                            formatDuration(convertState.elapsedTimeMs),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = PrimaryLight
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("剩余时间", fontSize = 10.sp, color = TextSecondary)
                        Text(
                            if (convertState.remainingTimeMs > 0) formatDuration(convertState.remainingTimeMs) else "--:--",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = PrimaryDark
                        )
                    }

                    if (convertState.speed != 1f) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("速度", fontSize = 10.sp, color = TextSecondary)
                            Text(
                                "${convertState.speed}x",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = PrimaryLight
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * 格式化毫秒时间为 HH:MM:SS 格式
 */
private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60

    return when {
        hours > 0 -> String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
        else -> String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds)
    }
}

