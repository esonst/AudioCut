package com.example.AudioCut.data.model

/**
 * 排序字段
 */
enum class SortField(val displayName: String) {
    NAME("名称"),
    SIZE("大小"),
    DURATION("时长"),
    DATE_MODIFIED("修改时间")
}

/**
 * 排序方向
 */
enum class SortDirection(val displayName: String) {
    ASCENDING("升序 ↑"),
    DESCENDING("降序 ↓")
}

/**
 * 浏览模式：全部音频 / 我的收藏
 */
enum class BrowseMode(val displayName: String) {
    ALL("全部音频"),
    FAVORITES("我的收藏")
}

/**
 * 时长筛选区间
 */
enum class DurationFilter(val displayName: String, val minMs: Long, val maxMs: Long) {
    ALL("全部时长", 0L, Long.MAX_VALUE),
    LESS_THAN_1_MIN("< 1分钟", 0L, 60_000L),
    FROM_1_TO_5_MIN("1 - 5分钟", 60_000L, 300_000L),
    FROM_5_TO_20_MIN("5 - 20分钟", 300_000L, 1_200_000L),
    GREATER_THAN_20_MIN("> 20分钟", 1_200_000L, Long.MAX_VALUE)
}

/**
 * 大小筛选区间
 */
enum class SizeFilter(val displayName: String, val minBytes: Long, val maxBytes: Long) {
    ALL("全部大小", 0L, Long.MAX_VALUE),
    LESS_THAN_5_MB("< 5MB", 0L, 5 * 1024 * 1024L),
    FROM_5_TO_20_MB("5 - 20MB", 5 * 1024 * 1024L, 20 * 1024 * 1024L),
    FROM_20_TO_50_MB("20 - 50MB", 20 * 1024 * 1024L, 50 * 1024 * 1024L),
    GREATER_THAN_50_MB("> 50MB", 50 * 1024 * 1024L, Long.MAX_VALUE)
}
