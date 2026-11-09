package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun oldLibraryMigratesWithoutLosingProgress() {
        val prefs = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("words", StudyStore.seedJson).putStringSet("selected", setOf("water"))
            .putString("progress", """{"water:CHARACTER_MEANING":{"attempts":3,"errors":1,"mastered":true}}""").commit()
        val store = StudyStore(context)
        assertEquals("Мои слова", store.packName)
        assertEquals(setOf("water"), store.selected)
        store.save()
        val restored = StudyStore(context)
        assertTrue(restored.engine.state(Task("water", Skill.CHARACTER_MEANING)).mastered)
        assertEquals(3, restored.engine.state(Task("water", Skill.CHARACTER_MEANING)).attempts)
    }
    @Test fun overlappingWordsKeepSeparateProgressAndSelections() {
        val store = StudyStore(context)
        val task = Task("water", Skill.CHARACTER_MEANING)
        store.selected.clear(); store.selected.add("water")
        store.engine.answer(task, true)
        val oldId = store.activePackId
        store.importPack("Lesson 2", listOf(store.words.first()))
        val newId = store.activePackId
        assertFalse(store.engine.state(task).mastered)
        assertEquals(1, store.tasks().map { it.wordId }.distinct().size)
        store.engine.answer(task, false)
        store.save()
        val restored = StudyStore(context)
        assertEquals(newId, restored.activePackId)
        assertEquals(1, restored.engine.state(task).errors)
        restored.switchPack(oldId)
        assertEquals(16, restored.words.size)
        assertEquals(setOf("water"), restored.selected)
        assertEquals(Fsrs.Phase.LEARNING, restored.engine.state(task).memory!!.phase)
        assertEquals(0, restored.engine.state(task).errors)
        restored.switchPack(newId)
        restored.renamePack("Lesson renamed")
        assertEquals("Lesson renamed", StudyStore(context).packName)
        restored.deletePack()
        assertEquals(oldId, StudyStore(context).activePackId)
        assertEquals(1, StudyStore(context).packNames.size)
    }
    @Test fun simpleJsonGeneratesChoicesAndInvalidImportIsAtomic() {
        val store = StudyStore(context)
        val input = """{"name":"Travel","words":[{"hanzi":"你好","pinyin":"ni3 hao3","meanings":["привет"]}]}"""
        val words = StudyStore.decodeWords(input)
        assertTrue(words.first().distractors.size >= 3)
        assertFalse(words.first().hanzi in words.first().distractors)
        store.importPack("Travel", words)
        assertEquals(words, StudyStore.decodeWords(store.exportPack()))
        try { store.importPack("Bad", listOf(words.first().copy(pinyin="invalid"))); fail("Accepted invalid word") } catch (_: IllegalArgumentException) { }
        assertEquals(2, store.packNames.size)
        assertEquals("Travel", store.packName)
        assertEquals(words, StudyStore(context).words)
    }
    @Test fun pickerSwitchesPackAndSurvivesRotation() {
        val store = StudyStore(context)
        store.importPack("Travel", listOf(store.words.first()))
        store.route = "words"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(libraryPracticePicker(1)).perform(scrollToLibraryControl(), click())
            onView(withText("Мои слова")).perform(click())
            onView(withText("Применить")).perform(click())
            scenario.recreate()
            onView(libraryPracticePicker(2)).check(matches(isDisplayed()))
            assertEquals(17, StudyStore(context).studyWords.size)
        }
    }
}
