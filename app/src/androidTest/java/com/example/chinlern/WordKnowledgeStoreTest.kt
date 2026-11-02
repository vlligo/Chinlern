package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordKnowledgeStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun mixedPacksPersistIndependentlyAndKnownWordsBypassNewWordLimit() {
        var store = StudyStore(context)
        val first = store.activePackId
        val word = store.words.first()
        store.importPack("Second", listOf(word))
        val second = store.activePackId
        store.choosePracticePacks(setOf(first, second))
        store.studyWords // Build the mixed cache before changing the inactive source.
        store.setWordKnown(first, word.id, WordKnowledge.RECOGNITION, true)
        store.setWordKnown(second, word.id, WordKnowledge.WRITING, true)
        store.dailyNewWords = 0; store.save()
        store = StudyStore(context)
        val packs = store.libraryPacks().associateBy { it.id }
        assertTrue(word.id in packs.getValue(first).recognized)
        assertFalse(word.id in packs.getValue(first).writable)
        assertFalse(word.id in packs.getValue(second).recognized)
        assertTrue(word.id in packs.getValue(second).writable)
        assertEquals(setOf(first, second), store.practicePackIds)
        assertEquals(second, store.activePackId)
        assertTrue(store.studyWords.filter { it.hanzi == word.hanzi }.all { !store.needsPreview(it) })
        StudyStore.validateSyncPacks(store.syncSnapshot())
        assertEquals(2, store.overview().sumOf { it.introduced })
        store.setWordKnown(first, word.id, WordKnowledge.RECOGNITION, false)
        assertFalse(word.id in StudyStore(context).libraryPacks().first { it.id == first }.recognized)
        assertTrue(word.id in StudyStore(context).libraryPacks().first { it.id == second }.writable)
    }

    @Test fun wordCardMarksSurviveRecreationAndCanBeRemoved() {
        val store = StudyStore(context)
        store.route = "words"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("Найти слово, пиньинь или перевод")).perform(scrollTo(), replaceText("shui3"), closeSoftKeyboard())
            onView(withContentDescription("Узнаю слово: 水 · Мои слова")).perform(scrollTo(), click()).check(matches(isChecked()))
            onView(withContentDescription("Умею писать: 水 · Мои слова")).check(matches(isNotChecked())).perform(scrollTo(), click())
            scenario.recreate()
            onView(withHint("Найти слово, пиньинь или перевод")).perform(scrollTo(), replaceText("shui3"), closeSoftKeyboard())
            onView(withContentDescription("Узнаю слово: 水 · Мои слова")).perform(scrollTo()).check(matches(isChecked()))
            onView(withContentDescription("Умею писать: 水 · Мои слова")).perform(scrollTo()).check(matches(isChecked())).perform(click())
            assertTrue("water" in StudyStore(context).libraryPacks().single().recognized)
            assertFalse("water" in StudyStore(context).libraryPacks().single().writable)
        }
    }
}
