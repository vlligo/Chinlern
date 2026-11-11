package com.example.chinlern

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Disposable emulator fixtures; never run against a personal library. */
class OverviewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun readsLiveVocabularyAndProgressWithoutSaving() {
        val now = 2_000_000_000_000L
        val store = StudyStore(context, clock = { now })
        val word = store.words.first()
        store.words = mutableListOf(word)
        store.engine.setKnown(word.id, WordKnowledge.RECOGNITION, true, now - 172_800_000L)
        store.engine.setKnown(word.id, WordKnowledge.WRITING, true, now - 172_800_000L)
        val before = prefs.all.toMap()
        var notifications = 0
        store.onSyncDataChanged = { notifications++ }
        repeat(2) {
            val overview = store.overview().single()
            assertEquals(1, overview.total)
            assertEquals(1, overview.introduced)
            assertEquals(1, overview.learned)
            assertEquals(10, overview.mastered)
            assertEquals(10, overview.due)
        }
        assertEquals(before, prefs.all)
        assertEquals(0, notifications)
    }

    @Test fun mixedAndInactivePackResultsMatchASavedReload() {
        val store = StudyStore(context)
        val first = store.activePackId
        val word = store.words.first()
        store.engine.setKnown(word.id, WordKnowledge.WRITING, true)
        store.importPack("Second", listOf(word))
        val second = store.activePackId
        store.importPack("Outside practice", listOf(word))
        store.engine.setKnown(word.id, WordKnowledge.RECOGNITION, true)
        store.choosePracticePacks(setOf(first, second))
        val task = store.studyEngine.tasks(store.studyWords, true, false).first()
        store.studyEngine.answer(task, false)
        val before = prefs.all.toMap()
        val overview = store.overview()
        assertEquals(before, prefs.all)
        assertEquals(3, overview.size)
        assertEquals(3, overview.sumOf { it.skills.getValue(Skill.WRITING).sumOf { p -> p.errors } })
        assertEquals(1, store.overview(includeTotal = true).last().skills.getValue(Skill.WRITING).sumOf { it.errors })
        store.save()
        assertEquals(StudyStore(context).overview(), overview)
    }

    @Test fun measureOverviewWithReviewHistory() {
        val store = StudyStore(context)
        val seed = store.words.first()
        store.words = (0 until 500).map { seed.copy(id = "word$it", hanzi = "水$it") }.toMutableList()
        store.selected.clear(); store.selected.addAll(store.words.map { it.id })
        val start = System.currentTimeMillis() - 40 * 86_400_000L
        store.words.forEach { word ->
            repeat(20) { review -> store.engine.answer(Task(word.id, Skill.WRITING), true, now = start + review * 86_400_000L) }
        }
        store.save()
        val samples = mutableListOf<Long>()
        instrumentation.runOnMainSync {
            repeat(9) { iteration ->
                val started = SystemClock.elapsedRealtimeNanos()
                assertEquals(500, store.overview().single().total)
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                if (iteration >= 2) samples.add(elapsed)
            }
        }
        android.util.Log.i("OverviewPerformance", "500 words / 10000 reviews: median=${samples.sorted()[samples.size / 2] / 1_000_000.0}ms samples=${samples.map { it / 1_000_000.0 }}")
    }
}
