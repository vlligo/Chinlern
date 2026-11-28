package com.example.chinlern

import com.example.chinlern.dictionary.Pinyin
import com.example.chinlern.texts.ReaderWords
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderWordsTest {
    @Test fun findsLongestFormsAndKeepsSupplementaryAndCombiningOffsets() {
        val text = "我去圖書館。𠀀\uFE00🙂电脑。"
        val boundaries = intArrayOf(0, 1, 2, 3, 4, 5, 6, 9, 11, 12, 13, 14)
        val entries = setOf("圖書", "圖書館", "电脑", "電腦")
        val spans = ReaderWords.segment(text, boundaries) { prefix -> entries.filter { it.startsWith(prefix) }.toSet() }
        assertEquals(listOf("我", "去", "圖書館", "𠀀\uFE00", "电脑"), spans.map { text.substring(it.start, it.end) })
    }

    @Test fun marksTonesOnTheCorrectVowel() {
        assertEquals("túshūguǎn", Pinyin.marked("tu2 shu1 guan3"))
        assertEquals("lüè", Pinyin.marked("lu:e4"))
        assertEquals("liú", Pinyin.marked("liu2"))
        assertEquals("shuǐ", Pinyin.marked("shui3"))
        assertEquals("Xī'ān", Pinyin.marked("Xi1 an1"))
        assertEquals("mìngbai", Pinyin.marked("ming4 bai5"))
    }

    @Test fun aKnownCompoundResolvesItsReadingAndStandaloneAmbiguityStaysVisible() {
        val text = "銀行。行。𠮷。图书馆。"
        val boundaries = intArrayOf(0, 1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12)
        val lexicon = mapOf(
            "行" to setOf("hang2", "xing2"),
            "銀行" to setOf("yin2 hang2"),
            "图书馆" to setOf("tu2 shu1 guan3", "Tu2Shu1Guan3")
        )
        val result = ReaderWords.annotate(text, boundaries) { prefix -> lexicon.filterKeys { it.startsWith(prefix) } }
        assertEquals(listOf("銀行" to "yínháng", "行" to "?", "𠮷" to null, "图书馆" to "túshūguǎn"),
            result.map { (span, ruby) -> text.substring(span.start, span.end) to ruby })
    }
}
