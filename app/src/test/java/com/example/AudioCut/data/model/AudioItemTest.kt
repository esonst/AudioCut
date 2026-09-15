package com.example.audiocut.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioItemTest {

    @Test
    fun formatDuration_worksCorrectly() {
        assertEquals("00:00", AudioItem.formatDuration(0))
        assertEquals("00:05", AudioItem.formatDuration(5000))
        assertEquals("01:00", AudioItem.formatDuration(60000))
        assertEquals("10:00", AudioItem.formatDuration(600000))
    }

    @Test
    fun formatFileSize_worksCorrectly() {
        assertEquals("0 B", AudioItem.formatFileSize(0))
        assertEquals("1.00 MB", AudioItem.formatFileSize(1024 * 1024))
        assertEquals("512.0 KB", AudioItem.formatFileSize(512 * 1024))
    }
}
