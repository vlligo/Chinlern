package com.example.chinlern

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import androidx.viewpager.widget.ViewPager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Destructive fixture: emulator or isolated test application only. */
class SectionSwipeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun data(activity: MainActivity) = MainActivity::class.java.getDeclaredField("store")
        .apply { isAccessible = true }.get(activity) as StudyStore
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun host(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<SectionSwipeLayout>().single()
    private fun activePage(activity: MainActivity) = MainActivity::class.java.getDeclaredField("pageFrame")
        .apply { isAccessible = true }.get(activity) as View
    private fun navigate(activity: MainActivity, route: String) = MainActivity::class.java
        .getDeclaredMethod("switchDestination", String::class.java).apply { isAccessible = true }.invoke(activity, route)

    @Before fun fixture() {
        val context = instrumentation.targetContext
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        StudyStore(context).apply { onboardingDone = true; route = "learn"; save() }
    }

    private fun drag(view: View, dx: Float, dy: Float = 0f, cancel: Boolean = false, x: Float = view.width / 2f, y: Float = view.height / 2f) {
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            MotionEvent.obtain(started, started + (fraction * 640).toLong(), action,
                x + dx * fraction, y + dy * fraction, 0).let { view.dispatchTouchEvent(it); it.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..8) send(MotionEvent.ACTION_MOVE, step / 8f)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 1f)
    }

    private fun awaitSettled(scenario: ActivityScenario<MainActivity>) {
        val idle = CountDownLatch(1)
        val listener = object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager.SCROLL_STATE_IDLE) idle.countDown()
            }
        }
        scenario.onActivity {
            val page = host(it)
            page.addOnPageChangeListener(listener)
            if (page.scrollX == page.currentItem * page.width) idle.countDown()
        }
        assertTrue("Pager must finish settling", idle.await(5, TimeUnit.SECONDS))
        scenario.onActivity { host(it).removeOnPageChangeListener(listener) }
        instrumentation.waitForIdleSync()
    }

    private fun assertRoute(activity: MainActivity, expected: String) {
        assertEquals(expected, data(activity).route)
        views(activity.window.decorView).filterIsInstance<BottomNavigationView>().firstOrNull()?.let {
            assertEquals(listOf("learn", "words", "stats", "settings").indexOf(expected) + 1, it.selectedItemId)
        }
    }

    @Test fun bothLivePagesTrackTheFingerBeforeRelease() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val page = host(activity)
                val started = SystemClock.uptimeMillis()
                fun send(action: Int, time: Long, x: Float) {
                    MotionEvent.obtain(started, started + time, action, x, page.height * 0.45f, 0).let {
                        page.dispatchTouchEvent(it); it.recycle()
                    }
                }
                val x = page.width * 0.8f
                send(MotionEvent.ACTION_DOWN, 0, x)
                send(MotionEvent.ACTION_MOVE, 30, x - page.width * 0.08f)
                send(MotionEvent.ACTION_MOVE, 80, x - page.width * 0.35f)
                assertEquals("learn", data(activity).route)
                assertTrue("The pager must move before release", page.scrollX > page.width * 0.2f)
                assertTrue("Both neighbouring pages must already have live content", page.childCount >= 2)
                val nextPage = (0 until page.childCount).map { page.getChildAt(it) }.first { it.left >= page.width }
                assertTrue("The revealed page must be laid out before release", nextPage.width > 0 && nextPage.height > 0)
                val root = activity.window.decorView
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                java.io.File(activity.cacheDir, "section-drag.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                send(MotionEvent.ACTION_CANCEL, 100, x - page.width * 0.35f)
            }
        }
    }

    @Test fun swipesVisitAdjacentSectionsAndKeepTheLibrary() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var before = emptyMap<String, String>()
            scenario.onActivity { before = data(it).syncSnapshot() }
            listOf("words", "stats", "settings").forEach { route ->
                scenario.onActivity { drag(host(it), -host(it).width * 0.65f); assertRoute(it, route) }
                awaitSettled(scenario)
            }
            listOf("stats", "words", "learn").forEach { route ->
                scenario.onActivity { drag(host(it), host(it).width * 0.65f); assertRoute(it, route) }
                awaitSettled(scenario)
            }
            scenario.onActivity { assertEquals(before, data(it).syncSnapshot()) }
        }
    }

    @Test fun shortVerticalAndCancelledGesturesStayOnThePage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val page = host(it)
                drag(page, -page.width * 0.08f); assertRoute(it, "learn")
                drag(page, -page.width * 0.05f, -page.height * 0.4f); assertRoute(it, "learn")
                drag(page, -page.width * 0.4f, cancel = true); assertRoute(it, "learn")
                navigate(it, "words"); assertRoute(it, "words")
            }
        }
    }

    @Test fun endsDoNotWrapAndQuizDoesNotSwitch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                drag(host(it), host(it).width * 0.65f); assertRoute(it, "learn")
                navigate(it, "settings")
            }
            awaitSettled(scenario)
            scenario.onActivity {
                drag(host(it), -host(it).width * 0.65f); assertRoute(it, "settings")
                data(it).route = "quiz"
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(it)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                drag(host(it), -host(it).width * 0.65f)
                assertEquals("quiz", data(it).route)
            }
        }
    }

    @Test fun returningToASectionRestoresScroll() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var position = 0
            scenario.onActivity {
                views(activePage(it)).filterIsInstance<ScrollView>().single().apply {
                    scrollTo(0, 200); position = scrollY
                }
                assertTrue(position > 0)
                drag(host(it), -host(it).width * 0.65f)
            }
            awaitSettled(scenario)
            scenario.onActivity { drag(host(it), host(it).width * 0.65f) }
            awaitSettled(scenario)
            scenario.onActivity { assertEquals(position, views(activePage(it)).filterIsInstance<ScrollView>().single().scrollY) }
        }
    }

    @Test fun progressRecyclesDifficultCardsAndRetainsItsScrollPosition() {
        StudyStore(instrumentation.targetContext).apply {
            val seed = words.first()
            words = (0 until 110).map { index ->
                seed.copy(id = "word${1000 + index}", hanzi = "水$index", meanings = listOf("перевод $index"))
            }.toMutableList()
            selected.clear()
            selected.addAll(words.map { it.id })
            words.forEach { engine.progress[Task(it.id, Skill.WRITING).key] = Progress(attempts = 1, errors = 1) }
            save()
        }
        fun list(activity: MainActivity) = views(activePage(activity)).filterIsInstance<ListView>().single()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { navigate(it, "stats") }
            awaitSettled(scenario)
            scenario.onActivity { activity ->
                val visibleCards = views(activePage(activity)).filterIsInstance<TextView>()
                    .count { it.text.startsWith("На повторении · Ошибок") }
                android.util.Log.i("OverviewPerformance", "Difficult-card views attached on open: $visibleCards")
                assertTrue("Offscreen difficult cards should not be attached", visibleCards < 20)
                list(activity).apply {
                    assertTrue(footerViewsCount > 0)
                    requestFocusFromTouch()
                    setSelectionFromTop(count - footerViewsCount - 1, 0)
                }
            }
            awaitSettled(scenario)
            var firstRow = 0
            scenario.onActivity {
                val list = list(it)
                assertTrue(views(list).filterIsInstance<TextView>().any { label -> label.text.startsWith("水99  ·  ") })
                assertTrue(views(list).filterIsInstance<TextView>().any { label -> label.text.startsWith("Исключено неоднозначных направлений") })
                firstRow = list.firstVisiblePosition
                assertTrue(firstRow > 0)
                navigate(it, "settings")
            }
            awaitSettled(scenario)
            scenario.onActivity { navigate(it, "stats") }
            awaitSettled(scenario)
            scenario.onActivity { assertEquals(firstRow, list(it).firstVisiblePosition) }
        }
    }

    @Test fun libraryEditRefreshesProgressAfterAnImmediateSwipe() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var settings: View
            scenario.onActivity { navigate(it, "settings") }
            awaitSettled(scenario)
            scenario.onActivity {
                settings = activePage(it)
                navigate(it, "words")
            }
            awaitSettled(scenario)
            scenario.onActivity { activity ->
                val list = views(activePage(activity)).filterIsInstance<ListView>().single()
                list.requestFocusFromTouch()
                list.setSelectionFromTop(1, 0)
            }
            awaitSettled(scenario)
            scenario.onActivity { activity ->
                views(activePage(activity)).filterIsInstance<CheckBox>()
                    .first { it.contentDescription == "Умею писать: 水 · Мои слова" }.performClick()
                drag(host(activity), -host(activity).width * 0.65f)
            }
            awaitSettled(scenario)
            scenario.onActivity { activity ->
                assertRoute(activity, "stats")
                assertTrue(views(activePage(activity)).filterIsInstance<TextView>().any { it.text.startsWith("Написание: 1 /") })
                navigate(activity, "settings")
            }
            awaitSettled(scenario)
            scenario.onActivity { assertSame(settings, activePage(it)) }
        }
    }

    @Test fun swipingInsideSearchKeepsTextEditing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { navigate(it, "words") }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val page = host(it)
                val input = views(activePage(it)).filterIsInstance<EditText>().first()
                val pageLocation = IntArray(2); page.getLocationOnScreen(pageLocation)
                val inputLocation = IntArray(2); input.getLocationOnScreen(inputLocation)
                drag(page, -page.width * 0.35f,
                    x = inputLocation[0] - pageLocation[0] + input.width * 0.8f,
                    y = inputLocation[1] - pageLocation[1] + input.height / 2f)
                assertRoute(it, "words")
            }
        }
    }
}
