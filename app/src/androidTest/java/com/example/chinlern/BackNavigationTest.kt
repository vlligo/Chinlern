package com.example.chinlern

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.BackEventCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The runner restricts these data-resetting fixtures to disposable emulators. */
class BackNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun store(activity: MainActivity) = field(activity, "store") as StudyStore
    private fun host(activity: MainActivity) = field(activity, "pageHost") as SectionSwipeLayout
    private fun page(activity: MainActivity) = field(activity, "pageFrame") as View
    private fun render(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("render")
        .apply { isAccessible = true }.invoke(activity)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun event(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) = BackEventCompat(0f, 300f, progress, edge)

    @Before fun fixture() {
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        StudyStore(context).apply { onboardingDone = true; route = "learn"; save() }
    }

    private fun settled(scenario: ActivityScenario<MainActivity>) {
        val ready = CountDownLatch(1)
        scenario.onActivity { it.window.decorView.postDelayed({ ready.countDown() }, 300) }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun assertRestored(view: View) {
        assertEquals(0f, view.translationX, .001f)
        assertEquals(1f, view.scaleX, .001f)
        assertEquals(1f, view.scaleY, .001f)
        assertEquals(1f, view.alpha, .001f)
    }

    @Test fun systemBackReusesPagesAndKeepsScrollAndSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settled(scenario)
            lateinit var originalHost: View
            lateinit var home: View
            lateinit var library: View
            var scroll = 0
            scenario.onActivity {
                originalHost = host(it); home = page(it)
                views(home).filterIsInstance<ScrollView>().single().apply { scrollTo(0, 180); scroll = scrollY }
                assertTrue(scroll > 0)
                host(it).setCurrentItem(1, false)
                library = page(it)
                views(library).filterIsInstance<EditText>().single().setText("水")
                library.requestFocus()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settled(scenario)
            scenario.onActivity {
                assertEquals("learn", store(it).route)
                assertSame(originalHost, host(it)); assertSame(home, page(it))
                assertEquals(scroll, views(home).filterIsInstance<ScrollView>().single().scrollY)
                assertRestored(home)
                host(it).setCurrentItem(1, false)
                assertSame(library, page(it))
                assertEquals("水", views(library).filterIsInstance<EditText>().single().text.toString())
            }
        }
    }

    @Test fun predictiveCancellationFromEitherEdgeDoesNotNavigateOrChangeData() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settled(scenario)
            for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                scenario.onActivity {
                    host(it).setCurrentItem(3, false)
                    val before = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).all.toMap()
                    it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f, edge))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.7f, edge))
                    assertTrue(host(it).scaleX < 1f)
                    assertTrue(if (edge == BackEventCompat.EDGE_LEFT) host(it).translationX > 0 else host(it).translationX < 0)
                    it.onBackPressedDispatcher.dispatchOnBackCancelled()
                    assertEquals("settings", store(it).route)
                    assertEquals(before, context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).all)
                }
                settled(scenario)
                scenario.onActivity { assertRestored(host(it)) }
            }
        }
    }

    @Test fun predictiveCommitAndImmediateNewNavigationResetTransforms() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settled(scenario)
            scenario.onActivity {
                val original = host(it)
                host(it).setCurrentItem(3, false)
                it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f))
                it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.8f))
                it.onBackPressedDispatcher.onBackPressed()
                assertEquals("learn", store(it).route)
                assertSame(original, host(it))
                assertRestored(host(it))
                val home = page(it)
                host(it).setCurrentItem(2, false)
                assertRestored(home)
                it.onBackPressedDispatcher.onBackPressed()
                assertEquals("learn", store(it).route)
            }
            settled(scenario)
            scenario.onActivity { assertRestored(page(it)); assertRestored(host(it)) }
        }
    }

    @Test fun callbackTracksRootAndReenablesAfterResumeAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settled(scenario)
            scenario.onActivity { assertFalse(it.onBackPressedDispatcher.hasEnabledCallbacks()) }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                host(it).setCurrentItem(3, false)
                assertTrue(it.onBackPressedDispatcher.hasEnabledCallbacks())
            }
            scenario.recreate()
            settled(scenario)
            scenario.onActivity {
                assertTrue(it.onBackPressedDispatcher.hasEnabledCallbacks())
                it.onBackPressedDispatcher.onBackPressed()
                assertEquals("learn", store(it).route)
                assertFalse(it.onBackPressedDispatcher.hasEnabledCallbacks())
            }
        }
    }

    @Test fun quizBackKeepsUnfinishedAnswerAndProgress() {
        StudyStore(context).apply {
            introduce(words.first { it.id == "water" })
            current = Task("water", Skill.CHARACTER_MEANING)
            draft = "unfinished"; route = "quiz"; save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settled(scenario)
            scenario.onActivity {
                val data = store(it)
                val question = data.current
                val before = data.syncSnapshot()
                it.onBackPressedDispatcher.onBackPressed()
                assertEquals("learn", data.route)
                assertEquals(question, data.current)
                assertEquals("unfinished", data.draft)
                assertEquals(before, data.syncSnapshot())
                data.route = "quiz"; render(it)
            }
            settled(scenario)
            scenario.onActivity {
                assertEquals("unfinished", views(it.window.decorView).filterIsInstance<EditText>().single().text.toString())
            }
        }
    }

    @Test fun systemBackDismissesKeyboardBeforeLeavingQuiz() {
        StudyStore(context).apply {
            introduce(words.first { it.id == "water" })
            current = Task("water", Skill.CHARACTER_MEANING)
            route = "quiz"; save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("Русский перевод")).perform(click())
            settled(scenario)
            scenario.onActivity {
                assertTrue(ViewCompat.getRootWindowInsets(it.window.decorView)!!.isVisible(WindowInsetsCompat.Type.ime()))
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settled(scenario)
            scenario.onActivity {
                assertEquals("quiz", store(it).route)
                assertFalse(ViewCompat.getRootWindowInsets(it.window.decorView)!!.isVisible(WindowInsetsCompat.Type.ime()))
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settled(scenario)
            scenario.onActivity { assertEquals("learn", store(it).route) }
        }
    }

    @Test fun disabledAnimationsDoNotTransformViews() {
        instrumentation.runOnMainSync {
            val view = View(context)
            val motion = BackNavigationMotion { false }
            motion.start(view); motion.progress(event(1f)); assertRestored(view)
            motion.cancel(); assertRestored(view)
            motion.enter(view); assertRestored(view)
            motion.reset(); assertRestored(view)
        }
    }

    @Test fun measureBackHandlerAgainstPreviousFullRebuild() {
        StudyStore(context).apply {
            val seed = words.first()
            words = (0 until 500).map { seed.copy(id = "back$it", hanzi = "水$it") }.toMutableList()
            selected.clear(); selected.addAll(words.map { it.id }); save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settled(scenario)
            val rebuild = mutableListOf<Double>()
            val reuse = mutableListOf<Double>()
            repeat(6) { iteration ->
                scenario.onActivity {
                    host(it).setCurrentItem(3, false)
                    var started = SystemClock.elapsedRealtimeNanos()
                    it.onBackPressedDispatcher.onBackPressed()
                    if (iteration > 0) reuse += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    host(it).setCurrentItem(3, false)
                    started = SystemClock.elapsedRealtimeNanos()
                    // Reproduce the previous Back handler in the same build and fixture.
                    store(it).route = "learn"; render(it)
                    if (iteration > 0) rebuild += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                }
                settled(scenario)
            }
            android.util.Log.i("BackPerformance", "500 words, handler only: previous rebuild=$rebuild ms; reused pages=$reuse ms")
        }
    }
}
