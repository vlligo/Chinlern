package com.example.chinlern

import android.content.Context
import android.graphics.Rect
import android.view.ViewGroup
import android.widget.ScrollView
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Run on disposable emulator at phone and tablet sizes. */
@RunWith(AndroidJUnit4::class)
class FixedQuizActionsTest {
    @Test fun allFiveTonesStayPutAcrossPicksAndReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = StudyStore(context)
        val word = Word("longTonePrompt", "水".repeat(30), List(30) { "shuǐ" }.joinToString(" "),
            List(30) { "shui3" }.joinToString(" "), listOf("вода"), listOf("火", "木", "土"))
        store.importPack("Tone test", listOf(word))
        store.introduce(word)
        val task = Task(word.id, Skill.CHARACTER_TONE)
        store.beginSession(focus = listOf(task))
        store.route = "quiz"; store.current = task; store.save()

        val choices = listOf("1   mā  · ровный", "2   má  · восходящий", "3   mǎ  · нисходяще-восходящий",
            "4   mà  · нисходящий", "5   ma  · нейтральный")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                if (activity.resources.configuration.smallestScreenWidthDp >= 600) return@onActivity
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val density = activity.resources.displayMetrics.density
                root.layoutParams = root.layoutParams.apply {
                    width = minOf(root.width, (320 * density).toInt())
                    height = minOf(root.height, (480 * density).toInt())
                }
            }
            val scroll = onView(withTagValue(org.hamcrest.Matchers.`is`("questionScroll" as Any)))
            var offset = 0
            scroll.check { view, _ ->
                (view as ScrollView).scrollTo(0, view.height)
                offset = view.scrollY
                if (context.resources.configuration.smallestScreenWidthDp < 600) assertTrue("Exercise an already scrolled prompt", offset > 0)
            }
            fun bounds() = choices.map { title ->
                val rect = Rect()
                onView(withContentDescription(title)).check(matches(isCompletelyDisplayed())).check { view, _ ->
                    assertTrue(view.getGlobalVisibleRect(rect))
                }
                rect
            }
            val initial = bounds()
            fun checkStable() {
                assertEquals(initial, bounds())
                scroll.check { view, _ -> assertEquals(offset, view.scrollY) }
            }
            repeat(3) {
                for (title in choices) {
                    onView(withContentDescription(title)).perform(click())
                    checkStable()
                }
            }
            onView(withText("Слог 16 из 30")).check(matches(isCompletelyDisplayed()))
            onView(withContentDescription("Выбрать тоны заново")).perform(click())
            checkStable()
            onView(withText("Слог 1 из 30")).check(matches(isCompletelyDisplayed()))
        }
    }

    @Test fun submitAndNextAreVisibleWithoutScrollingWithKeyboardOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = StudyStore(context)
        store.introduce(store.words.first { it.id == "water" }) // These regressions exercise an already-previewed word.
        store.beginSession()
        store.route = "quiz"; store.current = Task("water", Skill.CHARACTER_MEANING); store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var quizScreen: android.view.View
            scenario.onActivity { quizScreen = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
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
            onView(withContentDescription("Текущий вопрос: 水")).check(matches(isCompletelyDisplayed())).check(matches(withText("水")))
            onView(withHint("Русский перевод")).check(matches(isCompletelyDisplayed()))
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "quiz-keyboard.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            onView(withText("Проверить")).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed()))
            scenario.onActivity { assertSame(quizScreen, it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) }
            onView(withContentDescription("Ответил случайно — не учитывать")).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed())).perform(click())
        }
    }
}
