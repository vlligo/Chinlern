package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncSnapshotTest {
    private fun pack(name: String) = SyncSnapshot.pack(JSONObject()
        .put("name", name).put("words", """{"words":[],"version":1}""")
        .put("selected", JSONArray()).put("progress", JSONObject()).put("step", 0))

    @Test fun independentChangesMergeWithoutConflicts() {
        val base = mapOf("a" to pack("A"), "b" to pack("B"))
        val local = base + ("a" to pack("A phone"))
        val remote = base + ("b" to pack("B tablet"))
        val result = SyncSnapshot.merge(base, local, remote)
        assertEquals(mapOf("a" to local.getValue("a"), "b" to remote.getValue("b")), result.packs)
        assertEquals(0, result.conflicts)
    }

    @Test fun concurrentChangesKeepBothVersions() {
        val base = mapOf("a" to pack("Original"))
        val local = mapOf("a" to pack("Phone"))
        val remote = mapOf("a" to pack("Tablet"))
        val result = SyncSnapshot.merge(base, local, remote)
        assertEquals(remote["a"], result.packs["a"])
        assertEquals(2, result.packs.size)
        assertEquals(1, result.conflicts)
        assertEquals("Phone (копия конфликта)", JSONObject(result.packs.entries.first { it.key != "a" }.value).getString("name"))
        // Repeating an upload after a crash does not create extra copies.
        assertEquals(result.packs, SyncSnapshot.merge(base, local, result.packs).packs)
    }

    @Test fun identicalChangesDoNotDuplicate() {
        val base = mapOf("a" to pack("Original"))
        val changed = mapOf("a" to pack("Changed"))
        assertEquals(changed, SyncSnapshot.merge(base, changed, changed).packs)
        assertEquals(0, SyncSnapshot.merge(base, changed, changed).conflicts)
    }

    @Test fun remoteDeletionDoesNotResurrectUnchangedPack() {
        val base = mapOf("a" to pack("A"), "b" to pack("B"))
        assertEquals(mapOf("b" to base.getValue("b")), SyncSnapshot.merge(base, base, base - "a").packs)
    }

    @Test fun editAgainstDeletionBecomesRecoveryCopy() {
        val base = mapOf("a" to pack("A"), "b" to pack("B"))
        val result = SyncSnapshot.merge(base, base + ("a" to pack("Edited offline")), base - "a")
        assertFalse(result.packs.containsKey("a"))
        assertEquals(2, result.packs.size)
        assertEquals(1, result.conflicts)
        assertTrue(result.packs.values.any { JSONObject(it).getString("name").startsWith("Edited offline") })
    }

    @Test fun localDeletionAgainstRemoteEditKeepsRemoteEdit() {
        val base = mapOf("a" to pack("A"), "b" to pack("B"))
        val remote = base + ("a" to pack("Edited remotely"))
        val result = SyncSnapshot.merge(base, base - "a", remote)
        assertEquals(remote, result.packs)
        assertEquals(1, result.conflicts)
    }

    @Test fun concurrentDeletionsRetainAtLeastOnePack() {
        val base = mapOf("a" to pack("A"), "b" to pack("B"))
        val result = SyncSnapshot.merge(base, base - "a", base - "b")
        assertFalse(result.packs.isEmpty())
        assertEquals(1, result.conflicts)
    }

    @Test fun midFlightEditsSurviveServerResult() {
        val captured = mapOf("a" to pack("Captured"))
        val editedDuringUpload = mapOf("a" to pack("New answer"))
        assertEquals(editedDuringUpload, SyncSnapshot.merge(captured, editedDuringUpload, captured).packs)
        val changedRemotely = mapOf("a" to pack("Tablet answer"))
        val result = SyncSnapshot.merge(captured, editedDuringUpload, changedRemotely)
        assertEquals(2, result.packs.size)
        assertEquals(changedRemotely["a"], result.packs["a"])
    }

    @Test fun selectionAndObjectOrderingDoNotCauseFalseConflicts() {
        val first = JSONObject(pack("A")).put("selected", JSONArray(listOf("b", "a")))
        val second = JSONObject().put("step", 0).put("progress", JSONObject()).put("selected", JSONArray(listOf("a", "b")))
            .put("words", """{"version":1,"words":[]}""").put("name", "A")
        assertEquals(SyncSnapshot.pack(first), SyncSnapshot.pack(second))
    }

    @Test fun compressedSnapshotRoundTripsUnicode() {
        val source = mapOf("a" to pack("汉字 — Мои слова"), "b" to pack("Second"))
        val compressed = SyncSnapshot.compress(source)
        assertEquals(source, SyncSnapshot.decompress(compressed))
        assertEquals(source, SyncSnapshot.decode(SyncSnapshot.encode(source)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedCloudChunkIsRejected() {
        SyncSnapshot.decompress(listOf(ByteArray(SyncSnapshot.CHUNK_BYTES + 1)))
    }
}
