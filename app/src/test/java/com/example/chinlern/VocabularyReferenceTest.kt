package com.example.chinlern

import com.example.chinlern.data.VocabularyReference
import org.junit.Assert.*
import org.junit.Test

class VocabularyReferenceTest {
    @Test fun referencesAreStableAndDoNotCollapsePackLocalIdsOrAmbiguousDelimiters() {
        assertEquals(VocabularyReference("pack", "word").stableId, VocabularyReference("pack", "word").stableId)
        assertNotEquals(VocabularyReference("a", "word").stableId, VocabularyReference("b", "word").stableId)
        assertNotEquals(VocabularyReference("a:b", "c").stableId, VocabularyReference("a", "b:c").stableId)
        val word = Word("word", "水", "shuǐ", "shui3", listOf("вода"), emptyList())
        val original = VocabularyLookup.Entry(word, "pack", "Old")
        val edited = VocabularyLookup.Entry(word.copy(meanings = listOf("water")), "pack", "Renamed")
        assertEquals(original.reference, edited.reference)
        assertNotEquals(SharedWords.identity(original.word), SharedWords.identity(edited.word))
    }
}
