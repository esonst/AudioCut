package com.example.AudioCut.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文稿段首时间戳格式与行长度的单元测试
 */
class TranscriptTimestampTest {

    @Test
    fun zero_rendersMidnight() {
        assertEquals("[00:00:00]", formatTranscriptTimestamp(0L))
    }

    @Test
    fun plainSeconds() {
        assertEquals("[00:00:05]", formatTranscriptTimestamp(5_000L))
    }

    @Test
    fun minutesAndSeconds() {
        assertEquals("[00:01:01]", formatTranscriptTimestamp(61_000L))
    }

    @Test
    fun fullHour() {
        assertEquals("[01:00:00]", formatTranscriptTimestamp(3_600_000L))
    }

    @Test
    fun mixedHms() {
        assertEquals(
            "[02:03:04]",
            formatTranscriptTimestamp(2L * 3_600_000L + 3L * 60_000L + 4_000L)
        )
    }

    @Test
    fun longDurationOverOneHour() {
        assertEquals("[01:23:45]", formatTranscriptTimestamp((3_600L + 23L * 60L + 45L) * 1000L))
    }

    @Test
    fun negativeClampedToZero() {
        assertEquals("[00:00:00]", formatTranscriptTimestamp(-1L))
    }

    @Test
    fun lineLengthIncludesNewline() {
        // "[00:01:01]" = 10 字符 + 换行 1 字符
        assertEquals(11, transcriptTimestampLineLength(61_000L))
    }
}
