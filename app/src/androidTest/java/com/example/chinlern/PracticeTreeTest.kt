package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PracticeTreeTest {
    @Test fun foldersExpandIndependentlyAndCollapsingPreservesDraftSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val data = StudyStore(context)
        val original = data.activePackId
        data.importPack("Lesson one", listOf(data.words.first()))
        val lesson = data.activePackId
        data.movePack(lesson, "Lessons")
        data.importPack("Hidden list", listOf(data.words.first()))
        val hidden = data.activePackId
        data.movePack(hidden, "Lessons"); data.setPackHidden(hidden, true)
        data.choosePracticePacks(setOf(original))
        data.route = "words"; data.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun open(count: Int) = onView(withText("Наборы для практики: $count ▾")).perform(scrollTo(), click())
            fun folder(selected: Int) = onView(withContentDescription("Папка: Lessons, выбрано $selected из 1"))
            fun lessonRow() = onView(withContentDescription("Учить набор: Lessons / Lesson one"))
            open(1)
            lessonRow().check(doesNotExist())
            folder(0).perform(click())
            lessonRow().perform(click()).check(matches(isChecked()))
            onView(withText("Hidden list")).check(doesNotExist())
            folder(1).perform(click())
            lessonRow().check(doesNotExist())
            folder(1).perform(click())
            lessonRow().check(matches(isChecked()))
            folder(1).perform(click())
            onView(withText("Применить")).perform(click())
            assertEquals(setOf(original, lesson), StudyStore(context).practicePackIds)
            scenario.recreate()
            open(2)
            lessonRow().check(matches(isChecked())).perform(click())
            onView(withText("Отмена")).perform(click())
            assertEquals(setOf(original, lesson), StudyStore(context).practicePackIds)
            open(2)
            lessonRow().perform(click())
            onView(withContentDescription("Учить набор: Мои слова")).perform(click())
            onView(withText("Применить")).perform(click())
            onView(withText("Наборы для практики")).check(matches(isDisplayed()))
            assertEquals(setOf(original, lesson), StudyStore(context).practicePackIds)
            onView(withText("Отмена")).perform(click())
        }
    }
}
