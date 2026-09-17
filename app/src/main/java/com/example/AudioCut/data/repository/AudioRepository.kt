package com.example.audiocut.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.Intent
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
            val duration = probeDuration(file.absolutePath)
            return@withContext AudioItem(
                id = file.hashCode().toLong(),
                title = file.name,
                filePath = file.absolutePath,
                durationMs = duration,
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
                    var size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                    val filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: ""
                    // MediaStore 未提供大小时直接从 Uri 探测，保证列表正确显示文件大小
                    if (size <= 0L) size = probeSize(context, uri)

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
            // 尝试解析真实本地路径（部分 provider 提供 DATA 列），持久化后重启仍可直接访问
            var filePath = ""
            try {
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idx >= 0) {
                            val p = c.getString(idx)
                            if (!p.isNullOrBlank() && File(p).exists()) filePath = p
                        }
                    }
                }
            } catch (_: Exception) {
            }
            // 从 Uri 直接读取元数据（不拷贝文件）
            val duration = probeDuration(context, uri)
            val size = probeSize(context, uri)
            return@withContext AudioItem(
                id = uri.hashCode().toLong(),
                title = fileName,
                filePath = filePath,
                durationMs = duration,
                sizeBytes = size,
                contentUri = uri,
                dateModifiedSec = System.currentTimeMillis() / 1000
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 从本地文件路径探测时长（毫秒），失败返回 0 */
    private fun probeDuration(filePath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** 从 content Uri 直接探测时长（毫秒），失败返回 0 */
    private fun probeDuration(context: Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** 从 content Uri 直接探测文件大小（字节），失败返回 0 */
    private fun probeSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.coerceAtLeast(0L)
            } ?: 0L
        } catch (_: Exception) {
            0L
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
     * 扫描 App 内部音频目录（历史已拷贝导入的文件 / 转换导出产物）
     * 新的导入均为 Uri 引用方式（不拷贝），不再写入该目录
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
     * 将 Uri 加入音频库（引用优先，分享类文件生成副本）
     * - Document/OpenDocument/媒体库 Uri：保持引用（持久化权限或媒体权限），不拷贝文件
     * - 分享/打开等临时授权 Uri（无法持久化、非媒体库）：生成副本到应用私有目录，
     *   保证应用重启后仍可访问；列表/文稿随副本持久化
     * - 元数据（名称/时长/大小）从 Uri 直接解析
     */
    suspend fun importAudioByReference(uri: Uri): AudioItem? = withContext(Dispatchers.IO) {
        if (context == null) return@withContext null
        try {
            var persistable = false
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    persistable = true
                } catch (_: Exception) {
                    // 分享/打开等场景为临时授权，无法跨重启持久化
                }
            }

            val item = resolveUriToAudioItem(uri)
            if (item == null) return@withContext null

            // 临时授权且非媒体库 Uri（媒体库 Uri 有 READ_MEDIA_AUDIO 即可长期访问）：
            // 分享过来的文件生成副本到应用私有目录，保证重启后仍可访问
            if (uri.scheme == "content" && !persistable && uri.authority != "media") {
                copyToInternalStorage(uri, item) ?: item
            } else {
                item
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将临时授权的 content Uri 生成副本到应用私有音频目录（仅分享/打开等无法持久化授权的场景），
     * 返回指向内部副本的 AudioItem；拷贝失败返回 null
     */
    private fun copyToInternalStorage(uri: Uri, item: AudioItem): AudioItem? {
        return try {
            val appContext = context ?: return null
            val dir = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                ?: appContext.filesDir
            dir.mkdirs()
            val rawExt = item.filePath.substringAfterLast('.', "")
                .ifBlank { item.contentUri?.lastPathSegment?.substringAfterLast('.', "") ?: "" }
            val ext = rawExt.lowercase().ifBlank { "m4a" }
            val safeTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "shared" }
            val dest = File(dir, "${System.currentTimeMillis()}_$safeTitle.$ext")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (!dest.exists() || dest.length() == 0L) {
                dest.delete()
                return null
            }
            item.copy(
                id = dest.absolutePath.hashCode().toLong(),
                filePath = dest.absolutePath,
                contentUri = Uri.fromFile(dest),
                sizeBytes = dest.length(),
                dateModifiedSec = dest.lastModified() / 1000
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 重命名音频库条目：
     * - 本地/内部文件：物理重命名文件（保留原扩展名）
     * - 媒体库引用：更新 MediaStore 的 DISPLAY_NAME 与 TITLE（id 不变，文稿保留）
     * - 文档 Uri 引用：通过 DocumentsContract 重命名源文件；不支持时仅修改显示名
     * 返回重命名后的 AudioItem；失败返回 null（名称冲突/文件不可写等）
     */
    suspend fun renameAudioItem(item: AudioItem, newName: String): AudioItem? = withContext(Dispatchers.IO) {
        val appContext = context ?: return@withContext null
        val cleanName = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        if (cleanName.isBlank()) return@withContext null

        try {
            // 1. 本地/内部文件：物理重命名
            val file = item.filePath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
            if (file != null) {
                val ext = file.extension.ifBlank { "m4a" }
                val newFile = File(file.parent, "$cleanName.$ext")
                if (newFile.exists() && newFile.absolutePath != file.absolutePath) return@withContext null
                if (!file.renameTo(newFile)) return@withContext null
                return@withContext item.copy(
                    id = newFile.absolutePath.hashCode().toLong(),
                    title = "$cleanName.$ext",
                    filePath = newFile.absolutePath,
                    contentUri = Uri.fromFile(newFile),
                    dateModifiedSec = newFile.lastModified() / 1000
                )
            }

            // 2. 媒体库引用：更新 DISPLAY_NAME 与 TITLE（id = mediaId 不变，关联文稿自动保留）
            val contentUri = item.contentUri
            if (contentUri != null && contentUri.authority == "media") {
                val ext = item.filePath.substringAfterLast('.', "").ifBlank { "m4a" }
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$cleanName.$ext")
                    put(MediaStore.Audio.Media.TITLE, cleanName)
                }
                val updated = appContext.contentResolver.update(contentUri, values, null, null)
                if (updated > 0) {
                    val newPath = try {
                        appContext.contentResolver.query(
                            contentUri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val idx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                                if (idx >= 0) c.getString(idx) ?: item.filePath else item.filePath
                            } else item.filePath
                        } ?: item.filePath
                    } catch (_: Exception) {
                        item.filePath
                    }
                    return@withContext item.copy(title = cleanName, filePath = newPath)
                }
                return@withContext null
            }

            // 3. 文档 Uri 引用：尝试重命名源文件
            if (contentUri != null) {
                val ext = item.filePath.substringAfterLast('.', "").ifBlank {
                    item.contentUri?.lastPathSegment?.substringAfterLast('.', "") ?: ""
                }.ifBlank { "m4a" }
                try {
                    val newUri = android.provider.DocumentsContract.renameDocument(
                        appContext.contentResolver, contentUri, "$cleanName.$ext"
                    )
                    if (newUri != null) {
                        return@withContext item.copy(
                            id = newUri.hashCode().toLong(),
                            title = "$cleanName.$ext",
                            contentUri = newUri
                        )
                    }
                } catch (_: Exception) {
                    // 不支持重命名的 provider，回退为仅修改显示名
                }
                return@withContext item.copy(title = cleanName)
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 扫描已引用的外部音频（Uri 引用列表）
     * 解析失败（源文件被移除/权限失效）的条目自动剔除
     */
    suspend fun scanReferencedAudios(uriStrings: Set<String>): List<AudioItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AudioItem>()
        uriStrings.forEach { uriString ->
            runCatching { Uri.parse(uriString) }.getOrNull()?.let { uri ->
                resolveUriToAudioItem(uri)?.let { list.add(it) }
            }
        }
        list
    }

    /**
     * 扫描完整音频库：
     * 1. 外部引用（不拷贝的 Uri 引用导入）
     * 2. 应用内部目录（历史已拷贝文件 / 转换导出产物）
     * 按内容 Uri 去重，按修改时间倒序
     */
    suspend fun scanLibraryAudios(uriStrings: Set<String>): List<AudioItem> = withContext(Dispatchers.IO) {
        val referenced = scanReferencedAudios(uriStrings)
        val internal = scanImportedAudios()
        (referenced + internal)
            .distinctBy { it.contentUri?.toString() ?: it.filePath }
            .sortedByDescending { it.dateModifiedSec }
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
