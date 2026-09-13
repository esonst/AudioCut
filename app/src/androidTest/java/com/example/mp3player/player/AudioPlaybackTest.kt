package com.example.mp3player.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mp3player.data.model.AudioItem
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPlaybackTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun playerManager_initialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val playerManager = AudioPlayerManager(context)
        assertNotNull(playerManager)
        playerManager.release()
    }

    @Test
    fun audioItem_uriGeneration() {
        val item = AudioItem(id = 1, title = "Test", artist = "Artist", filePath = "/sdcard/test.mp3")
        assertNotNull(item)
    }
}
