package com.example.audiocut.data.repository

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.audiocut.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音频库数据仓库：本地音频扫描、元数据提取、筛选与排序
 */
class AudioRepository(private val context: Context? = null) {

    companion object {
        /** 导入库支持的音频/视频扩展名 */
        val SUPPORTED_MEDIA_EXTENSIONS = setOf(
            "mp3", "wav", "m4a", "flac", "aac", "ogg", "opus",
            "mp4", "m4v", "mkv", "avi", "webm", "3gp", "mov", "ts"
        )
    }

    /**
     * 扫描本地所有音频文件 (mp3/wav/m4a/flac)
     */
    suspend fun scanLocalAudios(): List<AudioItem> = withContext(Dispatchers.IO) {
        val audioList = mutableListOf<AudioItem>()
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
                "${MediaStore.Audio.Media.DATA} LIKE '%.mp3' OR " +
                "${MediaStore.Audio.Media.DATA} LIKE '%.wav' OR " +
                "${MediaStore.Audio.Media.DATA} LIKE '%.m4a' OR " +
                "${MediaStore.Audio.Media.DATA} LIKE '%.flac'"

        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        try {
            val cursor: Cursor? = context?.contentResolver?.query(
                uri,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val displayNameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateModifiedCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val displayName = it.getString(displayNameCol) ?: "未知音频"
                    val title = it.getString(titleCol) ?: displayName
                    val artist = it.getString(artistCol) ?: "未知艺术家"
                    val duration = it.getLong(durationCol)
                    val size = it.getLong(sizeCol)
                    val filePath = it.getString(dataCol) ?: ""
                    val dateModified = it.getLong(dateModifiedCol)

                    val file = File(filePath)
                    val folderPath = file.parent ?: ""
                    val folderName = file.parentFile?.name ?: "默认目录"

                    val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    // 过滤掉非支持扩展名与过小/无效文件
                    val lowerName = filePath.lowercase()
                    if (lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                        lowerName.endsWith(".m4a") || lowerName.endsWith(".flac") ||
                        lowerName.endsWith(".aac")
                    ) {
                        audioList.add(
                            AudioItem(
                                id = id,
                                title = if (title.isNotBlank()) title else displayName,
                                artist = if (artist.contains("<unknown>", ignoreCase = true) || artist.isBlank()) "未知艺术家" else artist,
                                durationMs = duration,
                                sizeBytes = size,
                                filePath = filePath,
                                folderPath = folderPath,
                                folderName = folderName,
                                dateModifiedSec = dateModified,
                                contentUri = itemUri
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        audioList
    }

    /**
     * 将 Uri 解析为 AudioItem (不拷贝文件)
     */
    suspend fun resolveUriToAudioItem(uri: Uri): AudioItem? = withContext(Dispatchers.IO) {
        if (context == null) return@withContext null
        
        // 如果是 File Uri
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return@withContext null)
            if (!file.exists()) return@withContext null
            return@withContext AudioItem(
                id = file.hashCode().toLong(),
                title = file.name,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                dateModifiedSec = file.lastModified() / 1000,
                contentUri = uri
            )
        }

        // 如果是 Content Uri
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA
        )

        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)) ?: "未知音频"
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: displayName
                    val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)) ?: "未知艺术家"
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                    val filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: ""

                    return@withContext AudioItem(
                        id = id,
                        title = title,
                        artist = artist,
                        durationMs = duration,
                        sizeBytes = size,
                        filePath = filePath,
                        contentUri = uri,
                        dateModifiedSec = System.currentTimeMillis() / 1000
                    )
                }
            }
        } catch (_: Exception) {
        }
        
        // Fallback for URIs that don't support projection
        try {
            val fileName = getFileNameFromUri(uri) ?: "未知音频"
            return@withContext AudioItem(
                id = uri.hashCode().toLong(),
                title = fileName,
                contentUri = uri,
                dateModifiedSec = System.currentTimeMillis() / 1000
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        var result: String? = null
        if (uri.scheme == "content") {
            context?.contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    result = cursor.getString(nameIndex)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    /**
     * 扫描 App 内部音频目录 (导入的音/视频文件都放在这里)
     */
    suspend fun scanImportedAudios(): List<AudioItem> = withContext(Dispatchers.IO) {
        val audioList = mutableListOf<AudioItem>()
        val destDir = context?.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: return@withContext emptyList()
        
        if (!destDir.exists()) destDir.mkdirs()
        
        val retriever = MediaMetadataRetriever()
        destDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.lowercase() in SUPPORTED_MEDIA_EXTENSIONS) {
                var duration = 0L
                try {
                    retriever.setDataSource(file.absolutePath)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                } catch (_: Exception) {
                }
                
                audioList.add(
                    AudioItem(
                        id = file.absolutePath.hashCode().toLong(),
                        title = file.name,
                        filePath = file.absolutePath,
                        durationMs = duration,
                        sizeBytes = file.length(),
                        dateModifiedSec = file.lastModified() / 1000,
                        contentUri = Uri.fromFile(file),
                        folderName = "导入库",
                        folderPath = file.parent ?: ""
                    )
                )
            }
        }
        try { retriever.release() } catch (e: Exception) {}
        audioList.sortedByDescending { it.dateModifiedSec }
    }

    /**
     * 将音频文件从 Uri 导入到 App 内部存储目录
     */
    suspend fun importAudioFile(uri: Uri): AudioItem? = withContext(Dispatchers.IO) {
        if (context == null) return@withContext null
        try {
            val fileName = getFileNameFromUri(uri) ?: "imported_${System.currentTimeMillis()}.mp3"
            val destDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val destFile = File(destDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // 重新解析导入后的文件并获取时长
            val retriever = MediaMetadataRetriever()
            var duration = 0L
            try {
                retriever.setDataSource(destFile.absolutePath)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }

            return@withContext AudioItem(
                id = destFile.absolutePath.hashCode().toLong(),
                title = fileName,
                filePath = destFile.absolutePath,
                durationMs = duration,
                sizeBytes = destFile.length(),
                dateModifiedSec = destFile.lastModified() / 1000,
                contentUri = Uri.fromFile(destFile)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 按关键词、时长区间、文件大小区间进行过滤
     */
    fun filterAudios(
        audios: List<AudioItem>,
        query: String = "",
        durationFilter: DurationFilter = DurationFilter.ALL,
        sizeFilter: SizeFilter = SizeFilter.ALL
    ): List<AudioItem> {
        return audios.filter { audio ->
            val matchQuery = query.isBlank() ||
                    audio.title.contains(query, ignoreCase = true) ||
                    audio.artist.contains(query, ignoreCase = true) ||
                    audio.folderName.contains(query, ignoreCase = true)

            val matchDuration = audio.durationMs in durationFilter.minMs..durationFilter.maxMs
            val matchSize = audio.sizeBytes in sizeFilter.minBytes..sizeFilter.maxBytes

            matchQuery && matchDuration && matchSize
        }
    }

    /**
     * 排序音频列表：名称、大小、时长、修改时间 升序/降序
     */
    fun sortAudios(
        audios: List<AudioItem>,
        field: SortField,
        direction: SortDirection
    ): List<AudioItem> {
        val comparator: Comparator<AudioItem> = when (field) {
            SortField.NAME -> Comparator { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.DURATION -> compareBy { it.durationMs }
            SortField.DATE_MODIFIED -> compareBy { it.dateModifiedSec }
        }

        return if (direction == SortDirection.ASCENDING) {
            audios.sortedWith(comparator)
        } else {
            audios.sortedWith(comparator.reversed())
        }
    }

    /**
     * 按文件夹路径分组音频
     */
    fun groupByFolder(audios: List<AudioItem>): Map<String, List<AudioItem>> {
        return audios.groupBy { it.folderName }
    }
}
