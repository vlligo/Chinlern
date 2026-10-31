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

/** Run only on a disposable emulator: resets the guest profile. */
@RunWith(AndroidJUnit4::class)
class ShortSessionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun previewThenPinyinFeedbackAndSessionResults() {
        val store = StudyStore(context)
        val word = store.words.first { it.numbered == "shui3" }
        store.beginSession(focus = listOf(Task(word.id, Skill.CHARACTER_PINYIN)))
        store.route = "quiz"; store.nextQuestion(); store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Новое слово")).check(matches(isDisplayed()))
            onView(withText("Запомнил — к вопросам")).perform(click())
            onView(withHint("Пиньинь с тонами")).perform(replaceText("shui2"), closeSoftKeyboard())
            onView(withText("Проверить")).perform(click())
            onView(withText("Тон 1: shui2 → shui3 (нужен 3-й тон)")).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.recreate()
            assertEquals(1, StudyStore(context).sessionCount)
            onView(withText("Завершить занятие")).perform(scrollTo(), click())
            onView(withText("Занятие завершено")).check(matches(isDisplayed()))
            onView(withText("Верно: 0 · Ошибок: 1")).check(matches(isDisplayed()))
        }
    }

    @Test fun tenAnswersSurviveReloadAndUndo() {
        var store = StudyStore(context)
        store.beginSession()
        repeat(10) {
            store.nextQuestion()
            store.recordAnswer(it % 2 == 0)
        }
        store = StudyStore(context)
        assertEquals(10, store.sessionCount)
        assertEquals(5, store.sessionCorrect)
        assertTrue(store.sessionFinished)
        assertTrue(store.ignoreAnswer())
        assertEquals(9, store.sessionCount)
        assertFalse(store.sessionFinished)
        store.nextQuestion()
        store.recordAnswer(true)
        store.nextQuestion()
        assertNull(store.current)
        assertEquals(6, store.sessionCorrect)
    }
    @Test fun synonymCorrectionCountsOnlyOnce() {
        val store = StudyStore(context)
        store.beginSession(); store.nextQuestion(); store.recordAnswer(false)
        assertTrue(store.correctAnswer())
        assertEquals(1, store.sessionCount)
        assertEquals(1, store.sessionCorrect)
    }
    @Test fun dailyLimitAndPreviewsPersist() {
        var store = StudyStore(context)
        store.dailyNewWords = 3
        val ids = store.tasks().map { it.wordId }.distinct()
        assertEquals(3, ids.size)
        ids.forEach { id -> store.introduce(store.studyWords.first { it.id == id }) }
        store = StudyStore(context)
        assertEquals(ids.toSet(), store.tasks().map { it.wordId }.toSet())
        assertFalse(store.needsPreview(store.studyWords.first { it.id == ids.first() }))
        store.dailyNewWords = 0
        assertEquals(ids.toSet(), store.tasks().map { it.wordId }.toSet())
    }
    @Test fun focusCanPractiseMasteredWritingAndKnowledgeWithoutResettingProgress() {
        val store = StudyStore(context)
        val id = store.studyWords.first().id
        val focus = listOf(Task(id, Skill.CHARACTER_TONE), Task(id, Skill.WRITING))
        focus.forEach { store.studyEngine.state(it).mastered = true }
        store.beginSession(focus = focus)
        assertEquals(focus.toSet(), store.tasks().toSet())
        store.nextQuestion()
        assertTrue(store.current in focus)
        assertTrue(focus.all { store.studyEngine.state(it).mastered })
        store.choosePracticePacks(setOf(store.activePackId))
        assertFalse(store.shortSession)
    }
    @Test fun expiredSessionDoesNotScheduleAnotherQuestion() {
        val store = StudyStore(context)
        store.beginSession(minutes = true)
        store.session.put("until", System.currentTimeMillis() - 1)
        store.nextQuestion()
        assertTrue(store.sessionFinished)
        assertNull(store.current)
    }
}
