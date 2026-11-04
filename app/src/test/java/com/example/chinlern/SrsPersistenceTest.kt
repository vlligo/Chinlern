package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SrsPersistenceTest {
    private val task = Task("water", Skill.WRITING)
    private val now = 1_700_000_000_000L
    private fun pack(progress: Progress): String = SyncSnapshot.pack(JSONObject().put("name", "Words").put("step", progress.attempts)
        .put("words", StudyStore.encodeWords(listOf(Word("water", "水", "shuǐ", "shui3", listOf("вода"), listOf("木", "火", "日")))))
        .put("selected", JSONArray(listOf("water"))).put("progress", JSONObject().put(task.key, ProgressCodec.write(progress))))
    private fun result(map: Map<String, String>) = ProgressCodec.read(JSONObject(map.getValue("p")).getJSONObject("progress").getJSONObject(task.key))
    @Test fun codecBackupAndCompressionPreserveLongIntervalsAndFullMemory() {
        val e = LearningEngine()
        e.answer(task, Fsrs.Rating.EASY, now)
        repeat(4) { e.answer(task, true, e.state(task).due) }
        val p = e.state(task)
        assertTrue(p.interval > 30)
        assertEquals(p, ProgressCodec.read(ProgressCodec.write(p)))
        val packs = mapOf("p" to pack(p))
        StudyStore.validateSyncPacks(packs)
        val backup = LibraryBackup.decode(LibraryBackup.encode(LibraryBackup.Data(packs, setOf("p"))))
        assertEquals(p, result(backup.packs))
        assertEquals(p, result(SyncSnapshot.decompress(SyncSnapshot.compress(packs))))
    }
    @Test fun concurrentReviewUsesOneWholeMemorySnapshotAndRetriesAreIdempotent() {
        val baseEngine = LearningEngine(); baseEngine.answer(task, Fsrs.Rating.EASY, now)
        val base = mapOf("p" to pack(baseEngine.state(task)))
        val local = LearningEngine(mutableMapOf(task.key to baseEngine.state(task).copy()))
        val remote = LearningEngine(mutableMapOf(task.key to baseEngine.state(task).copy()))
        local.answer(task, false, now + 9 * Fsrs.DAY)
        remote.answer(task, true, now + 10 * Fsrs.DAY)
        val lp = mapOf("p" to pack(local.state(task))); val rp = mapOf("p" to pack(remote.state(task)))
        val merged = SyncSnapshot.merge(base, lp, rp).packs
        val p = result(merged)
        assertEquals(remote.state(task).memory, p.memory)
        assertEquals(remote.state(task).due, p.due)
        assertEquals(remote.state(task).mastered, p.mastered)
        assertEquals(1, p.errors)
        StudyStore.validateSyncPacks(merged)
        assertEquals(merged, SyncSnapshot.merge(base, rp, lp).packs)
        assertEquals(merged, SyncSnapshot.merge(base, lp, merged).packs)
        assertEquals(merged, SyncSnapshot.merge(base, rp, merged).packs)
    }
    @Test fun restoringSnapshotForUndoRestoresEverySchedulingField() {
        val e = LearningEngine(); e.answer(task, Fsrs.Rating.EASY, now)
        val before = ProgressCodec.write(e.state(task)).toString()
        val prior = e.state(task).copy()
        e.answer(task, false, now + 9 * Fsrs.DAY)
        assertNotEquals(prior.memory, e.state(task).memory)
        e.progress[task.key] = ProgressCodec.read(JSONObject(before))
        assertEquals(prior, e.state(task))
        assertEquals(1, e.state(task).memory!!.history.size)
    }
    @Test fun legacyRecordsRemainUnchangedUntilReviewed() {
        val legacy = Progress(attempts = 9, errors = 2, mastered = true, due = now, interval = 30)
        val decoded = ProgressCodec.read(ProgressCodec.write(legacy))
        assertEquals(legacy, decoded)
        assertNull(decoded.memory)
        ProgressCodec.validate(decoded)
        val e = LearningEngine(mutableMapOf(task.key to decoded))
        e.answer(task, true, now)
        assertEquals(10, e.state(task).attempts)
        assertEquals(2, e.state(task).errors)
        assertEquals(1, e.state(task).memory!!.history.size)
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidMemoryRejectedBeforeImport() {
        val e = LearningEngine(); e.answer(task, true, now)
        val json = ProgressCodec.write(e.state(task))
        json.getJSONObject("srs").put("stability", -1)
        ProgressCodec.read(json)
    }
    @Test(expected = IllegalArgumentException::class)
    fun contradictoryPhaseRejected() {
        val e = LearningEngine(); e.answer(task, true, now)
        e.state(task).mastered = true
        ProgressCodec.validate(e.state(task))
    }
}
