package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HanpingQueueTest {
    @Test fun rapidRequestsKeepOnlyTheLatestAndStopCancelsPending() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var voice: Pronunciation
            val sent = mutableListOf<String>()
            val twoRequests = CountDownLatch(2)
            val word = Word("water", "水", "shuǐ", "shui3", listOf("вода"), listOf("火", "木", "人"))
            scenario.onActivity { activity ->
                voice = Pronunciation(activity, {}) { intent -> sent.add(intent.getStringExtra("s")!!); twoRequests.countDown() }
                voice.play(word, "hanping", false)
                voice.play(word.copy(hanzi="木"), "hanping", false)
                voice.play(word.copy(hanzi="火"), "hanping", false)
                assertEquals(listOf("水"), sent)
            }
            try {
                assertTrue(twoRequests.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(listOf("水", "火"), sent)
                    voice.play(word, "hanping", false)
                    voice.stop()
                }
                Thread.sleep(2100)
                scenario.onActivity { assertEquals(listOf("水", "火"), sent) }
            } finally { scenario.onActivity { voice.close() } }
        }
    }
}
