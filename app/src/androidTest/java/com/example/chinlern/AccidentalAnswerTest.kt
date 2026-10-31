package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only: resets the guest library. */
@RunWith(AndroidJUnit4::class)
class AccidentalAnswerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun wrongAnswerRestoresAllStatisticsAndScheduleAfterReload() {
        val store = StudyStore(context)
        val task = Task("water", Skill.CHARACTER_MEANING)
        val previous = Task("fire", Skill.CHARACTER_PINYIN)
        val before = Progress(attempts = 12, errors = 3, streak = 4, mastered = true, nextAt = 30, due = 2000000000000L, interval = 15)
        store.engine.progress[task.key] = before.copy()
        store.engine.step = 25; store.engine.previous = previous
        store.current = task; store.recordAnswer(false)
        assertEquals(4, store.engine.state(task).errors)
        val reopened = StudyStore(context)
        assertTrue(reopened.ignoreAnswer())
        assertEquals(before, reopened.engine.state(task))
        assertEquals(25, reopened.engine.step)
        assertEquals(previous, reopened.engine.previous)
        assertTrue(reopened.answerIgnored)
        assertFalse(reopened.ignoreAnswer())
        reopened.recordAnswer(true)
        assertEquals(before, reopened.engine.state(task))
        val again = StudyStore(context)
        assertTrue(again.answerIgnored)
        assertEquals(before, again.engine.state(task))
        again.nextQuestion()
        assertFalse(again.answerIgnored)
        assertFalse(again.canIgnoreAnswer)
    }

    @Test fun accidentalCorrectAndWritingAnswersAlsoCountForNothing() {
        for (skill in listOf(Skill.CHARACTER_TONE, Skill.WRITING)) {
            val store = StudyStore(context)
            store.clearQuestion()
            val task = Task("water", skill)
            val snapshot = store.syncSnapshot()
            store.current = task; store.recordAnswer(true)
            assertTrue(store.studyEngine.state(task).mastered)
            assertTrue(store.ignoreAnswer())
            assertEquals(snapshot, store.syncSnapshot())
        }
    }

    @Test fun mixedPackRollbackRestoresBothCountersWithoutTouchingOtherWords() {
        val store = StudyStore(context)
        val water = store.words.first()
        store.engine.step = 9
        store.engine.progress[Task(water.id, Skill.CHARACTER_MEANING).key] = Progress(attempts = 3, errors = 1, nextAt = 20)
        val first = store.activePackId
        store.importPack("Second", listOf(water))
        store.engine.step = 40
        store.engine.progress[Task(water.id, Skill.WRITING).key] = Progress(attempts = 7, errors = 2, nextAt = 50)
        store.choosePracticePacks(setOf(first, store.activePackId))
        val task = store.tasks().first { it.skill == Skill.CHARACTER_MEANING }
        val previous = store.tasks().last()
        store.studyEngine.previous = previous
        val snapshot = store.syncSnapshot()
        store.current = task; store.recordAnswer(false)
        val reloaded = StudyStore(context)
        assertTrue(reloaded.ignoreAnswer())
        assertEquals(previous, reloaded.studyEngine.previous)
        val after = reloaded.syncSnapshot()
        for (id in snapshot.keys) {
            val beforePack = JSONObject(snapshot.getValue(id))
            val afterPack = JSONObject(after.getValue(id))
            assertEquals(beforePack.getInt("step"), afterPack.getInt("step"))
            assertEquals(beforePack.getJSONObject("progress").toString(), afterPack.getJSONObject("progress").toString())
            assertEquals(beforePack.getString("words"), afterPack.getString("words"))
        }
    }

    @Test fun correctedSynonymStillAllowsUndoWithoutDoubleCounting() {
        val store = StudyStore(context)
        val task = Task("water", Skill.CHARACTER_MEANING)
        store.current = task; store.recordAnswer(false)
        assertTrue(store.correctAnswer())
        assertEquals(1, store.engine.state(task).attempts)
        assertEquals(0, store.engine.state(task).errors)
        assertTrue(store.ignoreAnswer())
        assertEquals(Progress(), store.engine.state(task))
        assertEquals(0, store.engine.step)
    }

    @Test fun ignoreButtonSurvivesRotationAndKeepsRevealedAnswerVisible() {
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.current = Task("water", Skill.CHARACTER_MEANING); store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("Русский перевод")).perform(scrollTo(), replaceText("shui3"), closeSoftKeyboard())
            onView(withText("Проверить")).check(matches(isCompletelyDisplayed())).perform(click())
            assertEquals(1, StudyStore(context).engine.state(Task("water", Skill.CHARACTER_MEANING)).errors)
            scenario.recreate()
            onView(withText("Ответил случайно — не учитывать")).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("Ответ не учтён")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Ответил случайно — не учитывать")).check(doesNotExist())
            val reloaded = StudyStore(context)
            assertEquals(0, reloaded.engine.state(Task("water", Skill.CHARACTER_MEANING)).attempts)
            assertEquals(0, reloaded.engine.state(Task("water", Skill.CHARACTER_MEANING)).errors)
            scenario.recreate()
            onView(withText("Ответ не учтён")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed())).perform(click())
        }
    }
}
