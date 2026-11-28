package com.example.chinlern

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on disposable emulators; includes phone, tablet and large-font configurations. */
@RunWith(AndroidJUnit4::class)
class ReachableListeningTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store(activity: MainActivity) = MainActivity::class.java.getDeclaredField("store")
        .apply { isAccessible = true }.get(activity) as StudyStore
    private fun render(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("render")
        .apply { isAccessible = true }.invoke(activity)
    private fun seed(): StudyStore {
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        return StudyStore(context).apply {
            audioEnabled = true; audioProvider = "hanping"; speakAfterAnswer = false
            introduce(words.first { it.id == "water" })
            introduce(words.first { it.id == "fire" })
            route = "learn"; save()
        }
    }
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    @Test fun listeningAutoplaysOnceAndReplayStillWorks() {
        seed()
        val sent = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("voice").apply { isAccessible = true }
                (field.get(activity) as Pronunciation).close()
                field.set(activity, Pronunciation(activity, {}) { sent += it.getStringExtra("s")!! })
                store(activity).apply { route = "quiz"; current = Task("water", Skill.AUDIO_MEANING); save() }
                render(activity)
            }
            idle()
            scenario.onActivity { activity ->
                assertEquals(listOf("水"), sent)
                repeat(3) { render(activity) }
            }
            idle()
            scenario.onActivity { assertEquals("A redraw must not replay", listOf("水"), sent) }
            onView(withContentDescription("Послушать")).perform(scrollTo(), click())
            Thread.sleep(2100)
            scenario.onActivity { activity ->
                assertEquals(listOf("水", "水"), sent)
                store(activity).apply { current = Task("fire", Skill.AUDIO_CHARACTER); choices = listOf("火", "水", "木", "人") }
                render(activity)
            }
            idle(); Thread.sleep(2100)
            scenario.onActivity { activity ->
                assertEquals(listOf("水", "水", "火"), sent)
                store(activity).apply { current = Task("water", Skill.AUDIO_MEANING) }
                render(activity)
                store(activity).route = "learn"
                render(activity)
            }
            idle(); Thread.sleep(2100)
            scenario.onActivity { assertEquals("Leaving must cancel pending automatic audio", listOf("水", "水", "火"), sent) }
        }
    }

    @Test fun bothAnswerColumnsStayInTheBottomDockAndSubmit() {
        seed().apply { route = "quiz"; current = Task("water", Skill.MEANING_CHARACTER)
            choices = listOf("水", "火", "木", "人", "口", "日"); save() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val positions = mutableListOf<Rect>()
            for (text in listOf("水", "火", "木", "人", "口", "日")) {
                onView(withText(text)).check(matches(isCompletelyDisplayed())).check { view, _ ->
                    var parent = view.parent
                    while (parent is View && parent.tag != "quizActions") parent = parent.parent
                    assertTrue("Answer belongs to bottom controls", parent is View && parent.tag == "quizActions")
                    val rect = Rect(); assertTrue(view.getGlobalVisibleRect(rect)); positions += rect
                }
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val bounds = Rect(); root.getGlobalVisibleRect(bounds)
                assertTrue("Answers are in lower half", positions.first().centerY() > bounds.centerY())
                assertTrue("Left choices extend to the left grip", positions[0].left < bounds.left + bounds.width() / 5)
                assertTrue("Right choices extend to the right grip", positions[1].right > bounds.right - bounds.width() / 5)
                capture(activity, "choice-dock.png")
            }
            onView(withText("水")).perform(click())
            onView(withText("Дальше")).check(matches(isCompletelyDisplayed()))
            onView(withContentDescription("Ещё")).perform(click())
            onView(withText("Порядок черт")).check(matches(isDisplayed()))
            onView(withContentDescription("Закрыть меню")).perform(scrollTo(), click())
        }
    }

    @Test fun largeChoicesAndSmallWindowRemainScrollableWithoutLosingPrompt() {
        val longForm = "水".repeat(30)
        seed().apply {
            route = "quiz"; current = Task("water", Skill.MEANING_CHARACTER)
            choices = listOf(longForm, "火".repeat(30), "木".repeat(30), "人".repeat(30)); save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val scale = activity.resources.displayMetrics.density
                root.layoutParams = root.layoutParams.apply { width = (320 * scale).toInt(); height = (480 * scale).toInt() }
            }
            onView(withText(longForm)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Не знаю — показать ответ")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
            scenario.onActivity { activity ->
                val prompt = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("questionScroll")
                assertTrue("Prompt keeps its own visible space", prompt.height > 0)
                capture(activity, "small-choice-dock.png")
            }
            onView(withText("Не знаю — показать ответ")).perform(click())
            onView(withText("Дальше")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
        }
    }

    private fun capture(activity: MainActivity, name: String) {
        val view = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File(context.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
