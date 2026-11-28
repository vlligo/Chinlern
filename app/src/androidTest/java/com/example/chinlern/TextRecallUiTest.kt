package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test

class TextRecallUiTest {
    @Test fun selectsAChunkAndKeepsAssistedAndUnaidedReviewsSeparateFromVocabulary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val first = "我今天去图书馆。"
        val second = "她明天来学校。"
        val document = store.textLibrary.save("Recall fixture", first + second)
        store.textRecall.add(store.textLibrary.select(document, first.length, document.text.length))
        store.route = "words"
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText(document.title)).perform(click())
            onView(withContentDescription("Действия с текстом")).perform(click())
            onView(withText("Учить фрагмент")).perform(click())
            onView(withText("Фрагмент для запоминания")).check(matches(isDisplayed()))
            onView(withText(first)).check(matches(isDisplayed()))
            onView(withText("Сохранить фрагмент")).perform(click())
            onView(withText(first)).check(matches(isDisplayed()))
            onView(withText("Скрыть текст")).perform(scrollToLibraryControl(), click())
            onView(withText(first)).check(doesNotExist())
            onView(withText("Показать начало")).perform(click())
            onView(withText("我今天…")).check(matches(isDisplayed()))
            onView(withText("Ещё подсказка")).perform(click())
            onView(withText("Показать ответ")).perform(click())
            onView(withText(first)).check(matches(isDisplayed()))
            onView(withText("Вспомнил")).check(doesNotExist())
            onView(withText("С подсказкой")).perform(scrollToLibraryControl(), click())
            onView(withText(startsWith("Следующее повторение ·"))).check(matches(isDisplayed()))
            onView(withText("Готово")).perform(click())

            pressBack()
            onView(withText("Повторение текстов")).perform(click())
            onView(withText(containsString("К повторению · 1"))).check(matches(isDisplayed()))
            onView(withText("Начать повторение")).perform(click())
            onView(withText(second)).check(doesNotExist())
            onView(withText("Показать ответ")).perform(scrollToLibraryControl(), click())
            onView(withText(second)).check(matches(isDisplayed()))
            onView(withText("Вспомнил")).perform(scrollToLibraryControl(), click())
            onView(withText("Готово")).perform(click())
            onView(withText("Повторение текстов")).perform(click())
            onView(withText("Все фрагменты")).perform(click())
            onView(withText(containsString(first))).perform(click())
            onView(withText(containsString("С подсказкой · 1"))).check(matches(isDisplayed()))
        }
        val restored = StudyStore(context)
        val targets = restored.textRecall.queue().targets
        assertEquals(2, targets.size)
        assertEquals(1, targets.single { it.quote == first }.assistedReviews)
        val unaided = targets.single { it.quote == second }
        assertEquals(1, unaided.reviews)
        assertEquals(0, unaided.assistedReviews)
        assertEquals(before, restored.syncSnapshot())
    }
}
