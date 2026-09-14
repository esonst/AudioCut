package com.example.mp3player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mp3player.ui.theme.PrimaryLight
import com.example.mp3player.ui.theme.TextMuted
import com.example.mp3player.navigation.AppScreen

/**
 * 底部导航栏：音频库、文稿、剪辑、裁剪、格式转换 五大页面
 */

@Composable
fun BottomNavBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(
            icon = Icons.Default.LibraryMusic,
            label = "音频库",
            isSelected = currentScreen == AppScreen.AUDIO_LIBRARY,
            onClick = { onNavigate(AppScreen.AUDIO_LIBRARY) }
        )

        NavItem(
            icon = Icons.Default.Description,
            label = "文稿",
            isSelected = currentScreen == AppScreen.TRANSCRIPT,
            onClick = { onNavigate(AppScreen.TRANSCRIPT) }
        )

        NavItem(
            icon = Icons.Default.ContentCut,
            label = "剪辑",
            isSelected = currentScreen == AppScreen.CLIP,
            onClick = { onNavigate(AppScreen.CLIP) }
        )

        NavItem(
            icon = Icons.Default.DeleteSweep,
            label = "裁剪",
            isSelected = currentScreen == AppScreen.TRIM,
            onClick = { onNavigate(AppScreen.TRIM) }
        )

        NavItem(
            icon = Icons.Default.AudioFile,
            label = "格式转换",
            isSelected = currentScreen == AppScreen.CONVERT,
            onClick = { onNavigate(AppScreen.CONVERT) }
        )

    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val tintColor by animateColorAsState(
        targetValue = if (isSelected) PrimaryLight else TextMuted,
        label = "navTint"
    )

    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tintColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = tintColor
        )
    }
}
@Preview(showBackground = true, name = "底部导航预览")
@Composable
fun PreviewBottomNavBar() {
    BottomNavBar(
        currentScreen = AppScreen.AUDIO_LIBRARY,
        onNavigate = {}
    )
}
