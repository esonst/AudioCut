package com.example.mp3player.data.repository

import com.example.mp3player.data.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRepositoryTest {

    private val repository = AudioRepository(null)

    private val sampleAudios = listOf(
        AudioItem(id = 1, title = "Song A", artist = "Artist X", durationMs = 100000, sizeBytes = 1000000, folderName = "Folder 1"),
        AudioItem(id = 2, title = "Song B", artist = "Artist Y", durationMs = 200000, sizeBytes = 2000000, folderName = "Folder 2"),
        AudioItem(id = 3, title = "Track C", artist = "Artist X", durationMs = 300000, sizeBytes = 3000000, folderName = "Folder 1")
    )

    @Test
    fun filterAudios_byQuery() {
        val result = repository.filterAudios(sampleAudios, query = "Song")
        assertEquals(2, result.size)
        assertEquals("Song A", result[0].title)
        assertEquals("Song B", result[1].title)
    }

    @Test
    fun filterAudios_byArtist() {
        val result = repository.filterAudios(sampleAudios, query = "Artist X")
        assertEquals(2, result.size)
    }

    @Test
    fun sortAudios_byNameAsc() {
        val result = repository.sortAudios(sampleAudios, SortField.NAME, SortDirection.ASCENDING)
        assertEquals("Song A", result[0].title)
        assertEquals("Song B", result[1].title)
        assertEquals("Track C", result[2].title)
    }

    @Test
    fun sortAudios_byDurationDesc() {
        val result = repository.sortAudios(sampleAudios, SortField.DURATION, SortDirection.DESCENDING)
        assertEquals("Track C", result[0].title)
        assertEquals("Song B", result[1].title)
        assertEquals("Song A", result[2].title)
    }

    @Test
    fun groupByFolder() {
        val result = repository.groupByFolder(sampleAudios)
        assertEquals(2, result.size)
        assertEquals(2, result["Folder 1"]?.size)
        assertEquals(1, result["Folder 2"]?.size)
    }
}
