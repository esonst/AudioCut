package com.example.mp3player.navigation

/**
 * 应用导航页面定义（支持音乐库-文稿-剪辑-裁剪-格式转换-设置 六屏左右滑动切换）
 */
enum class AppScreen(val pageIndex: Int) {
    AUDIO_LIBRARY(0), // 音乐库
    TRANSCRIPT(1),    // 文稿
    CLIP(2),          // 剪辑
    TRIM(3),          // 裁剪（删除所选部分）
    CONVERT(4),       // 格式转换
    SETTINGS(5);      // 设置

    companion object {
        fun fromIndex(index: Int): AppScreen = when (index) {
            0 -> AUDIO_LIBRARY
            1 -> TRANSCRIPT
            2 -> CLIP
            3 -> TRIM
            4 -> CONVERT
            5 -> SETTINGS
            else -> AUDIO_LIBRARY
        }
    }
}

/**
 * 顶部 Tab 栏定义（文稿、剪辑 两个界面）
 */
enum class PlayerTab(val pageIndex: Int) {
    TRANSCRIPT(1), // 文稿
    CLIP(2);       // 剪辑

    companion object {
        fun fromIndex(index: Int): PlayerTab = when (index) {
            1 -> TRANSCRIPT
            2 -> CLIP
            else -> TRANSCRIPT
        }
    }
}
