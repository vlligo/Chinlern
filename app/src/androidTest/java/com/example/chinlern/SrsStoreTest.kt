package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only: resets the guest profile. */
@RunWith(AndroidJUnit4::class)
class SrsStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var now = 1_700_000_000_000L
    private fun store() = StudyStore(context, clock = { now })
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun memoryAndRetentionSurviveReloadAndUndoExactly() {
        var s = store(); s.desiredRetention = 0.95
        s.beginSession(focus = listOf(Task(s.words.first().id, Skill.WRITING)))
        s.nextQuestion(); val task = s.current!!
        s.recordAnswer(true)
        val before = s.studyEngine.state(task).copy()
        s = store()
        assertEquals(0.95, s.desiredRetention, 0.0)
        assertEquals(before, s.studyEngine.state(task))
        s.leaveSession(); now = before.due
        s.beginSession(focus = listOf(task)); s.nextQuestion(); s.recordAnswer(false)
        assertTrue(s.ignoreAnswer())
        assertEquals(before, store().studyEngine.state(task))
        assertTrue(store().session.getJSONArray("reviewed").length() == 0)
    }
    @Test fun shortSessionEndsWithoutRepeatingKnownOrNotYetDueSkills() {
        val s = store()
        s.dailyNewWords = 1; s.beginSession(); s.nextQuestion()
        val word = s.studyWords.first { it.id == s.current!!.wordId }
        s.introduce(word); s.recordAnswer(true)
        s.nextQuestion()
        assertNull(s.current)
        assertTrue(s.sessionFinished)
        assertEquals(1, s.sessionCount)
        assertEquals(1, s.studyEngine.progress.values.sumOf { it.attempts })
    }
    @Test fun freshDayKeepsLearningStepAndDueDate() {
        val s = store(); s.beginSession(); s.nextQuestion(); s.recordAnswer(false)
        val task = s.current!!; val before = s.studyEngine.state(task).copy()
        now += Fsrs.DAY
        val resumed = store()
        assertNull(resumed.current)
        assertEquals(before, resumed.studyEngine.state(task))
        resumed.nextQuestion()
        assertEquals(task, resumed.current)
    }
    @Test fun focusedPracticeVisitsFutureSkillsOnlyOncePerSession() {
        val s = store(); val task = Task(s.words.first().id, Skill.WRITING)
        s.studyEngine.answer(task, Fsrs.Rating.EASY, now)
        s.beginSession(focus = listOf(task)); s.nextQuestion()
        assertEquals(task, s.current)
        s.recordAnswer(true); s.nextQuestion()
        assertNull(s.current)
        assertEquals(1, s.sessionCount)
        assertTrue(s.sessionFinished)
    }
    @Test fun completedRegularPracticeAutomaticallyFindsDueReviewsOnReturn() {
        val s = store(); val task = Task(s.words.first().id, Skill.CHARACTER_MEANING)
        s.dailyNewWords = 0
        s.studyEngine.answer(task, Fsrs.Rating.EASY, now)
        s.save()
        now = s.studyEngine.state(task).due
        val resumed = store(); resumed.nextQuestion()
        assertEquals(task, resumed.current)
    }
    @Test fun redrawPersistsScreenWithoutRewritingLearningData() {
        val s = store(); s.nextQuestion(); s.recordAnswer(true)
        val prefs = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE)
        val packs = prefs.getString("packs", null)
        var changes = 0
        s.onSyncDataChanged = { changes++ }
        s.route = "settings"; s.revealed = true; s.saveScreen()
        assertEquals(packs, prefs.getString("packs", null))
        assertEquals(0, changes)
        assertEquals("settings", store().route)
        assertTrue(store().revealed)
    }

    @Test fun doubleSubmissionCannotCreateTwoReviewEvents() {
        val s = store(); s.nextQuestion(); val task = s.current!!
        s.recordAnswer(true)
        val before = s.studyEngine.state(task).copy()
        s.recordAnswer(false)
        assertEquals(before, s.studyEngine.state(task))
        assertEquals(1, store().studyEngine.state(task).memory!!.history.size)
    }

}
