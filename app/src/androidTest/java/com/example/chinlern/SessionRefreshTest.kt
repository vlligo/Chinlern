package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/** Disposable emulator only: resets the guest profile. */
@RunWith(AndroidJUnit4::class)
class SessionRefreshTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var now = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 3, 12, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun store() = StudyStore(context, clock = { now })
    @Before fun reset() {
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun shortBreakRetainsQuestionDraftAndCounters() {
        val first = store()
        first.beginSession(); first.nextQuestion(); first.recordAnswer(false); first.nextQuestion()
        first.draft = "unfinished"; first.saveDraft()
        now += 60_000
        val resumed = store()
        assertEquals(first.current, resumed.current)
        assertEquals("unfinished", resumed.draft)
        assertEquals(1, resumed.sessionCount)
    }
    @Test fun longBreakStartsFreshWithoutLosingMistakesOrFocus() {
        val first = store()
        val task = Task(first.words.first().id, Skill.CHARACTER_PINYIN)
        first.beginSession(focus = listOf(task)); first.nextQuestion(); first.recordAnswer(false)
        now += 4 * 60 * 60 * 1000L
        val refreshed = store()
        assertTrue(refreshed.shortSession)
        assertEquals(0, refreshed.sessionCount)
        assertNull(refreshed.current)
        assertFalse(refreshed.canIgnoreAnswer)
        assertEquals(listOf(task), refreshed.tasks())
        assertEquals(1, refreshed.studyEngine.state(task).errors)
        assertFalse(refreshed.studyEngine.state(task).mastered)
    }
    @Test fun midnightRefreshesEvenAfterBriefBreak() {
        now = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis
        val first = store()
        first.beginSession(); first.nextQuestion(); first.recordAnswer(true)
        val task = first.current!!
        now += 120_000
        assertTrue(first.refreshPractice())
        assertEquals(0, first.sessionCount)
        assertEquals(Fsrs.Phase.LEARNING, first.studyEngine.state(task).memory!!.phase)
        assertEquals(1, first.studyEngine.state(task).attempts)
    }
    @Test fun timedSessionGetsNewTimerAfterLongAbsence() {
        val first = store()
        first.beginSession(minutes = true); first.nextQuestion()
        now += 4 * 60 * 60 * 1000L
        val refreshed = store()
        assertFalse(refreshed.sessionFinished)
        assertEquals(now + 300_000, refreshed.session.getLong("until"))
    }
    @Test fun unrestrictedPracticeAlsoDropsStaleQuestion() {
        val first = store()
        first.nextQuestion(); first.recordAnswer(false)
        val task = first.current!!
        now += 24 * 60 * 60 * 1000L
        val refreshed = store()
        assertFalse(refreshed.shortSession)
        assertNull(refreshed.current)
        assertEquals(1, refreshed.studyEngine.state(task).errors)
        assertTrue(task in refreshed.tasks())
    }
}
