package com.example.mp3player.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.example.mp3player.data.model.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用偏好与状态持久化仓库
 * 支持收藏列表、最后播放歌曲与进度、播放循环模式、播放倍速、各音频标记片段、文稿识别缓存等的跨会话保存与恢复
 */
class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mp3_player_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FAVORITE_IDS = "key_favorite_ids"
        private const val KEY_LAST_AUDIO_ID = "key_last_audio_id"
        private const val KEY_LAST_POSITION_MS = "key_last_position_ms"
        private const val KEY_LOOP_MODE = "key_loop_mode"
        private const val KEY_PLAYBACK_SPEED = "key_playback_speed"
        private const val KEY_SORT_FIELD = "key_sort_field"
        private const val KEY_SORT_DIRECTION = "key_sort_direction"
        private const val KEY_SEGMENTS_PREFIX = "key_segments_audio_"
        private const val KEY_TRIM_PREFIX = "key_trim_audio_"
        private const val KEY_TRANSCRIPT_PREFIX = "key_transcript_audio_"

        // 超参数配置 Keys
        private const val KEY_ASR_CHUNK_SECONDS = "key_asr_chunk_seconds"
        private const val KEY_ENABLE_SLICING = "key_enable_slicing"
        private const val KEY_VAD_THRESHOLD = "key_vad_threshold"
        private const val KEY_VAD_MIN_SILENCE_SEC = "key_vad_min_silence_sec"
        private const val KEY_VAD_MIN_SPEECH_SEC = "key_vad_min_speech_sec"
        private const val KEY_VAD_MAX_SPEECH_SEC = "key_vad_max_speech_sec"
        private const val KEY_ASR_THREADS = "key_asr_threads"
        private const val KEY_ASR_LANGUAGE = "key_asr_language"
        private const val KEY_DEFAULT_EXPORT_FORMAT = "key_default_export_format"
        private const val KEY_IMPORTED_AUDIO_URIS = "key_imported_audio_uris"
        private const val KEY_LIBRARY_CACHE = "key_library_cache"
        private const val KEY_HIDDEN_FILE_PATHS = "key_hidden_file_paths"
    }

    // 0. 导入音频 URI 集合
    fun getImportedAudioUris(): Set<String> {
        return prefs.getStringSet(KEY_IMPORTED_AUDIO_URIS, emptySet()) ?: emptySet()
    }

    fun saveImportedAudioUris(uris: Set<String>) {
        prefs.edit { putStringSet(KEY_IMPORTED_AUDIO_URIS, uris) }
    }

    fun addImportedAudioUri(uri: String) {
        val current = getImportedAudioUris().toMutableSet()
        current.add(uri)
        saveImportedAudioUris(current)
    }

    fun removeImportedAudioUri(uri: String) {
        val current = getImportedAudioUris().toMutableSet()
        current.remove(uri)
        saveImportedAudioUris(current)
    }

    // 0.5 音频库列表缓存（避免每次打开音频库都重新扫描并解析元数据，保证「打开即可见」）
    fun saveAudioLibraryCache(audios: List<AudioItem>) {
        try {
            val arr = JSONArray()
            for (a in audios) {
                arr.put(
                    JSONObject().apply {
                        put("id", a.id)
                        put("title", a.title)
                        put("artist", a.artist)
                        put("durationMs", a.durationMs)
                        put("sizeBytes", a.sizeBytes)
                        put("filePath", a.filePath)
                        put("folderPath", a.folderPath)
                        put("folderName", a.folderName)
                        put("dateModifiedSec", a.dateModifiedSec)
                    }
                )
            }
            prefs.edit { putString(KEY_LIBRARY_CACHE, JSONObject().apply { put("audios", arr) }.toString()) }
        } catch (_: Exception) {
        }
    }

    fun getAudioLibraryCache(): List<AudioItem> {
        val raw = prefs.getString(KEY_LIBRARY_CACHE, null) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("audios") ?: return emptyList()
            val list = mutableListOf<AudioItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val filePath = obj.optString("filePath", "")
                list.add(
                    AudioItem(
                        id = obj.optLong("id", 0L),
                        title = obj.optString("title", "未知音频"),
                        artist = obj.optString("artist", "未知艺术家"),
                        durationMs = obj.optLong("durationMs", 0L),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        filePath = filePath,
                        folderPath = obj.optString("folderPath", ""),
                        folderName = obj.optString("folderName", ""),
                        dateModifiedSec = obj.optLong("dateModifiedSec", 0L),
                        contentUri = if (filePath.isNotBlank()) Uri.fromFile(File(filePath)) else null
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 0.6 「仅删除记录、保留原文件」时记录文件路径，避免下次扫描时重新出现在音频库
    fun getHiddenFilePaths(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_FILE_PATHS, emptySet()) ?: emptySet()
    }

    fun addHiddenFilePath(path: String) {
        if (path.isBlank()) return
        val current = getHiddenFilePaths().toMutableSet()
        current.add(path)
        prefs.edit { putStringSet(KEY_HIDDEN_FILE_PATHS, current) }
    }

    fun removeHiddenFilePath(path: String) {
        val current = getHiddenFilePaths().toMutableSet()
        if (current.remove(path)) {
            prefs.edit { putStringSet(KEY_HIDDEN_FILE_PATHS, current) }
        }
    }

    /** 清理已不存在文件的隐藏记录（文件被外部删除后无需再保留） */
    fun cleanupHiddenFilePaths() {
        val current = getHiddenFilePaths()
        if (current.isEmpty()) return
        val stale = mutableListOf<String>()
        current.forEach { path ->
            if (!File(path).exists()) stale.add(path)
        }
        if (stale.isEmpty()) return
        val remaining = current.toMutableSet()
        remaining.removeAll(stale)
        prefs.edit { putStringSet(KEY_HIDDEN_FILE_PATHS, remaining) }
    }

    // 1. 收藏音频 ID 集合
    fun getFavoriteIds(): Set<Long> {
        val set = prefs.getStringSet(KEY_FAVORITE_IDS, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun saveFavoriteIds(ids: Set<Long>) {
        prefs.edit { putStringSet(KEY_FAVORITE_IDS, ids.map { it.toString() }.toSet()) }
    }

    // 2. 最后播放音频与进度
    fun getLastPlayedAudioId(): Long? {
        val id = prefs.getLong(KEY_LAST_AUDIO_ID, -1L)
        return if (id != -1L) id else null
    }

    fun saveLastPlayedAudioId(id: Long?) {
        if (id != null) {
            prefs.edit { putLong(KEY_LAST_AUDIO_ID, id) }
        } else {
            prefs.edit { remove(KEY_LAST_AUDIO_ID) }
        }
    }

    fun getLastPlayedPositionMs(): Long {
        return prefs.getLong(KEY_LAST_POSITION_MS, 0L)
    }

    fun saveLastPlayedPositionMs(posMs: Long) {
        prefs.edit { putLong(KEY_LAST_POSITION_MS, posMs) }
    }

    // 3. 循环模式
    fun getLoopMode(): LoopMode {
        val name = prefs.getString(KEY_LOOP_MODE, LoopMode.SEQUENCE.name) ?: LoopMode.SEQUENCE.name
        return try {
            LoopMode.valueOf(name)
        } catch (e: Exception) {
            LoopMode.SEQUENCE
        }
    }

    fun saveLoopMode(mode: LoopMode) {
        prefs.edit { putString(KEY_LOOP_MODE, mode.name) }
    }

    // 4. 播放倍速
    fun getPlaybackSpeed(): Float {
        return prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
    }

    fun savePlaybackSpeed(speed: Float) {
        prefs.edit { putFloat(KEY_PLAYBACK_SPEED, speed) }
    }

    // 5. 排序与筛选偏好
    fun getSortField(): SortField {
        val name = prefs.getString(KEY_SORT_FIELD, SortField.DATE_MODIFIED.name) ?: SortField.DATE_MODIFIED.name
        return try {
            SortField.valueOf(name)
        } catch (e: Exception) {
            SortField.DATE_MODIFIED
        }
    }

    fun saveSortField(field: SortField) {
        prefs.edit { putString(KEY_SORT_FIELD, field.name) }
    }

    fun getSortDirection(): SortDirection {
        val name = prefs.getString(KEY_SORT_DIRECTION, SortDirection.DESCENDING.name) ?: SortDirection.DESCENDING.name
        return try {
            SortDirection.valueOf(name)
        } catch (e: Exception) {
            SortDirection.DESCENDING
        }
    }

    fun saveSortDirection(direction: SortDirection) {
        prefs.edit { putString(KEY_SORT_DIRECTION, direction.name) }
    }

    // 6. 音频片段持久化
    fun getSegmentsForAudio(audioId: Long): List<AudioSegment> {
        val jsonStr = prefs.getString("$KEY_SEGMENTS_PREFIX$audioId", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<AudioSegment>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AudioSegment(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        audioId = obj.optLong("audioId", audioId),
                        title = obj.optString("title", "片段"),
                        startMs = obj.optLong("startMs", 0L),
                        endMs = obj.optLong("endMs", 0L),
                        isSelected = obj.optBoolean("isSelected", true),
                        colorIndex = obj.optInt("colorIndex", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSegmentsForAudio(audioId: Long, segments: List<AudioSegment>) {
        try {
            val array = JSONArray()
            for (seg in segments) {
                val obj = JSONObject().apply {
                    put("id", seg.id)
                    put("audioId", seg.audioId)
                    put("title", seg.title)
                    put("startMs", seg.startMs)
                    put("endMs", seg.endMs)
                    put("isSelected", seg.isSelected)
                    put("colorIndex", seg.colorIndex)
                }
                array.put(obj)
            }
            prefs.edit { putString("$KEY_SEGMENTS_PREFIX$audioId", array.toString()) }
        } catch (_: Exception) {
        }
    }

    /**
     * 裁剪区间持久化（复用 AudioSegment 结构，与剪辑片段分开存储）
     */
    fun getTrimRangesForAudio(audioId: Long): List<AudioSegment> {
        val jsonStr = prefs.getString("$KEY_TRIM_PREFIX$audioId", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<AudioSegment>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AudioSegment(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        audioId = obj.optLong("audioId", audioId),
                        title = obj.optString("title", "裁剪段"),
                        startMs = obj.optLong("startMs", 0L),
                        endMs = obj.optLong("endMs", 0L),
                        isSelected = obj.optBoolean("isSelected", true),
                        colorIndex = obj.optInt("colorIndex", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTrimRangesForAudio(audioId: Long, ranges: List<AudioSegment>) {
        try {
            val array = JSONArray()
            for (seg in ranges) {
                val obj = JSONObject().apply {
                    put("id", seg.id)
                    put("audioId", seg.audioId)
                    put("title", seg.title)
                    put("startMs", seg.startMs)
                    put("endMs", seg.endMs)
                    put("isSelected", seg.isSelected)
                    put("colorIndex", seg.colorIndex)
                }
                array.put(obj)
            }
            prefs.edit { putString("$KEY_TRIM_PREFIX$audioId", array.toString()) }
        } catch (_: Exception) {
        }
    }

    // 7. 语音识别结果缓存
    fun getCachedTranscript(audioId: Long): TranscriptResult? {
        val jsonStr = prefs.getString("$KEY_TRANSCRIPT_PREFIX$audioId", null) ?: return null
        return try {
            val root = JSONObject(jsonStr)
            val fullText = root.optString("fullText", "")
            val durationMs = root.optLong("durationMs", 0L)
            val isCompleted = root.optBoolean("isCompleted", true)
            val processedDurationMs = root.optLong("processedDurationMs", durationMs)

            val wordsArray = root.optJSONArray("words") ?: JSONArray()
            val words = mutableListOf<TranscriptWord>()
            for (i in 0 until wordsArray.length()) {
                val wObj = wordsArray.getJSONObject(i)
                words.add(
                    TranscriptWord(
                        id = wObj.optLong("id", i.toLong()),
                        word = wObj.optString("word", ""),
                        startMs = wObj.optLong("startMs", 0L),
                        endMs = wObj.optLong("endMs", 0L)
                    )
                )
            }

            val sentencesArray = root.optJSONArray("sentences") ?: JSONArray()
            val sentences = mutableListOf<TranscriptSentence>()
            for (i in 0 until sentencesArray.length()) {
                val sObj = sentencesArray.getJSONObject(i)
                val sWordsArray = sObj.optJSONArray("words") ?: JSONArray()
                val sWords = mutableListOf<TranscriptWord>()
                for (j in 0 until sWordsArray.length()) {
                    val swObj = sWordsArray.getJSONObject(j)
                    sWords.add(
                        TranscriptWord(
                            id = swObj.optLong("id", j.toLong()),
                            word = swObj.optString("word", ""),
                            startMs = swObj.optLong("startMs", 0L),
                            endMs = swObj.optLong("endMs", 0L)
                        )
                    )
                }
                sentences.add(
                    TranscriptSentence(
                        id = sObj.optLong("id", i.toLong()),
                        text = sObj.optString("text", ""),
                        startMs = sObj.optLong("startMs", 0L),
                        endMs = sObj.optLong("endMs", 0L),
                        words = sWords
                    )
                )
            }

            val paragraphs = com.example.mp3player.asr.OfflineAsrEngine.buildParagraphsFromSentences(sentences)

            TranscriptResult(
                audioId = audioId,
                fullText = fullText,
                words = words,
                sentences = sentences,
                paragraphs = paragraphs,
                durationMs = durationMs,
                isCompleted = isCompleted,
                processedDurationMs = processedDurationMs
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveCachedTranscript(audioId: Long, result: TranscriptResult?) {
        if (result == null) {
            prefs.edit { remove("$KEY_TRANSCRIPT_PREFIX$audioId") }
            return
        }
        try {
            val root = JSONObject().apply {
                put("audioId", result.audioId)
                put("fullText", result.fullText)
                put("durationMs", result.durationMs)
                put("isCompleted", result.isCompleted)
                put("processedDurationMs", result.processedDurationMs)

                val wordsArray = JSONArray()
                for (w in result.words) {
                    val wObj = JSONObject().apply {
                        put("id", w.id)
                        put("word", w.word)
                        put("startMs", w.startMs)
                        put("endMs", w.endMs)
                    }
                    wordsArray.put(wObj)
                }
                put("words", wordsArray)

                val sentencesArray = JSONArray()
                for (s in result.sentences) {
                    val sObj = JSONObject().apply {
                        put("id", s.id)
                        put("text", s.text)
                        put("startMs", s.startMs)
                        put("endMs", s.endMs)
                        val sWordsArray = JSONArray()
                        for (sw in s.words) {
                            val swObj = JSONObject().apply {
                                put("id", sw.id)
                                put("word", sw.word)
                                put("startMs", sw.startMs)
                                put("endMs", sw.endMs)
                            }
                            sWordsArray.put(swObj)
                        }
                        put("words", sWordsArray)
                    }
                    sentencesArray.put(sObj)
                }
                put("sentences", sentencesArray)
            }
            prefs.edit { putString("$KEY_TRANSCRIPT_PREFIX$audioId", root.toString()) }
        } catch (_: Exception) {
        }
    }

    // 8. ASR与VAD超参数配置持久化
    fun getAsrChunkSeconds(): Int = prefs.getInt(KEY_ASR_CHUNK_SECONDS, 30) // 默认切片间隔 30 秒
    fun saveAsrChunkSeconds(sec: Int) = prefs.edit { putInt(KEY_ASR_CHUNK_SECONDS, sec) }

    fun getEnableSlicing(): Boolean = prefs.getBoolean(KEY_ENABLE_SLICING, true)
    fun saveEnableSlicing(enabled: Boolean) = prefs.edit { putBoolean(KEY_ENABLE_SLICING, enabled) }

    fun getVadThreshold(): Float = prefs.getFloat(KEY_VAD_THRESHOLD, 0.50f)
    fun saveVadThreshold(threshold: Float) = prefs.edit { putFloat(KEY_VAD_THRESHOLD, threshold) }

    fun getVadMinSilence(): Float = prefs.getFloat(KEY_VAD_MIN_SILENCE_SEC, 0.50f)
    fun saveVadMinSilence(sec: Float) = prefs.edit { putFloat(KEY_VAD_MIN_SILENCE_SEC, sec) }

    fun getVadMinSpeech(): Float = prefs.getFloat(KEY_VAD_MIN_SPEECH_SEC, 0.25f)
    fun saveVadMinSpeech(sec: Float) = prefs.edit { putFloat(KEY_VAD_MIN_SPEECH_SEC, sec) }

    fun getVadMaxSpeech(): Float = prefs.getFloat(KEY_VAD_MAX_SPEECH_SEC, 30.0f)
    fun saveVadMaxSpeech(sec: Float) = prefs.edit { putFloat(KEY_VAD_MAX_SPEECH_SEC, sec) }

    fun getAsrThreads(): Int = prefs.getInt(KEY_ASR_THREADS, 2)
    fun saveAsrThreads(threads: Int) = prefs.edit { putInt(KEY_ASR_THREADS, threads) }

    fun getAsrLanguage(): String = prefs.getString(KEY_ASR_LANGUAGE, "") ?: ""
    fun saveAsrLanguage(lang: String) = prefs.edit { putString(KEY_ASR_LANGUAGE, lang) }

    fun getDefaultExportFormat(): String = prefs.getString(KEY_DEFAULT_EXPORT_FORMAT, "M4A") ?: "M4A"
    fun saveDefaultExportFormat(formatName: String) = prefs.edit { putString(KEY_DEFAULT_EXPORT_FORMAT, formatName) }

    fun clearAllTranscripts() {
        prefs.edit {
            prefs.all.keys.forEach { key ->
                if (key.startsWith(KEY_TRANSCRIPT_PREFIX)) {
                    remove(key)
                }
            }
        }
    }

    /**
     * 删除指定音频的所有关联数据（文稿、片段、收藏状态）
     */
    fun deleteAudioData(audioId: Long) {
        prefs.edit {
            remove("$KEY_TRANSCRIPT_PREFIX$audioId")
            remove("$KEY_SEGMENTS_PREFIX$audioId")
            remove("$KEY_TRIM_PREFIX$audioId")

            // 从收藏夹移除
            val favorites = getFavoriteIds().toMutableSet()
            if (favorites.contains(audioId)) {
                favorites.remove(audioId)
                putStringSet(KEY_FAVORITE_IDS, favorites.map { it.toString() }.toSet())
            }
        }
    }
}
