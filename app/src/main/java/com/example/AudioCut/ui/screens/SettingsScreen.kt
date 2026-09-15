package com.example.audiocut.ui.screens

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
import com.example.audiocut.ui.components.ModelInstallDialogHost
import com.example.audiocut.ui.theme.*
import com.example.audiocut.viewmodel.MainViewModel
import com.example.audiocut.viewmodel.SettingsViewModel

/**
 * 设置界面：文稿设置卡片（开启文稿 / 开启排版优化 / 分块 / VAD，未开启文稿时隐藏其它设置）+ 数据管理 + 关于
 */
@Composable
fun SettingsScreen(mainViewModel: MainViewModel, settingsViewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val enableDocTranscript by settingsViewModel.enableDocTranscript.collectAsState()
    val punctChunkChars by settingsViewModel.punctChunkChars.collectAsState()
    val enableSlicing by settingsViewModel.enableSlicing.collectAsState()
    val asrChunkSeconds by settingsViewModel.asrChunkSeconds.collectAsState()
    val enableVad by settingsViewModel.enableVad.collectAsState()
    val vadThreshold by settingsViewModel.vadThreshold.collectAsState()
    val vadMinSilence by settingsViewModel.vadMinSilence.collectAsState()
    val vadMinSpeech by settingsViewModel.vadMinSpeech.collectAsState()
    val vadMaxSpeech by settingsViewModel.vadMaxSpeech.collectAsState()
    val modelInstallState by settingsViewModel.modelInstallState.collectAsState()
    val enableLayoutOptimization by settingsViewModel.enableLayoutOptimization.collectAsState()

    var chunkInput by remember(asrChunkSeconds) { mutableStateOf(asrChunkSeconds.toString()) }
    var punctChunkInput by remember(punctChunkChars) { mutableStateOf(punctChunkChars.toString()) }
    var vadThresholdInput by remember(vadThreshold) { mutableStateOf(vadThreshold.toString()) }
    var vadMinSilenceInput by remember(vadMinSilence) { mutableStateOf(vadMinSilence.toString()) }
    var vadMinSpeechInput by remember(vadMinSpeech) { mutableStateOf(vadMinSpeech.toString()) }
    var vadMaxSpeechInput by remember(vadMaxSpeech) { mutableStateOf(vadMaxSpeech.toString()) }

    // 文稿转写默认开启但模型缺失时，进入设置页主动提示下载/导入（本会话只提示一次）
    LaunchedEffect(Unit) {
        settingsViewModel.verifyDocTranscriptModel()
    }

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
            // ==================== 文稿设置卡片 ====================
            SettingsSection(title = "文稿设置") {
                SettingsSwitchItem(
                    icon = Icons.Default.Description,
                    title = "开启文稿",
                    subtitle = "需安装 SenseVoice 识别模型；未安装时提示下载或导入",
                    checked = enableDocTranscript,
                    onCheckedChange = {
                        settingsViewModel.onToggleDocTranscript(it)
                    }
                )

                // 未开启文稿时，隐藏其它文稿相关设置
                if (enableDocTranscript) {
                    SettingsSwitchItem(
                        icon = Icons.Default.AutoAwesome,
                        title = "开启排版优化",
                        subtitle = "使用 punct-ct 标点模型删除并重加标点；未安装时提示下载或导入",
                        checked = enableLayoutOptimization,
                        onCheckedChange = {
                            settingsViewModel.onToggleLayoutOptimization(it)
                        }
                    )

                    if (enableLayoutOptimization) {
                        SettingsInputItem(
                            icon = Icons.Default.TextFields,
                            title = "排版优化单次字数",
                            subtitle = "文稿页【排版优化】每次交给标点模型处理的字符数（默认 1000，重叠窗口固定 20 字）",
                            value = punctChunkInput,
                            onValueChange = { input ->
                                punctChunkInput = input.filter { it.isDigit() }
                                punctChunkInput.toIntOrNull()?.let {
                                    if (it in 100..5000) settingsViewModel.setPunctChunkChars(it)
                                }
                            }
                        )
                    }

                HorizontalDivider(color = SurfaceVariantLight.copy(alpha = 0.6f))

                // ----- 分块设置 -----
                SettingsSwitchItem(
                    icon = Icons.Default.VerticalSplit,
                    title = "启用分片处理",
                    subtitle = "长音频建议开启以节省内存，关闭则全量识别",
                    checked = enableSlicing,
                    onCheckedChange = {
                        settingsViewModel.setEnableSlicing(it)
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
                                if (it > 0) settingsViewModel.setAsrChunkSeconds(it)
                            }
                        }
                    )
                }

                HorizontalDivider(color = SurfaceVariantLight.copy(alpha = 0.6f))

                // ----- VAD 设置 -----
                SettingsSwitchItem(
                    icon = Icons.Default.Mic,
                    title = "VAD 语音检测",
                    subtitle = "检测人声片段并跳过静音，提升识别准确率",
                    checked = enableVad,
                    onCheckedChange = {
                        settingsViewModel.setEnableVad(it)
                    }
                )

                if (enableVad) {
                    SettingsInputItem(
                        icon = Icons.Default.Tune,
                        title = "VAD 检测阈值",
                        subtitle = "0.05 - 0.95，越高越严格",
                        value = vadThresholdInput,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() || it == '.' }
                            vadThresholdInput = filtered
                            filtered.toFloatOrNull()?.let { settingsViewModel.setVadThreshold(it) }
                        }
                    )
                    SettingsInputItem(
                        icon = Icons.Default.Schedule,
                        title = "最短静音 (秒)",
                        subtitle = "静音超过该值才切分 (0.05 - 5)",
                        value = vadMinSilenceInput,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() || it == '.' }
                            vadMinSilenceInput = filtered
                            filtered.toFloatOrNull()?.let { settingsViewModel.setVadMinSilence(it) }
                        }
                    )
                    SettingsInputItem(
                        icon = Icons.Default.Schedule,
                        title = "最短语音 (秒)",
                        subtitle = "短于该值的人声视为噪音 (0.05 - 5)",
                        value = vadMinSpeechInput,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() || it == '.' }
                            vadMinSpeechInput = filtered
                            filtered.toFloatOrNull()?.let { settingsViewModel.setVadMinSpeech(it) }
                        }
                    )
                    SettingsInputItem(
                        icon = Icons.Default.Schedule,
                        title = "最长语音 (秒)",
                        subtitle = "单段语音上限 (1 - 120)",
                        value = vadMaxSpeechInput,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() || it == '.' }
                            vadMaxSpeechInput = filtered
                            filtered.toFloatOrNull()?.let { settingsViewModel.setVadMaxSpeech(it) }
                        }
                    )
                }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==================== 数据管理 ====================
            SettingsSection(title = "数据管理") {
                SettingsActionItem(
                    icon = Icons.Default.DeleteSweep,
                    title = "清作文稿缓存",
                    subtitle = "删除本地所有已识别的文字文稿",
                    actionLabel = "清空",
                    onClick = {
                        settingsViewModel.clearAllTranscripts()
                    }
                )
                SettingsActionItem(
                    icon = Icons.Default.FolderDelete,
                    title = "清理导出音频",
                    subtitle = "刷新音频库，清理已失去文件位置的失效记录",
                    actionLabel = "清理",
                    onClick = {
                        settingsViewModel.clearAllExports()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==================== 性能与关于 ====================
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

    // 模型下载/导入对话框（缺失提示 / 进度 / 失败卡片）
    ModelInstallDialogHost(
        state = modelInstallState,
        onDownload = { settingsViewModel.downloadPromptedModel() },
        onImport = { uri -> settingsViewModel.importPromptedModel(uri) },
        onDismiss = { settingsViewModel.dismissModelDialog() },
        onStopDownload = { settingsViewModel.stopModelDownload() }
    )
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
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Number
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
            modifier = Modifier.width(90.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
