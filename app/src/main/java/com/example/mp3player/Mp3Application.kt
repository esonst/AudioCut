package com.example.mp3player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.mp3player.di.AppContainer

class Mp3Application : Application() {
    companion object {
        const val CHANNEL_ID = "audio_playback_channel"
        const val CHANNEL_NAME = "音频播放控制"
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "音频播放控制通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
