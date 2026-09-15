package com.example.AudioCut

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.AudioCut.di.AppContainer

class AudioCutApplication : Application() {
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
}
