package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyActivityStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun summary(store: StudyStore) = store.activitySummary()

    @Test fun answersPersistAndUndoRemovesActivityAfterReload() {
        var store = StudyStore(context)
        val word = store.words.first()
        store.introduce(word)
        store.beginExtraStudy(false, true); store.nextQuestion(); store.recordAnswer(true)
        store = StudyStore(context)
        assertEquals(1, summary(store).streak)
        assertEquals(1L, summary(store).writing)
        assertTrue(store.ignoreAnswer())
        store = StudyStore(context)
        assertEquals(0, summary(store).streak)
        assertEquals(0L, summary(store).answers)
    }

    @Test fun backupAndSharedPacksKeepSingleActivityCount() {
        val store = StudyStore(context)
        val word = store.words.first()
        store.introduce(word)
        store.beginExtraStudy(false, true); store.nextQuestion(); store.recordAnswer(true)
        store.leaveSession()
        store.importPack("Duplicate", listOf(word))
        assertEquals(1L, summary(store).answers)
        val backup = store.exportBackup()
        store.importBackup(store.inspectBackup(backup))
        assertEquals(1L, summary(store).answers)
        assertEquals(1, summary(store).days.values.single().answers)
    }

    @Test fun calendarUpdatesLocallyAndShowsDayDetailsInline() {
        val store = StudyStore(context)
        store.onboardingDone = true
        store.introduce(store.words.first())
        store.beginExtraStudy(false, true); store.nextQuestion(); store.recordAnswer(true)
        store.leaveSession(); store.route = "stats"; store.save()
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val calendar = root.findViewWithTag<android.widget.LinearLayout>("activity-calendar")
                val parent = calendar.parent
                val allTime = descendants(root).filterIsInstance<android.widget.TextView>().first { it.text == "За всё время · вся библиотека" }
                val today = StudyActivity.date(System.currentTimeMillis())
                val cell = calendar.findViewWithTag<android.widget.TextView>(today)
                cell.performClick()
                val details = calendar.findViewWithTag<android.widget.TextView>("activity-day-details")
                assertTrue(details.text.toString().contains("Ответов: 1 · Верно: 1 · 100%"))
                assertTrue(cell.isSelected)
                val timings = mutableListOf<Long>()
                repeat(12) {
                    val previous = descendants(calendar).filterIsInstance<android.widget.TextView>().first { it.text == "← Предыдущий месяц" }
                    val started = android.os.SystemClock.elapsedRealtimeNanos()
                    previous.performClick()
                    timings += (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                    assertSame(calendar, root.findViewWithTag("activity-calendar"))
                    assertSame(parent, calendar.parent)
                    assertTrue(allTime.isAttachedToWindow)
                    assertSame(allTime, descendants(root).filterIsInstance<android.widget.TextView>().first { it.text == "За всё время · вся библиотека" })
                    val firstDay = descendants(calendar).filterIsInstance<android.widget.TextView>().first { (it.tag as? String)?.endsWith("-01") == true }
                    firstDay.performClick()
                    assertTrue(calendar.findViewWithTag<android.widget.TextView>("activity-day-details").text.contains("занятий не было"))
                    descendants(calendar).filterIsInstance<android.widget.TextView>().first { it.text == "Следующий месяц →" }.performClick()
                }
                android.util.Log.i("CalendarPerformance", "Month update median: ${timings.sorted()[timings.size / 2]} ms; max: ${timings.max()} ms")
                assertFalse(descendants(calendar).filterIsInstance<android.widget.TextView>().first { it.text == "Следующий месяц →" }.isEnabled)
            }
        }
    }

    @Test fun statisticsScreensRenderWithActivity() {
        val store = StudyStore(context)
        store.onboardingDone = true
        store.introduce(store.words.first())
        store.beginExtraStudy(false, true); store.nextQuestion(); store.recordAnswer(true)
        store.leaveSession(); store.route = "stats"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.allOf(androidx.test.espresso.matcher.ViewMatchers.withText("好好学习，天天向上"), androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.allOf(androidx.test.espresso.matcher.ViewMatchers.withText("Серия: 1 дн. · Лучшая: 1 дн."), androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(activity.cacheDir, "study-statistics.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
