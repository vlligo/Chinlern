package com.example.chinlern

import com.example.chinlern.dictionary.Pinyin
import android.icu.text.BreakIterator
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.dictionary.OfflineDictionary
import com.example.chinlern.texts.ReaderAnnotations
import com.example.chinlern.texts.ReaderWords
import com.example.chinlern.texts.TextLibrary
import org.junit.Assert.*
import org.junit.Test

class ReaderPronunciationTest {
    private val dictionary = OfflineDictionary(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun contextDistinguishesReadingsAndLeavesUnresolvedWordsInspectable() {
        val text = "我得走了。她看得很认真。她还要去银行，也要还钱。我慢慢地走。外面下著雨。行。"
        val readings = annotate(text)
        fun reading(form: String, occurrence: Int = 0): String? = readings.filter {
            text.substring(it.span.start, it.span.end) == form
        }[occurrence].ruby
        assertEquals("děi", reading("得"))
        assertEquals("le", reading("了"))
        assertEquals("kàn", reading("看"))
        assertEquals("dehěn", reading("得很"))
        assertEquals("hái", reading("还"))
        assertEquals("huán", reading("还", 1))
        assertEquals("de", reading("地"))
        assertEquals("zhe", reading("著"))
        assertEquals("?", reading("行"))
        assertTrue(dictionary.exact("著").map { it.pinyin }.distinct().size > 1)
    }

    @Test fun contemporaryReadingsSubstantiallyReduceQuestionMarksInSampleProse() {
        val text = """
            今天我和朋友去图书馆看书。我们在那里学习中文，也听老师读课文。下课以后，我给妈妈打电话，告诉她我已经吃过饭了。回家的路上，我买了一些水果。
            我的朋友正在看电视。她看得很认真。我慢慢地走过去，问她周末想做什么。她说她还要去银行，然后和我一起去买东西。
            她把書放在桌上，然後站起來，慢慢地走到窗戶旁邊。外面下著雨，路上的人都打著傘。他穿著外套，安靜地看著街上的人。
            他还没有还书。我得走了。他得了第一名。她跑得很快。他把钱给了我。这个办法行吗？我去银行。
            我的中文老师说，只要每天都读一点儿，就会慢慢进步。我觉得学中文很有意思，所以晚上还会在家听中文歌。
        """.trimIndent()
        val readings = annotate(text)
        val forms = readings.map { text.substring(it.span.start, it.span.end) }
        val counts = forms.distinct().associateWith { form ->
            dictionary.exact(form).map { Pinyin.readingKey(it.pinyin) }.distinct().size
        }
        val before = forms.count { counts.getValue(it) > 1 }
        val after = readings.count { it.ruby == "?" }
        Log.i("ReaderPronunciationTest", "$before → $after question marks across ${forms.size} spans")
        assertTrue("Question marks before: $before, after: $after", before >= 40 && after < before / 2)
    }

    private fun annotate(text: String): List<ReaderWords.Annotation> {
        val boundaries = BreakIterator.getCharacterInstance().run {
            setText(text)
            generateSequence(first()) { next().takeUnless { it == BreakIterator.DONE } }.toList().toIntArray()
        }
        return ReaderAnnotations.load(TextLibrary.Document("fixture", "Fixture", text, 1, ""),
            boundaries, VocabularyLookup(emptyList()), dictionary)
    }
}
