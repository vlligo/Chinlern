package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PackSyncMergeTest {
    private fun word(id: String) = JSONObject().put("id", id).put("hanzi", id)
        .put("meanings", JSONArray(listOf("original"))).put("hint", "")
        .put("pinyin", "shuǐ").put("numbered", "shui3").put("distractors", JSONArray(listOf("火", "山", "木", "日")))
    private fun pack() = JSONObject().put("name", "Words").put("step", 0)
        .put("words", JSONObject().put("version", 1).put("words", JSONArray(listOf(word("a"), word("b")))).toString())
        .put("selected", JSONArray(listOf("a", "b"))).put("progress", JSONObject())
    private fun copy(json: JSONObject) = JSONObject(json.toString())
    private fun state(attempts: Int, errors: Int, mastered: Boolean, nextAt: Int = 5) = JSONObject()
        .put("attempts", attempts).put("errors", errors).put("mastered", mastered).put("streak", if (mastered) 1 else 0)
        .put("nextAt", nextAt).put("interval", if (mastered) 1 else 0).put("due", if (mastered) 86400000 else 0)
        .put("confusions", JSONObject())
    private fun merge(b: JSONObject, l: JSONObject, r: JSONObject): JSONObject {
        val result = SyncSnapshot.merge(mapOf("p" to SyncSnapshot.pack(b)), mapOf("p" to SyncSnapshot.pack(l)), mapOf("p" to SyncSnapshot.pack(r)))
        assertEquals(setOf("p"), result.packs.keys)
        assertEquals(0, result.conflicts)
        return JSONObject(result.packs.getValue("p"))
    }
    private fun editWord(pack: JSONObject, id: String, edit: (JSONObject) -> Unit) {
        val doc = JSONObject(pack.getString("words")); val words = doc.getJSONArray("words")
        (0 until words.length()).map { words.getJSONObject(it) }.first { it.getString("id") == id }.let(edit)
        pack.put("words", doc.toString())
    }
    private fun words(pack: JSONObject): List<JSONObject> = JSONObject(pack.getString("words")).getJSONArray("words").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test fun independentSkillsAndRenameSurviveTogetherWithRebasedDelays() {
        val b = pack(); val l = copy(b).put("step", 2); val r = copy(b).put("step", 4).put("name", "Renamed")
        l.getJSONObject("progress").put("a:WRITING", state(1, 0, true, 7))
        r.getJSONObject("progress").put("b:WRITING", state(1, 1, false, 8))
        val merged = merge(b, l, r)
        assertEquals("Renamed", merged.getString("name"))
        assertEquals(4, merged.getInt("step"))
        val progress = merged.getJSONObject("progress")
        assertEquals(9, progress.getJSONObject("a:WRITING").getInt("nextAt"))
        assertEquals(8, progress.getJSONObject("b:WRITING").getInt("nextAt"))
        assertEquals(SyncSnapshot.pack(merged), SyncSnapshot.pack(merge(b, l, merged)))
    }

    @Test fun simultaneousSkillResultsRemainRetrySafeAndDoNotHideFailure() {
        val b = pack(); val l = copy(b).put("step", 1); val r = copy(b).put("step", 1)
        l.getJSONObject("progress").put("a:WRITING", state(1, 0, true))
        r.getJSONObject("progress").put("a:WRITING", state(1, 1, false))
        val merged = merge(b, l, r)
        val p = merged.getJSONObject("progress").getJSONObject("a:WRITING")
        assertFalse(p.getBoolean("mastered")); assertEquals(1, p.getInt("errors")); assertEquals(1, p.getInt("attempts"))
        assertEquals(0, p.getInt("interval")); assertEquals(0L, p.getLong("due"))
        assertEquals(SyncSnapshot.pack(merged), SyncSnapshot.pack(merge(b, l, merged)))
        assertEquals(SyncSnapshot.pack(merged), SyncSnapshot.pack(merge(b, r, merged)))
    }

    @Test fun unmarkKnownSurvivesUnrelatedAnswer() {
        val b = pack(); b.getJSONObject("progress").put("a:WRITING", state(1, 0, true))
        val l = copy(b); val r = copy(b).put("step", 1)
        l.getJSONObject("progress").put("a:WRITING", state(1, 0, false, 0))
        r.getJSONObject("progress").put("b:WRITING", state(1, 0, true))
        assertFalse(merge(b, l, r).getJSONObject("progress").getJSONObject("a:WRITING").getBoolean("mastered"))
    }

    @Test fun wordFieldsSynonymsAndSelectionMergeIndependently() {
        val b = pack(); val l = copy(b); val r = copy(b)
        editWord(l, "a") { it.put("hint", "Local hint").put("meanings", JSONArray(listOf("original", "local"))) }
        editWord(r, "a") { it.put("meanings", JSONArray(listOf("original", "remote"))) }
        l.put("selected", JSONArray(listOf("b")))
        r.put("selected", JSONArray(listOf("a")))
        val merged = merge(b, l, r)
        val a = words(merged).first { it.getString("id") == "a" }
        assertEquals("Local hint", a.getString("hint"))
        assertEquals(setOf("original", "local", "remote"), a.getJSONArray("meanings").let { arr -> (0 until arr.length()).map(arr::getString).toSet() })
        assertEquals(0, merged.getJSONArray("selected").length())
    }

    @Test fun conflictingFieldCombinationsKeepAValidUploadingWord() {
        val b = pack()
        editWord(b, "a") { it.put("meanings", JSONArray(listOf("one", "two"))) }
        val l = copy(b); val r = copy(b)
        editWord(l, "a") { it.put("meanings", JSONArray(listOf("one"))) }
        editWord(r, "a") { it.put("meanings", JSONArray(listOf("two"))) }
        val merged = merge(b, l, r)
        val a = words(merged).first { it.getString("id") == "a" }
        assertEquals("one", a.getJSONArray("meanings").getString(0))
        StudyStore.decodeWords(merged.getString("words"))
    }

    @Test fun wordDeletionRemovesOrphanedProgressAndConcurrentEditSurvives() {
        val b = pack(); b.getJSONObject("progress").put("a:WRITING", state(1, 0, true))
        val l = copy(b); val r = copy(b).put("name", "Renamed")
        l.put("words", JSONObject().put("version", 1).put("words", JSONArray(listOf(word("b")))).toString())
        val deleted = merge(b, l, r)
        assertEquals(listOf("b"), words(deleted).map { it.getString("id") })
        assertFalse(deleted.getJSONObject("progress").has("a:WRITING"))
        editWord(r, "a") { it.put("hint", "Keep this edit") }
        assertEquals(2, words(merge(b, l, r)).size)
    }
}
