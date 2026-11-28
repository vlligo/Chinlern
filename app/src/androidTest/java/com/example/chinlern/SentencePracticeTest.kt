package com.example.chinlern

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import com.example.chinlern.texts.ReaderSentences
import com.example.chinlern.texts.SentencePractice
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePracticeTest {
    @Test fun keepsQuotesAndSupplementaryCharactersWithTheirSentences() {
        val text = "  他问：“𠀀是生字吗？”\r\n\r\n 是的！\n"
        val spans = ReaderSentences.spans(text)
        assertEquals(listOf("他问：“𠀀是生字吗？”", "是的！"), spans.map { text.substring(it.start, it.end) })
        assertTrue(ReaderSentences.spans("…！\n ").isEmpty())
    }

    @Test fun playsOnlyTheCurrentSentenceAndStopsWhenLeavingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val library = StudyStore(context).textLibrary
        val first = "你好吗？"
        val second = "我今天和朋友一起去图书馆".repeat(60) + "！"
        val document = library.save("Playback fixture", first + second)
        val audio = mutableListOf<String>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                SentencePractice(activity, library, { audio.add(it) }, { audio.add("stop") },
                    { dialog, dismissed -> dialog.setOnDismissListener { dismissed() } })
                    .show(document, 0, { error("Unexpected jump") }, { error("Unexpected recall") })
            }
            onView(withContentDescription("Предыдущее предложение")).inRoot(isDialog()).check(matches(not(isEnabled())))
            val arrows = arrowLocations()
            onView(withContentDescription("Прослушать")).inRoot(isDialog()).perform(click())
            onView(withContentDescription("Следующее предложение")).inRoot(isDialog()).perform(click())
            assertEquals(arrows, arrowLocations())
            onView(withText("Скрыть текст")).inRoot(isDialog()).perform(click())
            assertEquals(arrows, arrowLocations())
            onView(withContentDescription("Прослушать")).inRoot(isDialog()).perform(click())
            onView(withContentDescription("Назад")).inRoot(isDialog()).perform(click())
            scenario.onActivity {
                assertEquals(listOf(first, "stop", second, "stop"), audio)
            }
        }
    }

    @Test fun startsAtTheReadingPositionAndReturnsToTheChosenSentenceWithoutRecordingReviews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val first = "我今天去图书馆。"
        val second = "她明天来学校。"
        val third = "我们一起看书。"
        val document = store.textLibrary.save("Sentence practice", first + second + third)
        store.textLibrary.saveReadingPosition(document, first.length)
        store.route = "words"
        store.save()
        val before = store.syncSnapshot()

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText(document.title)).perform(click())
            onView(withContentDescription("Действия с текстом")).perform(click())
            onView(withText("Чтение по предложениям")).perform(click())
            onView(withText("Предложение 2 из 3")).check(matches(isDisplayed()))
            onView(withText(second)).check(matches(isDisplayed()))
            onView(withText("Скрыть текст")).perform(click())
            onView(withText(second)).check(matches(not(isDisplayed())))
            onView(withContentDescription("Следующее предложение")).perform(click()).check(matches(not(isEnabled())))
            onView(withText("Предложение 3 из 3")).check(matches(isDisplayed()))
            onView(withText(third)).check(matches(not(isDisplayed())))
            onView(withText("Показать текст")).perform(click())
            onView(withText(third)).check(matches(isDisplayed()))
            onView(withContentDescription("Предыдущее предложение")).perform(click())
            onView(withText(second)).check(matches(isDisplayed()))
            onView(withContentDescription("Следующее предложение")).perform(click())
            onView(withText("Открыть в тексте")).perform(click())
            onView(withText(document.text)).check(matches(isDisplayed()))
            onView(withContentDescription("Назад")).perform(click())
        }

        val restored = StudyStore(context)
        assertEquals(first.length + second.length, restored.textLibrary.readingPosition(document))
        assertTrue(restored.textRecall.queue().targets.isEmpty())
        assertEquals(before, restored.syncSnapshot())
    }

    @Test fun memorizesTheSelectedSentenceAndSchedulesAnAssistedReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val first = "我今天去图书馆。"
        val second = "她明天来学校。"
        val original = store.textLibrary.save("Sentence recall", first + second)
        val edited = store.textLibrary.save(original.title, "他今天去书店。", previous = original)
        store.route = "words"
        store.save()
        val before = store.syncSnapshot()

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText(edited.title)).perform(click())
            onView(withContentDescription("Действия с текстом")).perform(click())
            onView(withText("Версии текста")).perform(click())
            onView(withText("1. ${original.title}")).perform(click())
            onView(withContentDescription("Действия с текстом")).perform(click())
            onView(withText("Чтение по предложениям")).perform(click())
            onView(withContentDescription("Следующее предложение")).perform(click())
            onView(withText("Запомнить предложение")).perform(click())
            onView(withText(second)).check(matches(isDisplayed()))
            onView(withText("Скрыть текст")).perform(click())
            onView(withText("Показать начало")).perform(click())
            onView(withText("她明天…")).check(matches(isDisplayed()))
            onView(withText("Показать ответ")).perform(click())
            onView(withText("С подсказкой")).perform(scrollToLibraryControl(), click())
            onView(withText("Готово")).perform(click())
            onView(withText("Предложение 2 из 2")).check(matches(isDisplayed()))
        }

        val restored = StudyStore(context)
        val target = restored.textRecall.queue().targets.single()
        assertEquals(original.revision, target.revision)
        assertEquals(first.length, target.start)
        assertEquals(second, target.quote)
        assertEquals(1, target.reviews)
        assertEquals(1, target.assistedReviews)
        assertTrue(target.due > 0)
        assertEquals(before, restored.syncSnapshot())
    }

    private fun arrowLocations() = listOf("Предыдущее предложение", "Следующее предложение").map { label ->
        val location = IntArray(2)
        onView(withContentDescription(label)).inRoot(isDialog()).check { view: View, failure ->
            if (failure != null) throw failure
            view.getLocationOnScreen(location)
        }
        location.toList()
    }
}
