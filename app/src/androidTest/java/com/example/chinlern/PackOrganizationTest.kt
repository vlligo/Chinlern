package com.example.chinlern

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PackOrganizationTest {
    @Test fun bulkMoveValidatesAllIdsBeforeChangingAnyPack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = StudyStore(context, "bulk-move-test-${System.nanoTime()}")
        val first = store.activePackId
        store.importPack("Second", listOf(store.words.first()))
        val second = store.activePackId
        store.choosePracticePacks(setOf(first, second))
        store.setPackHidden(second, true)
        val before = store.syncSnapshot()
        try { store.movePacks(setOf(first, "missing"), "Lessons"); fail("Invalid IDs must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, store.syncSnapshot())
        val chosen = store.practicePackIds.toSet()
        store.movePacks(setOf(first, second), "Lessons")
        assertEquals("Lessons", store.packFolder(first))
        assertEquals("Lessons", store.packFolder(second))
        assertTrue(store.isPackHidden(second))
        assertEquals(chosen, store.practicePackIds)
        val after = store.syncSnapshot()
        before.forEach { (id, text) ->
            assertEquals(org.json.JSONObject(text).put("folder", "Lessons").let(SyncSnapshot::pack), after[id])
        }
    }

    @Test fun hidingMovingRestoringAndDeletingPreserveOtherPacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = "organization-test-${System.nanoTime()}"
        var store = StudyStore(context, profile)
        val first = store.activePackId
        val word = store.words.first()
        store.setWordKnown(first, word.id, WordKnowledge.WRITING, true)
        store.importPack("Second", listOf(word))
        val second = store.activePackId
        store.choosePracticePacks(setOf(first, second))
        store.movePack(first, "Lessons")
        store.setPackHidden(first, true)
        store = StudyStore(context, profile)
        assertTrue(store.isPackHidden(first))
        assertEquals("Lessons", store.packFolder(first))
        assertEquals(setOf(second), store.practicePackIds)
        assertEquals(listOf(second), store.libraryPacks().map { it.id })
        try { store.setPackHidden(second, true); fail("Last visible list must remain") }
        catch (_: IllegalArgumentException) { }
        store.renameFolder("Lessons", "Study")
        assertEquals("Study", store.packFolder(first))
        store.setPackHidden(first, false)
        store.choosePracticePacks(setOf(first, second))
        assertTrue(word.id in store.libraryPacks().first { it.id == first }.writable)
        store.removeFolder("Study")
        assertEquals("", store.packFolder(first))
        store.editPack(second); store.deletePack()
        store = StudyStore(context, profile)
        assertEquals(listOf(first), store.packNames.map { it.first })
        assertTrue(word.id in store.libraryPacks().single().writable)
        StudyStore.validateSyncPacks(store.syncSnapshot())
    }
}
