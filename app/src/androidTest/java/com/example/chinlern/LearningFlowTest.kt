package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun resetTestData() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun seedDeckAndProgressSurviveReload() {
        val store = StudyStore(context)
        assertEquals(16, store.words.size)
        store.words.forEach { StudyStore.validate(it) }
        val task = Task("water", Skill.CHARACTER_MEANING)
        store.engine.answer(task, false, delay = 5)
        store.current = task; store.feedback = false; store.draft = "тест"; store.beforeAnswer = Progress()
        store.save()
        val restored = StudyStore(context)
        assertEquals(1, restored.engine.state(task).errors)
        assertEquals(task, restored.current); assertEquals(false, restored.feedback); assertEquals("тест", restored.draft)
        assertEquals(16, StudyStore.decodeWords(StudyStore.encodeWords(restored.words)).size)
    }
    @Test fun typedAnswerFeedbackAndRotationRemainConsistent() {
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.current = Task("water", Skill.CHARACTER_MEANING); store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("Русский перевод")).perform(replaceText("вода"), closeSoftKeyboard())
            onView(withText("Проверить")).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("✓  Верно")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText("✓  Верно")).check(matches(isDisplayed()))
            assertEquals(1, StudyStore(context).engine.state(Task("water", Skill.CHARACTER_MEANING)).attempts)
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed())).perform(click())
        }
    }
    @Test fun missingToneFails() {
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.current = Task("water", Skill.CHARACTER_PINYIN); store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withHint("Пиньинь с тонами")).perform(replaceText("shui"), closeSoftKeyboard())
            onView(withText("Проверить")).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("Неверно")).check(matches(isDisplayed()))
        }
        assertEquals(1, StudyStore(context).engine.state(Task("water", Skill.CHARACTER_PINYIN)).errors)
    }
    @Test fun writingRevealAndSelfAssessment() {
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.writing = true; store.current = Task("water", Skill.WRITING); store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Показать ответ")).check(matches(isCompletelyDisplayed())).perform(click())
            scenario.recreate()
            onView(withText("Помню")).check(matches(isCompletelyDisplayed())).perform(click())
            assertTrue(StudyStore(context).engine.state(Task("water", Skill.WRITING)).mastered)
            assertFalse(StudyStore(context).engine.state(Task("water", Skill.CHARACTER_MEANING)).mastered)
        }
    }
    @Test fun tonePromptDoesNotRevealThePronunciation() {
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.current = Task("water", Skill.CHARACTER_TONE); store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("shuǐ")).check(doesNotExist())
            onView(withText("3   mǎ  · нисходяще-восходящий")).perform(scrollTo(), click())
            assertTrue(StudyStore(context).engine.state(Task("water", Skill.CHARACTER_TONE)).mastered)
        }
    }
    @Test fun audioPromptKeepsMeaningAndHanziHidden() {
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.current = Task("water", Skill.AUDIO_MEANING); store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("вода")).check(doesNotExist())
            onView(withText("水")).check(doesNotExist())
            onView(withText("shuǐ")).check(doesNotExist())
            onView(withText("▶  Послушать")).check(matches(isDisplayed()))
        }
    }
    @Test fun bulkImportResetsOnlyReplacedCards() {
        val store = StudyStore(context)
        val water = Task("water", Skill.CHARACTER_MEANING)
        val fire = Task("fire", Skill.CHARACTER_MEANING)
        store.engine.answer(water, true); store.engine.answer(fire, true)
        store.replaceWords(listOf(store.words.first { it.id == "water" }.copy(meanings = listOf("вода", "водичка"))))
        val restored = StudyStore(context)
        assertFalse(restored.engine.state(water).mastered)
        assertTrue(restored.engine.state(fire).mastered)
        assertEquals(16, restored.words.size)
    }
    @Test fun malformedImportIsRejected() {
        try { StudyStore.decodeWords("[{\"hanzi\":\"水\"}]"); fail("Malformed import accepted") } catch (_: Exception) { }
        val words = StudyStore(context).words
        assertEquals(words, StudyStore.decodeWords(StudyStore.encodeWords(words)))
    }
}
