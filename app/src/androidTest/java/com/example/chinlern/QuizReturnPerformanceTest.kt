package com.example.chinlern

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Includes layout/drawing and a many-pack library, which handler-only timing misses. */
@SdkSuppress(minSdkVersion = 29)
class QuizReturnPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun store(activity: MainActivity) = MainActivity::class.java.getDeclaredField("store")
        .apply { isAccessible = true }.get(activity) as StudyStore
    private fun render(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("render")
        .apply { isAccessible = true }.invoke(activity)

    private fun fixture() {
        // An explicitly supplied private emulator fixture can reproduce a real library.
        if (InstrumentationRegistry.getArguments().getString("useSavedLibrary") == "true") return
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val seed = StudyStore(context).words.first()
        val packs = JSONObject()
        repeat(40) { index ->
            val words = (0 until 20).map { word -> seed.copy(id = "word$word", hanzi = "水${index / 4}-$word") }
            val engine = LearningEngine()
            val start = System.currentTimeMillis() - 10 * 86_400_000L
            words.forEach { word -> repeat(4) { review ->
                engine.answer(Task(word.id, Skill.WRITING), true, now = start + review * 86_400_000L)
            } }
            val progress = JSONObject()
            engine.progress.forEach { (key, value) -> progress.put(key, ProgressCodec.write(value)) }
            packs.put("pack$index", JSONObject().put("name", "Pack $index")
                .put("words", StudyStore.encodeWords(words)).put("selected", JSONArray(words.map { it.id }))
                .put("progress", progress).put("step", engine.step).put("practice", index < 7))
        }
        val active = packs.getJSONObject("pack0")
        prefs.edit().putString("packs", packs.toString()).putString("activePack", "pack0")
            .putString("words", active.getString("words")).putString("progress", active.getJSONObject("progress").toString())
            .putStringSet("selected", (0 until 20).map { "word$it" }.toSet())
            .putStringSet("practicePacks", (0 until 7).map { "pack$it" }.toSet())
            .putInt("step", active.getInt("step")).putInt("sharedWordsVersion", 1)
            .putBoolean("onboardingDone", true).putString("route", "learn").commit()
    }

    private fun draw(scenario: ActivityScenario<MainActivity>, onFrame: ((MainActivity) -> Unit)? = null,
        action: (MainActivity) -> Unit): Pair<Double, Double> {
        val drawn = CountDownLatch(1)
        var start = 0L
        var handler = 0L
        var committed = 0L
        scenario.onActivity { activity ->
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback {
                committed = SystemClock.elapsedRealtimeNanos() - start
                onFrame?.invoke(activity)
                drawn.countDown()
            }
            start = SystemClock.elapsedRealtimeNanos()
            action(activity)
            handler = SystemClock.elapsedRealtimeNanos() - start
            activity.window.decorView.invalidate()
        }
        assertTrue("The destination must submit a frame", drawn.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        return handler / 1_000_000.0 to committed / 1_000_000.0
    }

    private fun preparedSections(activity: MainActivity): Set<String> {
        val pages = MainActivity::class.java.getDeclaredField("sectionPages")
            .apply { isAccessible = true }.get(activity) as Map<*, *>
        return pages.entries.filter { (_, page) ->
            page!!.javaClass.getDeclaredField("ready").apply { isAccessible = true }.getBoolean(page)
        }.map { it.key as String }.toSet()
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun firstLearnFrameIsFreshAndDoesNotBuildHiddenSections() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var expectedToday = 0
            draw(scenario) { activity ->
                val data = store(activity)
                data.writing = true
                data.current = data.tasks().first()
                data.recordAnswer(true)
                expectedToday = data.activitySummary().days[StudyActivity.date(System.currentTimeMillis())]!!.answers
                data.route = "quiz"; render(activity)
            }
            var prepared = emptySet<String>()
            draw(scenario, onFrame = { prepared = preparedSections(it) }) { it.onBackPressedDispatcher.onBackPressed() }
            assertEquals("Only the visible destination belongs in its first frame", setOf("learn"), prepared)
            scenario.onActivity { activity ->
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.startsWith("Ответов сегодня: $expectedToday.") })
                val host = MainActivity::class.java.getDeclaredField("pageHost")
                    .apply { isAccessible = true }.get(activity) as SectionSwipeLayout
                // Jump before deferred preparation runs: the selected screen must be ready now.
                host.setCurrentItem(3, false)
                assertEquals("settings", store(activity).route)
                assertTrue("settings" in preparedSections(activity))
                host.setCurrentItem(1, false)
                assertTrue("words" in preparedSections(activity))
            }
        }
    }

    @Test fun quizExitMeasuresTheDestinationFrameWithManyPacks() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            repeat(4) { iteration ->
                draw(scenario) { activity ->
                    val data = store(activity)
                    data.route = "quiz"
                    if (data.current == null) data.nextQuestion()
                    render(activity)
                }
                val result = draw(scenario) { it.onBackPressedDispatcher.onBackPressed() }
                scenario.onActivity { assertEquals("learn", store(it).route) }
                android.util.Log.i("QuizReturnPerformance", "return $iteration: handler=${result.first} ms, frame=${result.second} ms")
            }
            scenario.onActivity { activity ->
                val data = store(activity)
                listOf<Pair<String, () -> Any>>(
                    "activity" to { data.activitySummary() }, "extra" to { data.extraStudyOptions() },
                    "overview" to { data.overview(true) }, "library" to { data.libraryPacks() }
                ).forEach { (name, read) ->
                    val started = SystemClock.elapsedRealtimeNanos()
                    read()
                    android.util.Log.i("QuizReturnPerformance", "$name read=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0} ms")
                }
            }
        }
    }
}
