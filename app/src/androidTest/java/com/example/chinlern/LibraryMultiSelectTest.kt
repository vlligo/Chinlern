package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMultiSelectTest {
    @Test fun wordsPageSelectsMultiplePacksAndRetainsSelectionOnRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = StudyStore(context)
        store.importPack("Second", listOf(store.words.first()))
        store.route = "words"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Наборы для практики: 1 ▾")).perform(scrollTo(), click())
            onView(withText("Мои слова")).perform(click())
            onView(withText("Применить")).perform(click())
            onView(withText("Ваши слова")).check(matches(isDisplayed()))
            onView(withText("Наборы для практики: 2 ▾")).check(matches(isDisplayed()))
            onView(withText("Снять выбор")).perform(scrollTo(), click())
            assertTrue(StudyStore(context).libraryPacks().all { it.selected.isEmpty() })
            onView(withText("Выбрать все")).perform(scrollTo(), click())
            assertEquals(17, StudyStore(context).studyWords.size)
            scenario.recreate()
            onView(withText("Наборы для практики: 2 ▾")).check(matches(isDisplayed()))
            onView(withHint("Найти слово, пиньинь или перевод")).perform(scrollTo(), replaceText("Second"), closeSoftKeyboard())
            onView(withContentDescription("Учить 水 · Second")).perform(scrollTo(), click())
            val restored = StudyStore(context)
            assertEquals(16, restored.studyWords.size)
            assertTrue(restored.libraryPacks().first { it.name == "Мои слова" }.selected.contains("water"))
        }
    }
}
