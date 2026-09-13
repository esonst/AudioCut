package com.example.mp3player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mp3player.ui.theme.*
import com.example.mp3player.viewmodel.MainViewModel

/**
 * 设置界面：管理 ASR 切片、VAD (恒开) 及其他超参数
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val asrChunkSeconds by viewModel.asrChunkSeconds.collectAsState()
    val enableSlicing by viewModel.enableSlicing.collectAsState()

    var chunkInput by remember(asrChunkSeconds) { mutableStateOf(asrChunkSeconds.toString()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundLight)
            .statusBarsPadding()
    ) {
        // 顶部导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = PrimaryDark)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "系统设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 110.dp) // 预留底部导航栏高度，避免内容被遮挡
        ) {
            SettingsSection(title = "语音识别 (ASR) 配置") {
                SettingsSwitchItem(
                    icon = Icons.Default.VerticalSplit,
                    title = "启用分片处理",
                    subtitle = "长音频建议开启以节省内存，关闭则全量识别",
                    checked = enableSlicing,
                    onCheckedChange = {
                        viewModel.setEnableSlicing(it)
                    }
                )
                
                if (enableSlicing) {
                    SettingsInputItem(
                        icon = Icons.Default.Timer,
                        title = "分片间隔 (秒)",
                        subtitle = "输入分片秒数 (建议 15-60s)",
                        value = chunkInput,
                        onValueChange = { input ->
                            chunkInput = input.filter { it.isDigit() }
                            chunkInput.toIntOrNull()?.let { 
                                if (it > 0) viewModel.setAsrChunkSeconds(it)
                            }
                        }
                    )
                }

                SettingsInfoItem(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "VAD 语音检测",
                    value = "始终开启"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingsSection(title = "数据管理") {
                SettingsActionItem(
                    icon = Icons.Default.DeleteSweep,
                    title = "清作文稿缓存",
                    subtitle = "删除本地所有已识别的文字文稿",
                    actionLabel = "清空",
                    onClick = {
                        viewModel.clearAllTranscripts()
                    }
                )
                SettingsActionItem(
                    icon = Icons.Default.FolderDelete,
                    title = "清理导出音频",
                    subtitle = "删除导出目录下的所有合并文件",
                    actionLabel = "清理",
                    onClick = {
                        viewModel.clearAllExports()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingsSection(title = "性能与关于") {
                SettingsInfoItem(
                    icon = Icons.Default.Speed,
                    title = "渲染模式",
                    value = "句子级加速"
                )
                SettingsInfoItem(
                    icon = Icons.Default.Info,
                    title = "软件版本",
                    value = "v2.1.0-Release"
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryLight,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
fun SettingsInputItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryDark, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = PrimaryDark, fontWeight = FontWeight.Medium)
            Text(text = subtitle, fontSize = 12.sp, color = TextSecondary)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(80.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryLight,
                unfocusedBorderColor = SurfaceVariantLight
            )
        )
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryDark, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = PrimaryDark, fontWeight = FontWeight.Medium)
            Text(text = subtitle, fontSize = 12.sp, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PrimaryLight,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = SurfaceVariantLight
            )
        )
    }
}

@Composable
fun SettingsActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = PrimaryDark, fontWeight = FontWeight.Medium)
            Text(text = subtitle, fontSize = 12.sp, color = TextSecondary)
        }
        TextButton(onClick = onClick) {
            Text(actionLabel, color = Color.Red.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryDark, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = title, fontSize = 15.sp, color = PrimaryDark, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 14.sp, color = TextSecondary)
    }
}
