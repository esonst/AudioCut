package com.example.audiocut.asr

import com.example.audiocut.data.model.TranscriptWord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PunctuationSegmenter.mergePunctuationToWordsFixed] 单元测试。
 *
 * 思路：取一段真实转写文本（测试值），去掉标点后模拟 ASR 的 sourceWords，
 * 再把原始带标点文本当作"标点模型完美恢复"的输出 punctuatedFullText，
 * 验证合并后所有词拼接仍能按正确顺序还原完整文本。
 *
 * mergePunctuationToWordsFixed 为 private，这里通过反射调用。
 */
class PunctuationSegmenterMergeTest {

    companion object {
        /** 测试值：原始带标点转写文本 */
        private const val TEST_TEXT =
            "今天要做这个D一阶流动态的介绍流动态，这是开始最基础的一个状态，其实也就是最困难的一个状态。" +
                "要切。这个状态其实是比较困难。一阶这个流动态如果能切度成功了，后面几个相对的来说，困难度会比较低。" +
                "我们要知道，我们每分每秒所接触到的所有情境，无一例外，全都是我们内在所显化出来，" +
                "通常我们总是显化出一成不变的情境。原因就是我们会一直将我们的注意力投注在过去所发生的情境上，这代表。"

        /** 与 PunctuationSegmenter.allPunctuationSet 一致的标点集合（该集合为 private，测试侧自备一份） */
        private val punctuationSet = setOf(
            '，', '。', '！', '？', '；', '：', '、',
            ',', '.', '!', '?', ';', ':', '…',
            '“', '”', '‘', '’'
        )
    }

    /** 去掉文本中的全部标点 */
    private fun stripPunctuation(text: String): String = text.filterNot { it in punctuationSet }

    /**
     * 用去掉标点后的纯文本模拟 ASR 词语输出：
     * 按 chunkSize 个字符一组切词（模拟逐字 / 多字词两种情况），时间戳单调递增。
     */
    private fun buildSourceWords(plain: String, chunkSize: Int): List<TranscriptWord> =
        plain.chunked(chunkSize).mapIndexed { index, chunk ->
            TranscriptWord(
                id = index.toLong(),
                word = chunk,
                startMs = index * 300L,
                endMs = index * 300L + chunk.length * 100L
            )
        }

    /** 反射调用 private 方法 mergePunctuationToWordsFixed */
    @Suppress("UNCHECKED_CAST")
    private fun invokeMerge(
        sourceWords: List<TranscriptWord>,
        punctuatedFullText: String
    ): List<TranscriptWord> {
        val method = PunctuationSegmenter::class.java.getDeclaredMethod(
            "mergePunctuationToWordsFixed",
            List::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(PunctuationSegmenter(), sourceWords, punctuatedFullText)
            as List<TranscriptWord>
    }

    /** 逐字分词：合并后拼接必须完整还原原始带标点文本 */
    @Test
    fun merge_singleCharWords_restoresFullTextInOrder() {
        val plain = stripPunctuation(TEST_TEXT)
        val sourceWords = buildSourceWords(plain, 1)

        val merged = invokeMerge(sourceWords, TEST_TEXT)

        // 词数不变
        assertEquals(sourceWords.size, merged.size)
        // 顺序 + 标点完整还原
        assertEquals(TEST_TEXT, merged.joinToString("") { it.word })
    }

    /** 多字分词：一个词含多个字符时，标点仍要挂到正确词尾，拼接还原完整文本 */
//    @Test
//    fun merge_multiCharWords_restoresFullTextInOrder() {
//        val plain = stripPunctuation(TEST_TEXT)
//        val sourceWords = buildSourceWords(plain, 3)
//
//        val merged = invokeMerge(sourceWords, TEST_TEXT)
//
//        assertEquals(sourceWords.size, merged.size)
//        assertEquals(TEST_TEXT, merged.joinToString("") { it.word })
//    }

    /** 每个词：时间戳完全不变，去标点后的纯文本与原词一致（顺序不错位） */
//    @Test
//    fun merge_keepsTimestamps_andPlainTextPerWord() {
//        val plain = stripPunctuation(TEST_TEXT)
//        val sourceWords = buildSourceWords(plain, 2)
//
//        val merged = invokeMerge(sourceWords, TEST_TEXT)
//
//        merged.forEachIndexed { idx, w ->
//            assertEquals("第 $idx 个词 startMs 不应被修改", sourceWords[idx].startMs, w.startMs)
//            assertEquals("第 $idx 个词 endMs 不应被修改", sourceWords[idx].endMs, w.endMs)
//            assertEquals("第 $idx 个词的纯文本应保持原样且顺序不变", sourceWords[idx].word, w.word.filterNot { it in punctuationSet })
//        }
//        // 整体纯文本 == 去标点文本（无丢字、无改序）
//        assertEquals(
//            plain,
//            merged.joinToString("") { it.word }.filterNot { it in punctuationSet }
//        )
//    }

//    /** 边界：文本最开头的标点应挂到第一个词且不越界；空输入直接返回空列表 */
//    @Test
//    fun merge_toleratesLeadingPunctuation_andEmptyInput() {
//        val plain = stripPunctuation(TEST_TEXT)
//        val sourceWords = buildSourceWords(plain, 4)
//
//        // 模型在最开头多吐了一个句号：不应抛异常，开头标点挂在第一个词
//        val merged = invokeMerge(sourceWords, "。" + TEST_TEXT)
//        assertEquals(sourceWords.size, merged.size)
//        assertEquals("。" + TEST_TEXT, merged.joinToString("") { it.word })
//
//        // 空输入
//        assertEquals(0, invokeMerge(emptyList(), TEST_TEXT).size)
//    }
}
