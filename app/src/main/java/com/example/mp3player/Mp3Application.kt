package com.example.mp3player

import android.app.Application
import com.example.mp3player.utils.CrashHandler

class Mp3Application : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 初始化全局异常处理器
        CrashHandler(this)
    }

    companion object {
        lateinit var instance: Mp3Application
            private set
    }
}
