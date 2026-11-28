package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class VocabularyLookupTest {
    private val word = Word("one", "学习", "xué xí", "xue2 xi2", listOf("учиться"), emptyList(), alternatives = listOf("學習"))
    private val entries = listOf(
        VocabularyLookup.Entry(word, "a", "First"),
        VocabularyLookup.Entry(word.copy(id = "copy"), "b", "Hidden"),
        VocabularyLookup.Entry(word.copy(id = "sense", meanings = listOf("изучение")), "c", "Other sense")
    )
    private val lookup = VocabularyLookup(entries)

    @Test fun findsFormsPronunciationAndMeaningsWithoutCombiningSenses() {
        for (query in listOf("學習", "学习", " XUÉ XÍ ", "xue2 xi2", "xuéxí", "xuexi"))
            assertEquals(3, lookup.search(query).size)
        assertTrue(lookup.search("xue2 xi4").isEmpty())
        assertEquals(2, lookup.search("УЧИТЬСЯ").size)
        assertEquals(listOf("c"), lookup.search("изучение").map { it.packId })
        assertTrue(lookup.search("  ").isEmpty())
        assertTrue(lookup.search("不存在").isEmpty())
    }

    @Test fun membershipsKeepDifferentMeaningsSeparateAndSearchIsRepeatable() {
        assertEquals(listOf("First", "Hidden"), lookup.memberships(word))
        val before = entries.toList()
        repeat(5) { lookup.search("学习"); lookup.memberships(word) }
        assertEquals(before, entries)
    }

    @Test fun findsCardsByPackOrPackAndHeadword() {
        assertEquals(listOf("b"), lookup.search("hidden").map { it.packId })
        assertEquals(listOf("c"), lookup.search("other sense 学习").map { it.packId })
    }

    @Test fun exactFormRanksBeforeLongerMatch() {
        val extended = word.copy(hanzi = "学习者", alternatives = emptyList())
        val search = VocabularyLookup(listOf(VocabularyLookup.Entry(extended, "a", "A"), entries.first()))
        assertEquals(word, search.search("学习").first().word)
    }
}
