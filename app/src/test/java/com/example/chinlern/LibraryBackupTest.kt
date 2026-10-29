package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private fun pack(attempts: Int = 0, skill: String = "CHARACTER_MEANING", step: Int = 0, nextAt: Int = 0): String {
        val progress = JSONObject()
        if (attempts > 0) progress.put("water:$skill", JSONObject().put("attempts", attempts).put("errors", 0)
            .put("streak", attempts).put("mastered", true).put("nextAt", nextAt).put("due", 123456L).put("interval", 1))
        return SyncSnapshot.pack(JSONObject().put("name", "Words").put("step", step).put("progress", progress)
            .put("selected", JSONArray(listOf("water")))
            .put("words", """{"version":1,"words":[{"id":"water","hanzi":"水","pinyin":"shuǐ","pinyinNumbered":"shui3","meanings":["вода"],"examples":[]}]}"""))
    }

    @Test fun roundTripPreservesAllLearningData() {
        val data = LibraryBackup.Data(mapOf("first" to pack(4), "second" to pack(2)), setOf("first", "second"))
        assertEquals(data, LibraryBackup.decode(LibraryBackup.encode(data)))
    }

    @Test fun newPacksUnionWithoutDeletingLocal() {
        val local = mapOf("first" to pack(4))
        val result = LibraryBackup.merge(local, mapOf("second" to pack(2)))
        assertEquals(local["first"], result.packs["first"])
        assertEquals(2, result.packs.size)
        assertEquals(1, result.added)
        assertEquals(result.packs, LibraryBackup.merge(result.packs, mapOf("second" to pack(2))).packs)
    }

    @Test fun differentSkillsCombineAndRebaseSchedule() {
        val result = LibraryBackup.merge(mapOf("first" to pack(4, step = 20)), mapOf("first" to pack(2, "WRITING", 4, 10)))
        assertEquals(0, result.conflicts)
        val progress = JSONObject(result.packs.getValue("first")).getJSONObject("progress")
        assertEquals(4, progress.getJSONObject("water:CHARACTER_MEANING").getInt("attempts"))
        assertEquals(2, progress.getJSONObject("water:WRITING").getInt("attempts"))
        assertEquals(26, progress.getJSONObject("water:WRITING").getInt("nextAt"))
    }

    @Test fun conflictingResultsPreserveLocalAndIncomingWithoutSumming() {
        val local = mapOf("first" to pack(4))
        val incoming = mapOf("first" to pack(2))
        val result = LibraryBackup.merge(local, incoming)
        assertEquals(local["first"], result.packs["first"])
        assertEquals(1, result.conflicts)
        assertEquals(2, result.packs.size)
        val copy = JSONObject(result.packs.entries.first { it.key != "first" }.value)
        assertEquals(2, copy.getJSONObject("progress").getJSONObject("water:CHARACTER_MEANING").getInt("attempts"))
        val repeated = LibraryBackup.merge(result.packs, incoming)
        assertEquals(result.packs, repeated.packs)
        assertEquals(0, repeated.conflicts)
    }

    @Test fun emptyIncomingProgressNeverErasesLocal() {
        val local = mapOf("first" to pack(4))
        assertEquals(local, LibraryBackup.merge(local, mapOf("first" to pack())).packs)
    }

    @Test fun conflictsCannotOverwriteAnotherIncomingPack() {
        val incoming = mapOf("first" to pack(2))
        val initial = LibraryBackup.merge(mapOf("first" to pack(4)), incoming)
        val copyId = initial.packs.keys.first { it != "first" }
        val result = LibraryBackup.merge(mapOf("first" to pack(4)), incoming + (copyId to pack(7)))
        assertEquals(pack(7), result.packs[copyId])
        assertEquals(3, result.packs.size)
    }

    @Test fun invalidVersionAndTrailingDataAreRejected() {
        val text = LibraryBackup.encode(LibraryBackup.Data(mapOf("a" to pack()), setOf("a")))
        for (invalid in listOf(text + "junk", JSONObject(text).put("version", 99).toString(),
            JSONObject(text).put("practicePacks", JSONArray(listOf("missing"))).toString())) {
            assertTrue(runCatching { LibraryBackup.decode(invalid) }.isFailure)
        }
    }
}
