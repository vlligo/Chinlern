package com.example.chinlern

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VocabularyLookupStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1")
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun searchesHiddenUnselectedPacksWithoutChangingStudyOrPersistedData() {
        val store = StudyStore(context)
        val original = store.activePackId
        val word = store.words.first()
        store.importPack("Hidden lookup", listOf(word.copy(id = "copy")))
        val hidden = store.activePackId
        store.setPackHidden(hidden, true)
        store.switchPack(original)
        val before = prefs.all.toMap()
        val progress = store.engine.progress.mapValues { it.value.copy() }
        val selection = store.selected.toSet()
        val current = store.current
        val lookup = store.vocabularyLookup()
        assertTrue(lookup.search(word.hanzi).any { it.packId == hidden })
        assertTrue(lookup.memberships(word).contains("Hidden lookup"))
        assertEquals(original, store.activePackId)
        assertEquals(current, store.current)
        assertEquals(selection, store.selected)
        assertEquals(progress, store.engine.progress)
        assertEquals(before, prefs.all)
    }
}
