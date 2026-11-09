package com.example.chinlern

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ScrollView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
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

/** Destructive fixtures: emulator or a separate test application only. */
@RunWith(AndroidJUnit4::class)
class ExtraStudyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun everyNewWordGetsTwoQuestionsAcrossReloads() {
        val now = System.currentTimeMillis()
        var store = StudyStore(context, clock = { now })
        store.dailyNewWords = 0
        val selectedCount = store.selectedStudyWords.size
        assertTrue(store.beginExtraStudy(newWords = true))
        val ids = store.tasks().map { it.wordId }.toSet()
        assertEquals(5, ids.size)
        assertEquals(selectedCount, store.selectedStudyWords.size)
        val asked = mutableMapOf<String, Int>()
        repeat(10) { index ->
            store.nextQuestion()
            val task = store.current!!
            val word = store.studyWords.first { it.id == task.wordId }
            if (store.needsPreview(word)) store.introduce(word)
            asked[task.wordId] = (asked[task.wordId] ?: 0) + 1
            store.recordAnswer(index % 2 == 0)
            if (index == 4) assertEquals(ids, asked.keys)
            if (index in setOf(2, 6)) store = StudyStore(context, clock = { now })
        }
        assertTrue(store.sessionFinished)
        assertEquals(ids.associateWith { 2 }, asked)
        assertEquals(0, store.dailyNewWords)
    }

    @Test fun refreshedExtraSessionKeepsBalancedCoverage() {
        var now = System.currentTimeMillis()
        var store = StudyStore(context, clock = { now })
        store.beginExtraStudy(newWords = true)
        store.nextQuestion(); store.introduce(store.studyWords.first { it.id == store.current!!.wordId })
        store.recordAnswer(false)
        now += 4 * 60 * 60 * 1000L
        store = StudyStore(context, clock = { now })
        val firstRound = mutableSetOf<String>()
        repeat(5) {
            store.nextQuestion()
            assertTrue(firstRound.add(store.current!!.wordId))
            store.recordAnswer(true)
        }
    }

    private fun scrollToPracticeControl() = object : ViewAction {
        override fun getDescription() = "scroll above the floating navigation dock"
        override fun getConstraints() = scrollTo().constraints
        override fun perform(uiController: UiController, view: View) {
            scrollTo().perform(uiController, view)
            var ancestor = view.parent
            while (ancestor is View && ancestor !is ScrollView) ancestor = ancestor.parent
            val scroll = ancestor as? ScrollView ?: return
            val location = IntArray(2); view.getLocationOnScreen(location)
            val viewport = IntArray(2); scroll.getLocationOnScreen(viewport)
            val bottom = viewport[1] + scroll.height - scroll.paddingBottom
            if (location[1] + view.height > bottom) scroll.scrollBy(0, location[1] + view.height - bottom)
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun screenshot(activity: MainActivity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        java.io.File(activity.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun extraWordsBypassZeroBudgetAndPersistWithoutChangingDailyPace() {
        var store = StudyStore(context)
        store.dailyNewWords = 0
        assertTrue(store.tasks().isEmpty())
        val focus = store.extraStudyTasks(newWords = true)
        val ids = focus.map { it.wordId }.toSet()
        assertEquals(5, ids.size)
        store.beginSession(focus = focus)
        store.nextQuestion()
        assertNotNull(store.current)
        val word = store.studyWords.first { it.id == store.current!!.wordId }
        assertTrue(store.needsPreview(word))
        store.introduce(word)
        store.recordAnswer(true)
        val task = store.current!!
        val saved = store.studyEngine.peek(task).copy()
        store = StudyStore(context)
        assertEquals(0, store.dailyNewWords)
        assertFalse(store.needsPreview(store.studyWords.first { it.id == word.id }))
        assertEquals(saved, store.studyEngine.peek(task))
        assertEquals(ids, store.tasks().map { it.wordId }.toSet())
        store.leaveSession()
        val next = store.extraStudyTasks(newWords = true).map { it.wordId }.toSet()
        assertFalse(word.id in next)
        assertEquals(5, next.size)
    }

    @Test fun familiarPracticeWorksBeforeDueWithoutResettingMemory() {
        val store = StudyStore(context)
        store.dailyNewWords = 0
        val word = store.studyWords.first()
        store.introduce(word)
        val focus = store.extraStudyTasks(newWords = false)
        val future = System.currentTimeMillis() + 86_400_000L
        focus.forEach { task -> store.studyEngine.state(task).apply { attempts = 3; mastered = true; due = future } }
        val before = focus.associateWith { store.studyEngine.peek(it).copy() }
        assertNull(store.studyEngine.next(focus))
        store.beginSession(focus = focus)
        store.nextQuestion()
        assertNotNull(store.current)
        assertEquals(before, focus.associateWith { store.studyEngine.peek(it).copy() })
        store.recordAnswer(true)
        assertEquals(4, store.studyEngine.peek(store.current!!).attempts)
        store.endSession()
        store.beginSession(focus = store.extraStudyTasks(newWords = false))
        store.nextQuestion()
        assertNotNull(store.current)
    }

    @Test fun extraWordsRespectSelectionWritingAndExhaustion() {
        val store = StudyStore(context)
        val word = store.studyWords.last()
        store.selected.clear(); store.selected.add(word.id)
        val focus = store.extraStudyTasks(newWords = true, writing = true)
        assertEquals(listOf(Task(word.id, Skill.WRITING)), focus)
        store.introduce(word)
        assertTrue(store.extraStudyTasks(newWords = true).isEmpty())
        assertEquals(focus, store.extraStudyTasks(newWords = false, writing = true))
        store.selected.clear()
        assertTrue(store.extraStudyTasks(newWords = false).isEmpty())
    }

    @Test fun mixedPackIntroductionsStayWithTheirOriginalWordsAfterReload() {
        var store = StudyStore(context)
        store.dailyNewWords = 0
        val firstPack = store.activePackId
        val firstWord = store.words.first()
        store.selected.clear(); store.selected.add(firstWord.id)
        store.importPack("Extra study", listOf(firstWord))
        store.choosePracticePacks(setOf(firstPack, store.activePackId))
        val focus = store.extraStudyTasks(newWords = true, writing = true)
        assertEquals(2, focus.size)
        store.beginSession(focus = focus)
        store.nextQuestion()
        val word = store.studyWords.first { it.id == store.current!!.wordId }
        store.introduce(word); store.recordAnswer(true)
        store = StudyStore(context)
        assertEquals(1, store.extraStudyTasks(newWords = true, writing = true).size)
        assertEquals(1, store.extraStudyTasks(newWords = false, writing = true).size)
        assertEquals(1, store.studyEngine.peek(store.current!!).attempts)
        store.endSession()
        store.beginSession(focus = store.extraStudyTasks(newWords = true, writing = true))
        store.nextQuestion()
        assertNotEquals(word.id, store.current!!.wordId)
        assertTrue(store.needsPreview(store.studyWords.first { it.id == store.current!!.wordId }))
    }

    @Test fun learnMoreFromDashboardPreviewAndResults() {
        val store = StudyStore(context)
        store.dailyNewWords = 0; store.onboardingDone = true; store.route = "learn"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Учить ещё: 5 новых слов")).perform(scrollToPracticeControl())
            scenario.onActivity { screenshot(it, "extra-study-dashboard.png") }
            onView(withText("Учить ещё: 5 новых слов")).perform(click())
            onView(withText("Новое слово")).check(matches(isDisplayed()))
            scenario.onActivity { screenshot(it, "extra-study-preview.png") }
            onView(withText("Запомнил — к вопросам")).perform(click())
            onView(withText("Завершить занятие")).perform(scrollToPracticeControl(), click())
            onView(withText("Занятие завершено")).check(matches(isDisplayed()))
            scenario.onActivity { screenshot(it, "extra-study-results.png") }
            onView(withText("Учить ещё: 5 новых слов")).perform(scrollToPracticeControl(), click())
            onView(withText("Новое слово")).check(matches(isDisplayed()))
            assertEquals(0, StudyStore(context).dailyNewWords)
        }
    }

    @Test fun exhaustedDailyQueueStillOffersNewWords() {
        val store = StudyStore(context)
        store.dailyNewWords = 0; store.onboardingDone = true; store.route = "quiz"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Учить ещё: 5 новых слов")).perform(scrollToPracticeControl(), click())
            onView(withText("Новое слово")).check(matches(isDisplayed()))
        }
    }

    @Test fun noDueReviewsStillAllowsFamiliarPractice() {
        val store = StudyStore(context)
        store.dailyNewWords = 0; store.onboardingDone = true
        store.studyWords.forEach { store.introduce(it) }
        val future = System.currentTimeMillis() + 86_400_000L
        store.tasks().forEach { store.studyEngine.state(it).apply { attempts = 1; due = future } }
        store.route = "quiz"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Сейчас повторений нет")).check(matches(isDisplayed()))
            onView(withText("Практиковать знакомые слова")).perform(scrollToPracticeControl(), click())
            onView(withText("Завершить занятие")).check(matches(isDisplayed()))
        }
    }
}
