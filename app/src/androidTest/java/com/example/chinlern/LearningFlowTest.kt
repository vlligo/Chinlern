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
    @Before fun resetTestData() { com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit() }
    @Test fun restoredFeedbackDropsUnavailableListeningQuestionWithoutChangingProgress() {
        val store = StudyStore(context)
        val word = Word("there", "那儿", "nàr", "nar4", listOf("там"), listOf("水", "木", "火"))
        store.importPack("Listening regression", listOf(word))
        store.introduce(word)
        store.audioProvider = "system"
        val task = Task(word.id, Skill.AUDIO_MEANING)
        assertTrue(task in store.tasks())
        store.current = task
        store.recordAnswer(true)
        val progress = store.studyEngine.state(task).copy()
        store.audioProvider = "hanping"
        store.route = "quiz"
        store.save()
        assertEquals(true, StudyStore(context).feedback)
        assertFalse(task in StudyStore(context).tasks())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val restored = StudyStore(context)
                assertNotEquals(task, restored.current)
                assertNull(restored.feedback)
                assertEquals(progress, restored.studyEngine.state(task))
            }
        }
    }
    @Test fun seedDeckAndProgressSurviveReload() {
        val store = StudyStore(context)
        assertEquals(16, store.words.size)
        store.words.forEach { StudyStore.validate(it) }
        val task = Task("water", Skill.CHARACTER_MEANING)
        store.introduce(store.words.first { it.id == "water" })
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
    @Test fun speechExampleSupportsRecallBeforeAndAfterTheQuestion() {
        val store = StudyStore(context)
        val word = store.words.first { it.id == "water" }
        store.route = "quiz"
        store.current = Task(word.id, Skill.CHARACTER_MEANING)
        store.save()

        fun checkExample() {
            onView(withText(word.example)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(word.exampleTranslation)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withText("Показать разбор")).perform(scrollTo(), click())
            for (text in listOf(word.examplePinyin, word.exampleTranslation, word.hint)) {
                onView(withText(text)).perform(scrollTo()).check(matches(isDisplayed()))
            }
            onView(withText("Скрыть разбор")).perform(scrollTo(), click())
            onView(withText(word.examplePinyin)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            checkExample()
            onView(withText("Запомнил — к вопросам")).perform(click())
            onView(withText(word.example)).check(doesNotExist())
            onView(withHint("Русский перевод")).perform(replaceText("вода"), closeSoftKeyboard())
            onView(withText("Проверить")).perform(click())
            checkExample()
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed()))
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
            assertEquals(Fsrs.Phase.LEARNING, StudyStore(context).engine.state(Task("water", Skill.WRITING)).memory!!.phase)
            assertFalse(StudyStore(context).engine.state(Task("water", Skill.CHARACTER_MEANING)).mastered)
        }
    }
    @Test fun tonePromptDoesNotRevealThePronunciation() {
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.current = Task("water", Skill.CHARACTER_TONE); store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("shuǐ")).check(doesNotExist())
            val thirdTone = onView(withContentDescription("3   mǎ  · нисходяще-восходящий"))
            if (context.resources.configuration.smallestScreenWidthDp >= 600) thirdTone.perform(scrollTo())
            thirdTone.check(matches(isCompletelyDisplayed())).perform(click())
            assertEquals(Fsrs.Phase.LEARNING, StudyStore(context).engine.state(Task("water", Skill.CHARACTER_TONE)).memory!!.phase)
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
            onView(withContentDescription("Послушать")).check(matches(isDisplayed()))
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
        assertEquals(Fsrs.Phase.LEARNING, restored.engine.state(fire).memory!!.phase)
        assertEquals(16, restored.words.size)
    }
    @Test fun malformedImportIsRejected() {
        try { StudyStore.decodeWords("[{\"hanzi\":\"水\"}]"); fail("Malformed import accepted") } catch (_: Exception) { }
        val words = StudyStore(context).words
        assertEquals(words, StudyStore.decodeWords(StudyStore.encodeWords(words)))
    }
}
