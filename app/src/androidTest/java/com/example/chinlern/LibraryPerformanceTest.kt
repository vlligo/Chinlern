package com.example.chinlern

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import androidx.viewpager.widget.ViewPager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run fixtures only in an emulator or a separate test application, never a personal library. */
class LibraryPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(count: Int) {
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        StudyStore(context).apply {
            val seed = words.first()
            words = (0 until count).map { seed.copy(id = "word$it", hanzi = "水$it", meanings = listOf("перевод $it")) }.toMutableList()
            selected.clear(); selected.addAll(words.map { it.id })
            onboardingDone = true; route = "learn"; save()
        }
    }
    private fun data(activity: MainActivity) = MainActivity::class.java.getDeclaredField("store")
        .apply { isAccessible = true }.get(activity) as StudyStore
    private fun switch(activity: MainActivity, route: String) = MainActivity::class.java
        .getDeclaredMethod("switchDestination", String::class.java).apply { isAccessible = true }.invoke(activity, route)
    private fun awaitPage(scenario: ActivityScenario<MainActivity>) {
        val idle = CountDownLatch(1)
        val listener = object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager.SCROLL_STATE_IDLE) idle.countDown()
            }
        }
        fun pager(activity: MainActivity) = MainActivity::class.java.getDeclaredField("pageHost")
            .apply { isAccessible = true }.get(activity) as ViewPager
        scenario.onActivity {
            val page = pager(it)
            page.addOnPageChangeListener(listener)
            if (page.scrollX == page.currentItem * page.width) idle.countDown()
        }
        assertTrue("Page must finish settling before checking its recycled rows", idle.await(5, TimeUnit.SECONDS))
        scenario.onActivity { pager(it).removeOnPageChangeListener(listener) }
        val drawn = CountDownLatch(1)
        scenario.onActivity { activity -> pager(activity).postOnAnimation { pager(activity).post { drawn.countDown() } } }
        assertTrue("List layout must complete before checking recycled rows", drawn.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun list(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<ListView>().single()
    private fun jumpToRow(list: ListView, row: Int) {
        // ListView keeps keyboard selection separate from touch-mode scroll position.
        list.requestFocusFromTouch()
        list.setSelectionFromTop(row, 0)
    }
    private fun expectedRows(activity: MainActivity, words: Int): Int {
        val width = MainActivity::class.java.getDeclaredField("contentWidthDp")
            .apply { isAccessible = true }.getInt(activity)
        val columns = if (width >= 640 && activity.resources.configuration.fontScale <= 1.3f) 2 else 1
        return 1 + (words + columns - 1) / columns
    }
    private fun check(activity: MainActivity, description: String) = descendants(activity.window.decorView)
        .filterIsInstance<CheckBox>().singleOrNull { it.contentDescription == description }
        ?: error("Missing $description in rows ${list(activity).firstVisiblePosition}–${list(activity).lastVisiblePosition}")

    @Test fun measureOpening87Words() {
        fixture(87)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(4) { iteration ->
                var started = 0L
                var buildMs = 0L
                scenario.onActivity { activity ->
                    switch(activity, "learn")
                    started = SystemClock.elapsedRealtime()
                    switch(activity, "words")
                    buildMs = SystemClock.elapsedRealtime() - started
                }
                awaitPage(scenario)
                android.util.Log.i("LibraryPerformance", "87 words opening $iteration: build=${buildMs}ms, idle=${SystemClock.elapsedRealtime() - started}ms")
            }
        }
    }

    @Test fun allWordsAreReachableAndRecyclingDoesNotChangeSelectionsOrKnowledge() {
        fixture(87)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var before = emptyMap<String, String>()
            scenario.onActivity { activity ->
                before = data(activity).syncSnapshot()
                switch(activity, "words")
            }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                val list = list(activity)
                assertEquals(expectedRows(activity, 87), list.count) // One header plus all 87 words, without truncation.
                assertTrue("Only visible rows should exist", list.childCount < 10)
                jumpToRow(list, list.count - 1)
            }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                assertEquals(before, data(activity).syncSnapshot())
                check(activity, "Учить 水86 · Мои слова").performClick()
                check(activity, "Умею писать: 水86 · Мои слова").performClick()
                jumpToRow(list(activity), 1)
            }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                assertTrue(check(activity, "Учить 水0 · Мои слова").isChecked)
                assertFalse(check(activity, "Умею писать: 水0 · Мои слова").isChecked)
                list(activity).let { jumpToRow(it, it.count - 1) }
            }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                assertFalse(check(activity, "Учить 水86 · Мои слова").isChecked)
                assertTrue(check(activity, "Умею писать: 水86 · Мои слова").isChecked)
                switch(activity, "settings"); switch(activity, "words")
            }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                assertTrue(list(activity).firstVisiblePosition > 0)
                jumpToRow(list(activity), 0)
            }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                val search = descendants(activity.window.decorView).filterIsInstance<EditText>().single()
                search.setText("水86")
                assertEquals(2, list(activity).count)
                search.setText("no matches")
                assertEquals(1, list(activity).count)
                search.setText("")
                assertEquals(expectedRows(activity, 87), list(activity).count)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val pack = data(activity).libraryPacks().single()
                assertFalse("word86" in pack.selected)
                assertTrue("word86" in pack.writable)
                assertFalse("word0" in pack.writable)
                val root = activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                java.io.File(activity.cacheDir, "words-performance.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun thousandWordsStillCreateOnlyVisibleCards() {
        fixture(1000)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { switch(it, "words") }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                assertEquals(expectedRows(activity, 1000), list(activity).count)
                assertTrue(list(activity).childCount < 10)
                list(activity).let { jumpToRow(it, it.count - 1) }
            }
            awaitPage(scenario)
            scenario.onActivity { activity ->
                assertTrue(check(activity, "Учить 水999 · Мои слова").isChecked)
            }
        }
    }
}
