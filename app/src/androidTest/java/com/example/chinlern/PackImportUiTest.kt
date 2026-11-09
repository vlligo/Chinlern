package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only end-to-end import; creates one disposable test pack. */
@RunWith(AndroidJUnit4::class)
class PackImportUiTest {
    @Test fun pastePreviewBackAndCreate() {
        val json = """{"name":"UI import test","words":[{"hanzi":"水","pinyin":"shui3","meanings":["вода"]}]}"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { MainActivity::class.java.getDeclaredMethod("pastePack").apply { isAccessible = true }.invoke(it) }
            onView(withHint("Ответ модели (JSON)")).inRoot(isDialog()).perform(replaceText("bad"), closeSoftKeyboard())
            onView(withText("Проверить")).inRoot(isDialog()).perform(click())
            onView(withHint("Ответ модели (JSON)")).inRoot(isDialog()).check(matches(withText("bad")))
            onView(withHint("Ответ модели (JSON)")).inRoot(isDialog()).perform(replaceText(json), closeSoftKeyboard())
            onView(withText("Проверить")).inRoot(isDialog()).perform(click())
            onView(withHint("Название набора")).inRoot(isDialog()).check(matches(withText("UI import test")))
            onView(withText("Назад")).inRoot(isDialog()).perform(click())
            onView(withHint("Ответ модели (JSON)")).inRoot(isDialog()).check(matches(withText(json)))
            onView(withText("Проверить")).inRoot(isDialog()).perform(click())
            onView(withText("Создать набор")).inRoot(isDialog()).perform(click())
            onView(withText("Ваши слова")).check(matches(isDisplayed()))
            scenario.onActivity { assertEquals("UI import test", StudyStore(it).packName) }
        }
    }
}
