package com.example.audiocut.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiocut.data.model.AudioItem
import com.example.audiocut.data.model.ConvertQuality
import com.example.audiocut.ui.components.ConversionProgressView
import com.example.audiocut.ui.theme.*
import com.example.audiocut.viewmodel.ConvertViewModel
import com.example.audiocut.viewmodel.MainViewModel

/**
 * 格式转换界面
 */
@Composable
fun ConvertScreen(mainViewModel: MainViewModel, convertViewModel: ConvertViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val currentAudio by mainViewModel.currentPlayingAudio.collectAsState()
    val convertState by convertViewModel.convertState.collectAsState()
    val convertInputFile by convertViewModel.convertInputFile.collectAsState()
    val isVideoInput by convertViewModel.isVideoInput.collectAsState()

    var selectedQuality by remember { mutableStateOf(ConvertQuality.HIGH) }
    var showInputFileSelection by remember { mutableStateOf(false) }
    var outputFileName by remember { mutableStateOf<String?>(null) }
    var showOutputNameEdit by remember { mutableStateOf(false) }
    // 进度/完成卡片的关闭状态（点击卡片关闭后不再显示，重新开始转换时重置）
    var progressCardDismissed by remember { mutableStateOf(false) }

    // 重新开始转换时重置卡片关闭状态
    LaunchedEffect(convertState.isConverting) {
        if (convertState.isConverting) progressCardDismissed = false
    }

    // 输入不是视频时自动切回高质量（复制音频选项仅对视频输入显示）
    LaunchedEffect(isVideoInput) {
        if (!isVideoInput && selectedQuality == ConvertQuality.EXTRACT) {
            selectedQuality = ConvertQuality.HIGH
        }
    }
    fun String.removeFileExtension(): String {
        val dotIndex = lastIndexOf('.')
        return if (dotIndex > 0) substring(0, dotIndex) else this
    }
    // 默认输出文件名：前缀 "converted" + 原文件名
    val defaultOutputName = when {
        convertInputFile != null -> "converted_${java.io.File(convertInputFile!!).nameWithoutExtension}"
        currentAudio != null -> "c_${currentAudio!!.title.removeFileExtension()}"
        else -> null
    }
    val effectiveOutputName = outputFileName ?: defaultOutputName

    // 输出扩展名：复制音频固定 m4a（-c:a copy 直拷原音频流），其余转 mp3
    val outputExtension = if (selectedQuality == ConvertQuality.EXTRACT) "m4a" else "mp3"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundLight)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 顶部标题与右上角【转换】/【停止】按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "格式转换",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark
            )

            if (convertState.isConverting) {
                // 转换中显示【停止】按钮
                TextButton(
                    onClick = { convertViewModel.cancelConversion() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // 空闲时显示【转换】按钮：点击直接开始转换
                Button(
                    onClick = {
                        if (convertInputFile == null && currentAudio == null) {
                            Toast.makeText(context, "请先选择输入文件", Toast.LENGTH_SHORT).show()
                        } else {
                            convertViewModel.startConversion(selectedQuality, customFileName = effectiveOutputName)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("转换", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 转换设置：输入文件 + 输出文件名（合并单卡片节省纵向空间）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // ===== 输入文件 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AudioFile,
                            contentDescription = null,
                            tint = PrimaryLight,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        Text(
                            text = "输入文件",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDark
                        )
                    }

                    TextButton(
                        onClick = { showInputFileSelection = true },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("更改", fontSize = 12.sp, color = PrimaryLight)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 显示输入文件信息
                if (convertInputFile != null) {
                    val inputFile = java.io.File(convertInputFile!!)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = inputFile.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "路径: ${inputFile.path}  ·  ${formatFileSize(inputFile.length())}",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (currentAudio != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentAudio!!.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = AudioItem.formatDuration(currentAudio!!.durationMs),
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                } else {
                    Text(
                        text = "请选择输入文件",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                }

                // 分隔线
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    thickness = 1.dp,
                    color = BorderLight
                )

                // ===== 输出文件名 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = PrimaryLight,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "输出文件名",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDark
                        )
                    }

                    TextButton(
                        onClick = { showOutputNameEdit = true },
                        enabled = effectiveOutputName != null,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("修改", fontSize = 12.sp, color = PrimaryLight)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = effectiveOutputName?.let { "$it.$outputExtension" } ?: "请先选择输入文件",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (effectiveOutputName != null) PrimaryDark else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = effectiveOutputName != null) { showOutputNameEdit = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 输出选项（紧凑单行选择，去掉外层大卡片节省纵向空间）
        Text(
            text = "输出选项",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QualityChip(
                label = "高质量",
                bitrate = "320kbps",
                icon = Icons.Default.HighQuality,
                selected = selectedQuality == ConvertQuality.HIGH,
                onClick = { selectedQuality = ConvertQuality.HIGH },
                modifier = Modifier.weight(1f)
            )

            QualityChip(
                label = "低质量",
                bitrate = "128kbps",
                icon = Icons.Default.Speed,
                selected = selectedQuality == ConvertQuality.LOW,
                onClick = { selectedQuality = ConvertQuality.LOW },
                modifier = Modifier.weight(1f)
            )
        }

        // 输入为视频时，额外提供【复制音频】：-c:a copy 直接复制原音频流为 m4a，不重新编码，速度最快
        if (isVideoInput) {
            Spacer(modifier = Modifier.height(6.dp))
            QualityChip(
                label = "复制音频",
                bitrate = "直接复制 · 最快",
                icon = Icons.Default.ContentCopy,
                selected = selectedQuality == ConvertQuality.EXTRACT,
                onClick = { selectedQuality = ConvertQuality.EXTRACT },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

//        if (convertState.isIdle) {
//            // 转换前的操作按钮（转换入口已移至右上角，此处保留预览）
//            OutlinedButton(
//                onClick = {
//                    if (convertInputFile != null) {
//                        // 可以在这里添加预览功能
//                        Toast.makeText(context, "预览功能开发中", Toast.LENGTH_SHORT).show()
//                    } else if (currentAudio != null) {
//                        // 播放当前音频
//                        mainViewModel.playAudio(currentAudio!!)
//                    }
//                },
//                enabled = convertInputFile != null || currentAudio != null,
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .height(34.dp),
//                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryLight),
//                shape = RoundedCornerShape(10.dp),
//                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
//            ) {
//                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(14.dp))
//                Spacer(modifier = Modifier.width(4.dp))
//                Text("预览", fontSize = 13.sp)
//            }
//        } else
        if (!convertState.isIdle && !progressCardDismissed) {
            // 转换进度及时间卡片；转换完成后提示完成，点击卡片即可关闭
            ConversionProgressView(
                convertState = convertState,
                onDismiss = { progressCardDismissed = true }
            )
        }

        // 转换完成后：输出文件卡片，显示输出路径，可保存或分享
        if (convertState.isCompleted && convertState.outputFilePath != null) {
            Spacer(modifier = Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "输出文件",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDark,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = "✓ 已完成",
                            fontSize = 11.sp,
                            color = Color(0xFF16A34A),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 输出文件路径
                    Text(
                        text = convertState.outputFilePath ?: "",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 保存按钮
                        OutlinedButton(
                            onClick = {
                                convertViewModel.saveConvertedToLibrary()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryLight),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("保存", fontSize = 13.sp)
                        }

                        // 分享按钮
                        Button(
                            onClick = {
                                convertViewModel.shareConvertedFile(context)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("分享", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // 输出文件名修改对话框
    if (showOutputNameEdit) {
        var editedName by remember { mutableStateOf(effectiveOutputName ?: "") }

        AlertDialog(
            onDismissRequest = { showOutputNameEdit = false },
            title = { Text("修改输出文件名") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    suffix = { Text(".$outputExtension", fontSize = 14.sp, color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 清理非法字符
                        val name = editedName.trim()
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            .ifEmpty { null }
                        outputFileName = name
                        showOutputNameEdit = false
                    }
                ) {
                    Text("确定", color = PrimaryLight)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // 恢复默认名称
                        outputFileName = null
                        showOutputNameEdit = false
                    }
                ) {
                    Text("恢复默认", color = TextSecondary)
                }
            }
        )
    }

    // 输入文件选择对话框
    if (showInputFileSelection) {
        AlertDialog(
            onDismissRequest = { showInputFileSelection = false },
            title = { Text("选择输入文件") },
            text = {
                Text(
                    "选择转换的输入文件：\n" +
                    "• 使用预览文件（如果可用）\n" +
                    "• 使用当前播放的音频\n" +
                    "• 使用音频库中的文件",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        onClick = {
                            // 使用预览文件
                            mainViewModel.navigateToConvertFormat(convertInputFile)
                            showInputFileSelection = false
                        }
                    ) {
                        Text("预览文件")
                    }
                    
                    TextButton(
                        onClick = {
                            // 使用当前播放音频
                            val inputFile = currentAudio?.filePath
                            if (inputFile != null) {
                                mainViewModel.navigateToConvertFormat(inputFile)
                            }
                            showInputFileSelection = false
                        }
                    ) {
                        Text("当前音频")
                    }
                    
                    TextButton(
                        onClick = {
                            // 返回音频库选择
                            mainViewModel.navigateTo(com.example.audiocut.navigation.AppScreen.AUDIO_LIBRARY)
                            showInputFileSelection = false
                        }
                    ) {
                        Text("音频库")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showInputFileSelection = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 紧凑的质量选择单项：图标 + 名称 + 码率 横向单行布局
 */
@Composable
private fun QualityChip(
    label: String,
    bitrate: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PrimaryLight.copy(alpha = 0.1f) else SurfaceVariantLight)
            .border(
                width = 1.dp,
                color = if (selected) PrimaryLight else BorderLight,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryLight,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = PrimaryDark
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = bitrate,
            fontSize = 10.sp,
            color = TextSecondary
        )
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    val df = java.text.DecimalFormat("#.##")
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${df.format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024))} MB"
        else -> "${df.format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
}