package com.example.chinlern

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
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
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
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
