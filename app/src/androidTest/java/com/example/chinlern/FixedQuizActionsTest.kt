package com.example.chinlern

import android.content.Context
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on disposable emulator at phone and tablet sizes. */
@RunWith(AndroidJUnit4::class)
class FixedQuizActionsTest {
    @Test fun submitAndNextAreVisibleWithoutScrollingWithKeyboardOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.route = "quiz"; store.current = Task("water", Skill.CHARACTER_MEANING); store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("Русский перевод")).perform(click(), typeText("shui3"))
            var keyboardVisible = false
            repeat(20) {
                if (!keyboardVisible) {
                    Thread.sleep(100)
                    scenario.onActivity { activity ->
                        keyboardVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                }
            }
            assertTrue("Test must exercise an open keyboard", keyboardVisible)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "quiz-keyboard.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            onView(withText("Проверить")).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed()))
            onView(withText("Ответил случайно — не учитывать")).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed())).perform(click())
        }
    }
}
