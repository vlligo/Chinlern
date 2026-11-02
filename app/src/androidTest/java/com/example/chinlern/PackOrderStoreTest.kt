package com.example.chinlern

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PackOrderStoreTest {
    @Test fun sortedNamesSurviveRenameAndReloadWithoutChangingPracticeSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        var store = StudyStore(context)
        val word = store.words.first()
        store.renamePack("Zebra")
        val zebra = store.activePackId
        store.importPack("apple", listOf(word))
        val apple = store.activePackId
        store.importPack("Banana", listOf(word))
        store.choosePracticePacks(setOf(zebra, apple))
        assertEquals(listOf("apple", "Banana", "Zebra"), store.packNames.map { it.second })
        store.renamePack("Aardvark")
        store = StudyStore(context)
        assertEquals(listOf("Aardvark", "apple", "Zebra"), store.packNames.map { it.second })
        assertEquals(setOf(zebra, apple), store.practicePackIds)
        assertEquals(listOf("apple", "Zebra"), store.libraryPacks().map { it.name })
    }
}
