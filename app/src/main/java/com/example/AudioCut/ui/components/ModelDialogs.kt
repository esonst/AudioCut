package com.example.audiocut.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.example.audiocut.asr.ModelInstallCoordinator
import com.example.audiocut.asr.ModelManager
import com.example.audiocut.ui.theme.PrimaryDark
import com.example.audiocut.ui.theme.PrimaryLight
import com.example.audiocut.ui.theme.SurfaceVariantLight
import com.example.audiocut.ui.theme.TextMuted
import com.example.audiocut.ui.theme.TextSecondary

/** 选择模型压缩包（.tar.bz2）的 MIME 类型 */
private val MODEL_ARCHIVE_MIME_TYPES = arrayOf(
    "application/x-bzip2",
    "application/x-tar",
    "application/gzip",
    "application/octet-stream",
    "*/*"
)

/**
 * 统一的模型安装对话框宿主：
 * 根据 [ModelInstallCoordinator.UiState] 的相位渲染 缺失提示 / 下载进度 / 失败卡片。
 */
@Composable
fun ModelInstallDialogHost(
    state: ModelInstallCoordinator.UiState,
    onDownload: () -> Unit,
    onImport: (Uri) -> Unit,
    onDismiss: () -> Unit,
    onStopDownload: () -> Unit = {}
) {
    when (state.phase) {
        ModelInstallCoordinator.Phase.NONE,
        ModelInstallCoordinator.Phase.CHECKING -> Unit

        ModelInstallCoordinator.Phase.MISSING -> {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) onImport(uri) }
            ModelMissingDialog(
                type = state.type,
                onDownload = onDownload,
                onImport = { launcher.launch(MODEL_ARCHIVE_MIME_TYPES) },
                onDismiss = onDismiss
            )
        }

        ModelInstallCoordinator.Phase.DOWNLOADING -> {
            ModelProgressDialog(
                title = "正在下载${state.type?.displayName ?: "模型"}",
                progress = state.progress,
                progressLabel = "已下载",
                onStop = onStopDownload
            )
        }

        ModelInstallCoordinator.Phase.IMPORTING -> {
            // progress >= 0f：下载/复制已完成，进入解压阶段；否则仍处于文件复制阶段
            val extracting = state.progress >= 0f
            ModelProgressDialog(
                title = (if (extracting) "正在解压" else "正在导入") + (state.type?.displayName ?: "模型"),
                progress = state.progress,
                progressLabel = if (extracting) "已解压" else null,
                hint = if (extracting) "下载完成，正在解压安装…" else null,
                onStop = onStopDownload
            )
        }

        ModelInstallCoordinator.Phase.FAILED -> {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) onImport(uri) }
            ModelFailedCard(
                type = state.type,
                downloadUrl = state.downloadUrl,
                onImport = { launcher.launch(MODEL_ARCHIVE_MIME_TYPES) },
                onDismiss = onDismiss,
                onRetry = onDownload
            )
        }
    }
}

/**
 * 模型缺失提示：提供 下载 / 导入 / 取消
 */
@Composable
fun ModelMissingDialog(
    type: ModelManager.ModelType?,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未检测到${type?.displayName ?: "模型"}", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = "开启该功能需要本地模型文件。\n\n可直接从官方地址下载（约 200MB），下载过程会显示进度并自动安装；也可手动下载 .tar.bz2 压缩包后导入。",
                fontSize = 14.sp,
                color = TextSecondary
            )
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = TextMuted)
                }
                TextButton(onClick = onImport) {
                    Text("导入模型", color = PrimaryDark)
                }
                Button(onClick = onDownload) {
                    Text("下载模型", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    )
}

/**
 * 下载/导入/解压进度对话框
 * @param progress 0f~1f；-1f 显示不确定进度
 * @param progressLabel 进度文本前缀（如「已下载」「已解压」）；null 时不显示百分比
 * @param hint 进度条下方的辅助说明文字（如「下载完成，正在解压安装…」）
 */
@Composable
fun ModelProgressDialog(
    title: String,
    progress: Float,
    progressLabel: String? = "已下载",
    hint: String? = null,
    onStop: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = { /* 下载期间禁止关闭 */ },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = PrimaryLight,
                        trackColor = SurfaceVariantLight
                    )
                    Text(
                        text = "${progressLabel ?: "进度"} ${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = PrimaryLight,
                        trackColor = SurfaceVariantLight
                    )
                    Text(
                        text = "处理中，请稍候...",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        fontSize = 12.sp,
                        color = PrimaryLight,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (onStop != null) {
                TextButton(onClick = onStop) {
                    Text("停止", color = androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.8f))
                }
            }
        }
    )
}

/**
 * 下载失败卡片：支持一键重试（自动断点续传）；展示官方下载地址（点击可复制），引导手动下载后导入
 */
@Composable
fun ModelFailedCard(
    type: ModelManager.ModelType?,
    downloadUrl: String,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${type?.displayName ?: "模型"}下载失败", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "网络下载失败，可点击「重试」继续下载（自动续传，无需重新下载）；也可点击下方链接复制到浏览器手动下载 .tar.bz2 压缩包，再通过「导入模型」安装：",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Text(
                    text = downloadUrl,
                    fontSize = 12.sp,
                    color = PrimaryDark,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clickable {
                            clipboard.setText(AnnotatedString(downloadUrl))
                            Toast.makeText(context, "下载链接已复制", Toast.LENGTH_SHORT).show()
                        }
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = TextMuted)
                }
                TextButton(onClick = onImport) {
                    Text("导入模型", color = PrimaryDark)
                }
                Button(onClick = onRetry) {
                    Text("重试", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    )
}
