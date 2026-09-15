package com.example.AudioCut.data.model

/**
 * 播放器循环模式
 */
enum class LoopMode(val displayName: String) {
    SEQUENCE("顺序播放"),
    REPEAT_ONE("单曲循环"),
    REPEAT_ALL("列表循环"),
    SHUFFLE("随机播放")
}
