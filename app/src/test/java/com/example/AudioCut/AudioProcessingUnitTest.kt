package com.example.AudioCut

import com.example.AudioCut.data.model.*
import com.example.AudioCut.data.repository.AudioRepository
import com.example.AudioCut.ffmpeg.ExportAudioFormat
import org.junit.Assert.*
import org.junit.Test

class AudioProcessingUnitTest {

    @Test
    fun testAudioItemFormatting() {
        assertEquals("00:00", AudioItem.formatDuration(0L))
        assertEquals("01:15", AudioItem.formatDuration(75_000L))
        assertEquals("10:05", AudioItem.formatDuration(605_000L))

        assertEquals("0 B", AudioItem.formatFileSize(0L))
        assertEquals("500.0 KB", AudioItem.formatFileSize(500 * 1024L))
        assertEquals("4.50 MB", AudioItem.formatFileSize((4.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testAudioSegmentCalculations() {
        val segment = AudioSegment(
            id = "seg-1",
            audioId = 100L,
            title = "重点段落",
            startMs = 10_000L,
            endMs = 25_000L,
            isSelected = true
        )

        assertEquals(15_000L, segment.durationMs)
        assertEquals("00:10 ~ 00:25", segment.formattedRange)
    }

    @Test
    fun testTranscriptModelStructures() {
        val word1 = TranscriptWord(1L, "你", 0L, 200L)
        val word2 = TranscriptWord(2L, "好", 200L, 500L)
        val sentence1 = TranscriptSentence(1L, "你好。", 0L, 500L, listOf(word1, word2))

        val word3 = TranscriptWord(3L, "世", 600L, 800L)
        val word4 = TranscriptWord(4L, "界", 800L, 1000L)
        val sentence2 = TranscriptSentence(2L, "世界。", 600L, 1000L, listOf(word3, word4))

        val result = TranscriptResult(
            audioId = 1L,
            fullText = "你好。\n世界。",
            words = listOf(word1, word2, word3, word4),
            sentences = listOf(sentence1, sentence2),
            durationMs = 1000L
        )

        assertEquals(2, result.sentences.size)
        assertEquals("你好。", result.sentences[0].text)
        assertEquals("世界。", result.sentences[1].text)
        assertEquals(0L, result.sentences[0].startMs)
        assertEquals(1000L, result.sentences[1].endMs)
    }

    @Test
    fun testMultiSentenceSegmentCreationLogic() {
        val word1 = TranscriptWord(1L, "第一句开端", 0L, 1000L)
        val word2 = TranscriptWord(2L, "第一句结尾", 1000L, 2000L)
        val sentence1 = TranscriptSentence(1L, "第一句开端第一句结尾。", 0L, 2000L, listOf(word1, word2))

        val word3 = TranscriptWord(3L, "第二句开端", 3000L, 4000L)
        val word4 = TranscriptWord(4L, "第二句结尾", 4000L, 5000L)
        val sentence2 = TranscriptSentence(2L, "第二句开端第二句结尾。", 3000L, 5000L, listOf(word3, word4))

        val sentences = listOf(sentence1, sentence2)
        val selectedWordIds = setOf(1L, 3L) // 跨两句选中

        val createdSegments = mutableListOf<AudioSegment>()
        for (sentence in sentences) {
            val selectedInSentence = sentence.words.filter { selectedWordIds.contains(it.id) }.sortedBy { it.startMs }
            if (selectedInSentence.isNotEmpty()) {
                val sStart = selectedInSentence.first().startMs
                val sEnd = selectedInSentence.last().endMs
                val textSnippet = selectedInSentence.joinToString("") { it.word }.take(12)
                createdSegments.add(
                    AudioSegment(
                        audioId = 10L,
                        title = "片段${createdSegments.size + 1}: $textSnippet",
                        startMs = sStart,
                        endMs = sEnd
                    )
                )
            }
        }

        assertEquals(2, createdSegments.size)
        assertEquals(0L, createdSegments[0].startMs)
        assertEquals(1000L, createdSegments[0].endMs)
        assertEquals(3000L, createdSegments[1].startMs)
        assertEquals(4000L, createdSegments[1].endMs)
    }

    @Test
    fun testExportAudioFormats() {
        assertEquals("m4a", ExportAudioFormat.M4A.extension)
        assertEquals("wav", ExportAudioFormat.WAV.extension)
        assertEquals("mp3", ExportAudioFormat.MP3.extension)
    }

    @Test
    fun testFilterAndSortLogic() {
        val repo = AudioRepository()
        val mockList = listOf(
            AudioItem(id = 1, title = "Song B", artist = "Artist 1", durationMs = 120_000, sizeBytes = 3 * 1024 * 1024, dateModifiedSec = 1000),
            AudioItem(id = 2, title = "Song A", artist = "Artist 2", durationMs = 30_000, sizeBytes = 10 * 1024 * 1024, dateModifiedSec = 2000),
            AudioItem(id = 3, title = "Song C", artist = "Artist 3", durationMs = 400_000, sizeBytes = 1 * 1024 * 1024, dateModifiedSec = 500)
        )

        // 搜索过滤
        val filtered = repo.filterAudios(mockList, query = "Song A")
        assertEquals(1, filtered.size)
        assertEquals("Song A", filtered[0].title)

        // 时长过滤 (< 1分钟)
        val shortAudios = repo.filterAudios(mockList, durationFilter = DurationFilter.LESS_THAN_1_MIN)
        assertEquals(1, shortAudios.size)
        assertEquals("Song A", shortAudios[0].title)

        // 排序：名称升序
        val sortedByName = repo.sortAudios(mockList, SortField.NAME, SortDirection.ASCENDING)
        assertEquals("Song A", sortedByName[0].title)
        assertEquals("Song B", sortedByName[1].title)
        assertEquals("Song C", sortedByName[2].title)

        // 排序：修改时间降序
        val sortedByDateDesc = repo.sortAudios(mockList, SortField.DATE_MODIFIED, SortDirection.DESCENDING)
        assertEquals(2L, sortedByDateDesc[0].id)
        assertEquals(1L, sortedByDateDesc[1].id)
        assertEquals(3L, sortedByDateDesc[2].id)
    }
    @Test
    fun testAppScreenAndPlayerTabIndices() {
        assertEquals(0, com.example.AudioCut.navigation.AppScreen.AUDIO_LIBRARY.pageIndex)
        assertEquals(1, com.example.AudioCut.navigation.AppScreen.TRANSCRIPT.pageIndex)
        assertEquals(2, com.example.AudioCut.navigation.AppScreen.CLIP.pageIndex)
        assertEquals(3, com.example.AudioCut.navigation.AppScreen.TRIM.pageIndex)
        assertEquals(4, com.example.AudioCut.navigation.AppScreen.CONVERT.pageIndex)
        assertEquals(5, com.example.AudioCut.navigation.AppScreen.SETTINGS.pageIndex)

        assertEquals(com.example.AudioCut.navigation.AppScreen.AUDIO_LIBRARY, com.example.AudioCut.navigation.AppScreen.fromIndex(0))
        assertEquals(com.example.AudioCut.navigation.AppScreen.TRANSCRIPT, com.example.AudioCut.navigation.AppScreen.fromIndex(1))
        assertEquals(com.example.AudioCut.navigation.AppScreen.CLIP, com.example.AudioCut.navigation.AppScreen.fromIndex(2))
        assertEquals(com.example.AudioCut.navigation.AppScreen.TRIM, com.example.AudioCut.navigation.AppScreen.fromIndex(3))
        assertEquals(com.example.AudioCut.navigation.AppScreen.CONVERT, com.example.AudioCut.navigation.AppScreen.fromIndex(4))
        assertEquals(com.example.AudioCut.navigation.AppScreen.SETTINGS, com.example.AudioCut.navigation.AppScreen.fromIndex(5))

        assertEquals(com.example.AudioCut.navigation.PlayerTab.TRANSCRIPT, com.example.AudioCut.navigation.PlayerTab.fromIndex(1))
        assertEquals(com.example.AudioCut.navigation.PlayerTab.CLIP, com.example.AudioCut.navigation.PlayerTab.fromIndex(2))
    }


    @Test
    fun testOfflineAsrParseTextToWords() {
        val mixedText = "你好 世界 hello world 这是一个测试。"
        val words = com.example.AudioCut.asr.OfflineAsrEngine.parseTextToWords(mixedText)
        assertTrue(words.contains("你好") || (words.contains("你") && words.any { it.startsWith("好") }))
        assertTrue(words.contains("hello"))
        assertTrue(words.contains("world"))
        assertTrue(words.any { it.contains("测试。") || it.contains("。") })
    }

    @Test
    fun testOfflineAsrBuildSentencesFromWordsWithPause() {
        // 第一句：0~400ms
        val word1 = TranscriptWord(1L, "你", 0L, 200L)
        val word2 = TranscriptWord(2L, "好", 200L, 400L)
        // 停顿 600ms (1000ms - 400ms = 600ms，智能添加逗号)
        // 第二句词：1000~1400ms，末尾停顿超过 650ms，智能添加句号
        val word3 = TranscriptWord(3L, "欢", 1000L, 1200L)
        val word4 = TranscriptWord(4L, "迎", 1200L, 1400L)

        val words = listOf(word1, word2, word3, word4)
        val sentences = com.example.AudioCut.asr.OfflineAsrEngine.buildSentencesFromWords(words, 3000L)

        assertTrue(sentences.isNotEmpty())
        val fullJoined = sentences.joinToString("") { it.text }
        assertTrue("Expected fullJoined to contain commas and periods: $fullJoined", fullJoined.contains("，") || fullJoined.contains("。"))
        assertTrue(fullJoined.startsWith("你好"))
        assertTrue(fullJoined.contains("欢迎"))
    }

    @Test
    fun testOfflineAsrBuildSentencesWithPunctuation() {
        val word1 = TranscriptWord(1L, "你", 0L, 200L)
        val word2 = TranscriptWord(2L, "好。", 200L, 500L)
        val word3 = TranscriptWord(3L, "新", 550L, 750L)
        val word4 = TranscriptWord(4L, "一", 750L, 950L)
        val word5 = TranscriptWord(5L, "天。", 950L, 1150L)

        val words = listOf(word1, word2, word3, word4, word5)
        val sentences = com.example.AudioCut.asr.OfflineAsrEngine.buildSentencesFromWords(words, 2000L)

        assertEquals(2, sentences.size)
        assertEquals("你好。", sentences[0].text)
        assertEquals("新一天。", sentences[1].text)
        assertFalse("Should not have duplicate periods", sentences[0].text.contains("。。"))
    }

    @Test
    fun testSenseVoiceTokenTimestampAndPunctuationProcessing() {
        val tokens = arrayOf("<|zh|>", "<|NEUTRAL|>", "<|Speech|>", "<|woitn|>", "你", "好", "，", "世", "界", "。")
        val timestamps = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.10f, 0.30f, 0.50f, 0.80f, 1.00f, 1.20f)
        val durations = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.18f, 0.18f, 0.10f, 0.18f, 0.18f, 0.10f)

        val words = com.example.AudioCut.asr.OfflineAsrEngine.processSenseVoiceResult(
            text = "你好，世界。",
            tokens = tokens,
            timestamps = timestamps,
            durations = durations,
            totalDurationMs = 2000L
        )

        assertNotNull(words)
        assertEquals(4, words!!.size)
        assertEquals("你", words[0].word)
        assertEquals("好，", words[1].word)
        assertEquals("世", words[2].word)
        assertEquals("界。", words[3].word)

        assertEquals(100L, words[0].startMs)
        assertEquals(300L, words[1].startMs)
        assertEquals(800L, words[2].startMs)
        assertEquals(1000L, words[3].startMs)

        // 验证句子生成
        val sentences = com.example.AudioCut.asr.OfflineAsrEngine.buildSentencesFromWords(words, 2000L)
        assertEquals(1, sentences.size)
        assertEquals("你好，世界。", sentences[0].text)
    }

    @Test
    fun testSenseVoiceFallbackTextParsing() {
        val text = "今天天气真好，我们一起出去玩吧！"
        val words = com.example.AudioCut.asr.OfflineAsrEngine.processSenseVoiceResult(
            text = text,
            tokens = emptyArray(),
            timestamps = floatArrayOf(),
            durations = floatArrayOf(),
            totalDurationMs = 5000L
        )

        assertNotNull(words)
        assertTrue(words!!.isNotEmpty())
        val joined = words.joinToString("") { it.word }
        assertEquals("今天天气真好，我们一起出去玩吧！", joined)

        val sentences = com.example.AudioCut.asr.OfflineAsrEngine.buildSentencesFromWords(words, 5000L)
        assertTrue(sentences.isNotEmpty())
        assertEquals("今天天气真好，我们一起出去玩吧！", sentences[0].text)
    }

    @Test
    fun testChunkingAndGlobalTimestampConversion() {
        // 模拟第 2 个 60s 分片：
        // 逻辑切片：60000ms ~ 120000ms
        // 强制向前冗余 1s：actualStartMs = 59000ms, actualEndMs = 120000ms
        val segmentBaseTimeMs = 59000L
        val sliceStartMs = 60000L
        val sliceEndMs = 120000L
        val totalDurationMs = 180000L

        // 分片内部相对时间（秒）
        // 0.2s 处（对应全局 59200ms < 60000ms，属于上一分片的冗余缓冲部分，应被过滤）
        // 1.5s 处（对应全局 60500ms，在当前分片内，应保留）
        // 2.0s 处（对应全局 61000ms，在当前分片内，应保留）
        val tokens = arrayOf("冗", "正", "确")
        val timestamps = floatArrayOf(0.2f, 1.5f, 2.0f)
        val durations = floatArrayOf(0.3f, 0.4f, 0.4f)

        val words = com.example.AudioCut.asr.OfflineAsrEngine.processSenseVoiceResult(
            text = "正确",
            tokens = tokens,
            timestamps = timestamps,
            durations = durations,
            segmentBaseTimeMs = segmentBaseTimeMs,
            sliceStartMs = sliceStartMs,
            sliceEndMs = sliceEndMs,
            totalDurationMs = totalDurationMs
        )

        assertNotNull(words)
        assertEquals(2, words!!.size)
        assertEquals("正", words[0].word)
        assertEquals(60500L, words[0].startMs)
        assertEquals(60900L, words[0].endMs)

        assertEquals("确", words[1].word)
        assertEquals(61000L, words[1].startMs)
        assertEquals(61400L, words[1].endMs)
    }

    @Test
    fun testFirstChunkBoundaryPaddingAtZero() {
        // 模拟第 1 个分片（音频开头不足 1s，向前 padding 最多到 0）
        val sliceStartMs = 0L
        val redundancyMs = 1000L
        val actualStartMs = maxOf(0L, sliceStartMs - redundancyMs)
        assertEquals(0L, actualStartMs)

        val tokens = arrayOf("开", "始")
        val timestamps = floatArrayOf(0.1f, 0.5f)
        val durations = floatArrayOf(0.3f, 0.3f)

        val words = com.example.AudioCut.asr.OfflineAsrEngine.processSenseVoiceResult(
            text = "开始",
            tokens = tokens,
            timestamps = timestamps,
            durations = durations,
            segmentBaseTimeMs = actualStartMs,
            sliceStartMs = sliceStartMs,
            sliceEndMs = 60000L,
            totalDurationMs = 120000L
        )

        assertNotNull(words)
        assertEquals(2, words!!.size)
        assertEquals(100L, words[0].startMs)
        assertEquals(400L, words[0].endMs)
        assertEquals(500L, words[1].startMs)
        assertEquals(800L, words[1].endMs)
    }
}
