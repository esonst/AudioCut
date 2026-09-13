package com.example.mp3player.data.model

import android.net.Uri

/**
 * 本地音频数据模型
 */
data class AudioItem(
    val id: Long,
    val title: String,
    val artist: String = "未知艺术家",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val filePath: String = "",
    val folderPath: String = "",
    val folderName: String = "",
    val dateModifiedSec: Long = 0L,
    val contentUri: Uri? = null,
    val isFavorite: Boolean = false
) {
    val formattedDuration: String
        get() = formatDuration(durationMs)

    val formattedSize: String
        get() = formatFileSize(sizeBytes)

    companion object {
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) {
                "%.2f MB".format(mb)
            } else {
                "%.1f KB".format(kb)
            }
        }
    }
}
