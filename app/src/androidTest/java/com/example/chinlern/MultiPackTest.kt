package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator only: uses the disposable guest profile. */
@RunWith(AndroidJUnit4::class)
class MultiPackTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun combinedAnswersPersistToOriginalPacksAndResume() {
        val store = StudyStore(context)
        val firstId = store.activePackId
        val water = store.words.first()
        store.selected.clear(); store.selected.add(water.id); store.save()
        store.importPack("Second", listOf(water))
        val secondId = store.activePackId
        store.choosePracticePacks(setOf(firstId, secondId))
        assertEquals(1, store.studyWords.size)
        val tasks = store.tasks().filter { it.skill == Skill.CHARACTER_MEANING }
        assertEquals(1, tasks.size)
        store.studyEngine.answer(tasks.first(), false, delay=5)
        store.current = tasks.first(); store.feedback = false; store.save()
        val firstSnapshot = store.syncSnapshot()
        StudyStore.validateSyncPacks(firstSnapshot)
        val reloaded = StudyStore(context)
        assertEquals(setOf(firstId, secondId), reloaded.practicePackIds)
        assertEquals(tasks.first(), reloaded.current)
        assertEquals(false, reloaded.feedback)
        assertEquals(1, reloaded.studyEngine.state(tasks.first()).errors)
        assertEquals(1, reloaded.studyEngine.state(tasks.single()).attempts)
        reloaded.save(); reloaded.save()
        assertEquals(firstSnapshot, reloaded.syncSnapshot())
        reloaded.choosePracticePacks(setOf(firstId))
        reloaded.switchPack(firstId)
        val firstErrors = reloaded.engine.state(Task(water.id, Skill.CHARACTER_MEANING)).errors
        reloaded.switchPack(secondId)
        val secondErrors = reloaded.engine.state(Task(water.id, Skill.CHARACTER_MEANING)).errors
        assertEquals(1, firstErrors)
        assertEquals(1, secondErrors)
    }
    @Test fun mixedSyncAndWordSelectionDoNotLoseProgress() {
        val store = StudyStore(context)
        val firstId = store.activePackId
        store.importPack("Second", listOf(store.words.first()))
        store.choosePracticePacks(store.packNames.map { it.first }.toSet())
        val task = store.tasks().first()
        store.studyEngine.answer(task, true); store.save()
        val before = store.syncSnapshot()
        store.applySync(before, before, "revision")
        assertEquals(Fsrs.Phase.LEARNING, store.studyEngine.state(task).memory!!.phase)
        store.selected.clear(); store.invalidatePractice(); store.save()
        assertEquals(16, store.studyWords.size)
        store.deletePack()
        assertEquals(setOf(firstId), store.practicePackIds)
        StudyStore.validateSyncPacks(store.syncSnapshot())
    }
    @Test fun librarySelectionAndEditingKeepOverlappingWordsIndependent() {
        val store = StudyStore(context)
        val first = store.activePackId
        val word = store.words.first()
        store.importPack("Second", listOf(word))
        val second = store.activePackId
        store.choosePracticePacks(setOf(first, second))
        store.route = "words"
        store.selectLibraryWord(first, word.id, false)
        assertFalse(store.libraryPacks().first { it.id == first }.selected.contains(word.id))
        assertTrue(store.libraryPacks().first { it.id == second }.selected.contains(word.id))
        store.editPack(first)
        store.replaceWord(word.copy(meanings = listOf("changed")))
        assertEquals("words", store.route)
        assertEquals(setOf(first, second), store.practicePackIds)
        assertEquals(word.meanings, store.libraryPacks().first { it.id == second }.words.first().meanings)
        store.selectLibraryWords(false)
        assertTrue(store.libraryPacks().all { it.selected.isEmpty() })
        store.selectLibraryWords(true)
        val restored = StudyStore(context)
        assertEquals(17, restored.studyWords.size)
        assertEquals(setOf(first, second), restored.practicePackIds)
        restored.choosePracticePacks(setOf(second))
        restored.editPack(first)
        assertEquals(setOf(second), restored.practicePackIds)
        restored.selectLibraryWords(false)
        assertEquals(16, restored.selected.size)
        assertEquals(0, restored.studyWords.size)
    }

}
