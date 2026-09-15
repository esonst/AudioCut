package com.example.AudioCut.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AudioCut.data.model.AudioItem
import com.example.AudioCut.ui.theme.*
import com.example.AudioCut.viewmodel.AudioLibraryViewModel

/**
 * 扫描本地文件并选择导入的对话框
 */
@Composable
fun ScanImportDialog(
    viewModel: AudioLibraryViewModel,
    onDismiss: () -> Unit
) {
    val deviceAudios by viewModel.deviceAudios.collectAsState()
    val isScanning by viewModel.isScanningDevice.collectAsState()
    
    val selectedUris = remember { mutableStateListOf<android.net.Uri>() }
    var selectedFolder by remember { mutableStateOf<String?>(null) }

    val groupedAudios = remember(deviceAudios) {
        deviceAudios.groupBy { it.folderName }
    }

    LaunchedEffect(Unit) {
        viewModel.scanDeviceAudios()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedFolder != null) {
                            IconButton(onClick = { selectedFolder = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                        Text(
                            text = if (selectedFolder == null) "按文件夹扫描" else selectedFolder!!,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDark
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = if (selectedFolder == null) "共发现 ${groupedAudios.size} 个文件夹" else "该文件夹下有 ${groupedAudios[selectedFolder]?.size ?: 0} 个音频",
                    fontSize = 14.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // List
                Box(modifier = Modifier.weight(1f)) {
                    if (isScanning) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (deviceAudios.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("未发现本地音频文件", color = TextMuted)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (selectedFolder == null) {
                                items(groupedAudios.keys.toList()) { folder ->
                                    ScanFolderRow(
                                        folderName = folder,
                                        count = groupedAudios[folder]?.size ?: 0,
                                        onClick = { selectedFolder = folder }
                                    )
                                }
                            } else {
                                items(groupedAudios[selectedFolder] ?: emptyList()) { audio ->
                                    ScanItemRow(
                                        audio = audio,
                                        isSelected = selectedUris.contains(audio.contentUri),
                                        onToggle = {
                                            if (audio.contentUri != null) {
                                                if (selectedUris.contains(audio.contentUri)) {
                                                    selectedUris.remove(audio.contentUri)
                                                } else {
                                                    selectedUris.add(audio.contentUri)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.importMultipleAudios(selectedUris.toList())
                            onDismiss()
                        },
                        enabled = selectedUris.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("导入选中的 (${selectedUris.size})")
                    }
                }
            }
        }
    }
}

@Composable
fun ScanFolderRow(
    folderName: String,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariantLight)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = AccentAmber,
            modifier = Modifier.size(32.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folderName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$count 个音频文件",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
        
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextMuted
        )
    }
}

@Composable
fun ScanItemRow(
    audio: AudioItem,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFFEFF6FF) else SurfaceVariantLight)
            .clickable { onToggle() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = PrimaryLight)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = audio.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${audio.formattedDuration} · ${audio.formattedSize} · ${audio.folderName}",
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 1
            )
        }
    }
}
