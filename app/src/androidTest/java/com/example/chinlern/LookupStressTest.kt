package com.example.chinlern

import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.dictionary.OfflineDictionary
import org.junit.Assert.*
import org.junit.Test

class LookupStressTest {
    @Test fun repeatedBroadQueriesRemainBoundedAndFast() {
        val dictionary = OfflineDictionary(InstrumentationRegistry.getInstrumentation().targetContext)
        dictionary.search("水") // Installation is measured separately from warm search.
        val timings = mutableListOf<Long>()
        repeat(4) {
            for (query in listOf("a", "the", "water", "computer", "一", "行", "shi", "nu:3", "zzzznone", "' OR 1=1 --")) {
                val start = android.os.SystemClock.elapsedRealtime()
                assertTrue(dictionary.search(query).size <= 50)
                timings.add(android.os.SystemClock.elapsedRealtime() - start)
            }
        }
        timings.sort()
        val p95 = timings[(timings.size * .95).toInt() - 1]
        android.util.Log.i("LookupStress", "dictionary warm p95=${p95}ms max=${timings.last()}ms")
        assertTrue("Warm dictionary p95 must stay below 1 second: $p95", p95 < 1000)
    }
    @Test fun tenThousandPersonalEntriesHandleRepeatedTypingWithoutMutation() {
        val seed = Word("seed", "水", "shuǐ", "shui3", listOf("вода"), emptyList())
        val entries = (0 until 10000).map { VocabularyLookup.Entry(seed.copy(id = "$it", hanzi = "水$it"), "pack", "Pack") }
        val lookup = VocabularyLookup(entries)
        val start = android.os.SystemClock.elapsedRealtime()
        repeat(20) {
            for (query in listOf("水", "水9", "水99", "水999", "water", "shui3")) lookup.search(query)
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        android.util.Log.i("LookupStress", "10000 entries, 120 queries=${elapsed}ms")
        assertTrue("Repeated personal lookup must finish within 5 seconds: $elapsed", elapsed < 5000)
        assertEquals("水999", lookup.search("水999").first().word.hanzi)
        assertEquals(10000, entries.size)
    }
}
