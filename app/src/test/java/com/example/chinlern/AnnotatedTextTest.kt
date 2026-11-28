package com.example.chinlern

import com.example.chinlern.texts.AnnotatedText
import com.example.chinlern.texts.ReaderWords
import com.example.chinlern.texts.TextLibrary
import org.junit.Assert.*
import org.junit.Test

class AnnotatedTextTest {
    @Test fun markupRoundTripsWhileOffsetsAndCopyTextStayPlain() {
        val input = " {我|wǒ}去{圖書館|tu2 shu1 guan3|библиотека}。\n{𠮷|ji2|удача}🙂\\{x\\}"
        val parsed = AnnotatedText.parse(input)
        assertEquals(" 我去圖書館。\n𠮷🙂{x}", parsed.text)
        assertEquals(listOf("我", "圖書館", "𠮷"), parsed.notes.map { parsed.text.substring(it.start, it.end) })
        assertEquals(listOf("", "библиотека", "удача"), parsed.notes.map { it.gloss })
        assertEquals(input, AnnotatedText.format(TextLibrary.Document("fixture", "", parsed.text, 1, "", parsed.notes)))
        assertEquals("túshūguǎn", AnnotatedText.display(parsed.notes[1].pinyin))
        assertEquals("nü3", AnnotatedText.numbered("nu:3"))
    }

    @Test fun rejectsIncompleteMarkupAndInvalidReadings() {
        for (input in listOf("{水|", "{水}", "{水|shui6}", "{水|水}", "{水|:3}", "{水|shuǐ{", "{|shuǐ}", "水}")) {
            try {
                AnnotatedText.parse(input)
                fail("Accepted invalid markup: $input")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun suppliedBoundariesTakePrecedenceOverDictionaryCompounds() {
        val text = "去图书馆。"
        val words = setOf("去图书馆", "图书馆", "书馆")
        val annotations = ReaderWords.annotate(text, IntArray(text.length + 1) { it },
            supplied = mapOf(ReaderWords.Span(1, 2) to "tú")) { initial ->
            words.filter { it.startsWith(initial) }.associateWith { setOf("shu1 guan3") }
        }
        assertEquals(listOf("去", "图", "书馆"), annotations.map { text.substring(it.span.start, it.span.end) })
        assertEquals("tú", annotations[1].ruby)
    }
}
