package com.example.audiocut.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PunctuationSegmenter.rePunctuateChunked] 单元测试。
 *
 * 验证「单次文本数量 + 固定重叠窗口」的分片合并语义（不加载原生模型，用模拟标点函数）：
 * - 非末片只保留前 (片长 - 重叠) 个非标点字符
 * - 重叠区不重复、不丢失，最终拼接完整还原原文
 * - 末片全量保留
 */
class PunctuationSegmenterWindowTest {

    /** 模拟标点模型原样返回：任何分片方式都必须完整还原原文（不重复、不丢字） */
    @Test
    fun identityPunctuator_preservesTextExactly() {
        val text = "今天天气很好我们一起去公园散步非常开心" // 20 字
        val result = PunctuationSegmenter().rePunctuateChunked(
            text = text,
            chunkChars = 12,
            overlapChars = 4,
            punctuate = { it }
        )
        assertEquals(text, result)
    }

    /** 每片末尾追加句号：非末片丢弃重叠区及边界标点，重叠区只在下一片出现一次 */
    @Test
    fun overlappingChunks_doNotDuplicateOrDropText() {
        val text = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸" // 20 字
        var callCount = 0
        val result = PunctuationSegmenter().rePunctuateChunked(
            text = text,
            chunkChars = 12,
            overlapChars = 4,
            punctuate = { chunk ->
                callCount++
                chunk + "。"
            }
        )
        // 片1 [0,12) 片2 [8,20)：共 2 次模型调用
        assertEquals(2, callCount)
        // 片1 保留前 8 个非标点字符，片2（末片）全量保留 → 原文 + 句号，无重复
        assertEquals(text + "。", result)
    }

    /** 长文本：步进 = 单次数量 - 重叠，连续多片拼接同样无重复无丢失 */
    @Test
    fun longText_slidingWindows_preserveFullText() {
        val text = "甲乙丙丁戊己庚辛壬癸".repeat(10) // 100 字
        val result = PunctuationSegmenter().rePunctuateChunked(
            text = text,
            chunkChars = 30,
            overlapChars = 5,
            punctuate = { it + "，" }
        )
        // 非末片边界标点被丢弃，末片全保留 → 恰好原文 + 一个句末标点
        assertEquals(text + "，", result)
    }

    /** 文本不足一片：只调用一次模型，完整保留输出 */
    @Test
    fun shortText_singleChunk_keepsEverything() {
        val text = "很短的一句话"
        var callCount = 0
        val result = PunctuationSegmenter().rePunctuateChunked(
            text = text,
            chunkChars = 1000,
            overlapChars = 20,
            punctuate = { chunk ->
                callCount++
                chunk + "。"
            }
        )
        assertEquals(1, callCount)
        assertEquals(text + "。", result)
    }

    /** 重叠为 0（兼容旧分片路径）：非末片同样丢弃边界标点，由下一片重新决定 */
    @Test
    fun zeroOverlap_dropsBoundaryPunctuationOnly() {
        val text = "今天天气很好我们一起去公园散步非常开心".repeat(3) // 60 字
        val result = PunctuationSegmenter().rePunctuateChunked(
            text = text,
            chunkChars = 20,
            overlapChars = 0,
            punctuate = { it + "，" }
        )
        assertEquals(text + "，", result)
    }
}
