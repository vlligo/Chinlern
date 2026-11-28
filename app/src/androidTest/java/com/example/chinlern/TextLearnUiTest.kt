package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import com.example.chinlern.texts.ReaderSentences
import com.example.chinlern.texts.TextRecall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLearnUiTest {
    @Test fun resumesAtTheFirstUnratedSentenceAndReusesExistingProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val first = "我今天去图书馆。"
        val second = "她明天来学校。"
        val third = "我们一起看书。"
        val document = store.textLibrary.save("Guided recall", first + second + third)
        val learned = store.textRecall.add(store.textLibrary.select(document, first.length + second.length, document.text.length))
        store.textRecall.record(learned, "prior-review", Fsrs.Rating.GOOD, assisted = false)
        store.route = "words"
        val before = store.syncSnapshot()

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            val learn = onView(withContentDescription("Учить текст «${document.title}»"))
            learn.perform(click())
            onView(withText("Фрагмент 1 из 3")).check(matches(isDisplayed()))
            onView(withText(first)).check(matches(isDisplayed()))
            grade()
            onView(withText("Следующий фрагмент")).perform(click())
            onView(withText(second)).check(matches(isDisplayed()))
            pressBack()
            assertEquals(setOf(first, third), StudyStore(context).textRecall.queue().targets.map { it.quote }.toSet())

            learn.perform(click())
            onView(withText("Фрагмент 2 из 3")).check(matches(isDisplayed()))
            onView(withText(second)).check(matches(isDisplayed()))
            grade()
            onView(withText("Готово")).perform(click())
            learn.perform(click())
            onView(withText("Все фрагменты пройдены.")).check(matches(isDisplayed()))
        }

        val restored = StudyStore(context)
        val targets = restored.textRecall.queue().targets
        assertEquals(listOf(first, second, third), targets.sortedBy { it.start }.map { it.quote })
        assertTrue(targets.all { it.reviews == 1 && it.revision == document.revision })
        assertEquals("prior-review", targets.single { it.id == learned.id }.reviewId)
        assertEquals(before, restored.syncSnapshot())
    }

    @Test fun splitsLongSentencesOnGraphemeBoundaries() {
        val first = "我".repeat(TextRecall.MAX_LENGTH - 1)
        val second = "𠀀\uFE00是生字。"
        val text = first + second
        assertEquals(listOf(first, second), ReaderSentences.forRecall(text).map { text.substring(it.start, it.end) })
    }

    private fun grade() {
        onView(withText("Скрыть текст")).perform(click())
        onView(withText("Показать ответ")).perform(click())
        onView(withText("Вспомнил")).perform(scrollToLibraryControl(), click())
    }
}
