package com.example.chinlern

import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Source(var packs: Map<String, String>) : LibraryRepository {
        override fun read() = packs
        override fun replace(packs: Map<String, String>) { this.packs = packs }
    }
    private fun fixture(): Map<String, String> {
        val words = StudyStore.decodeWords(StudyStore.seedJson)
        return mapOf("a" to JSONObject().put("name", "Pack").put("words", StudyStore.encodeWords(words))
            .put("selected", org.json.JSONArray(words.map { it.id })).put("progress", JSONObject())
            .put("step", 7).put("hidden", true).toString())
    }
    @Test fun migrationIsRestartSafePreservesBackupAndKeepsStableReferencesAcrossEdits() {
        val profile = "repository-test-${UUID.randomUUID()}"
        val source = Source(fixture())
        val original = source.packs
        var ids: Set<String>
        SqliteLibraryRepository(context, profile).use { repo ->
            assertTrue(repo.migrate(source))
            assertEquals(original, repo.read())
            assertEquals(original, PreferencesLibraryRepository.decode(repo.migrationBackup()!!))
            ids = repo.referenceIds()
            val pack = JSONObject(original.getValue("a"))
            val words = StudyStore.decodeWords(pack.getString("words"))
            pack.put("words", StudyStore.encodeWords(words.map { it.copy(meanings = listOf("новое значение")) }))
            repo.replace(mapOf("a" to pack.toString()))
            assertEquals(ids, repo.referenceIds())
        }
        SqliteLibraryRepository(context, profile).use { repo ->
            assertFalse(repo.migrate(source))
            assertEquals(ids, repo.referenceIds())
            assertNotEquals(original, repo.read())
            assertEquals(original, PreferencesLibraryRepository.decode(repo.migrationBackup()!!))
            repo.replace(emptyMap())
            assertTrue(repo.referenceIds().isEmpty())
            assertTrue(repo.read().isEmpty())
        }
    }
    @Test fun invalidImportRollsBackAndProfilesAreIsolated() {
        val profile = "repository-test-${UUID.randomUUID()}"
        SqliteLibraryRepository(context, profile).use { repo ->
            try { repo.migrate(Source(mapOf("broken" to "{}"))); fail("Invalid migration accepted") }
            catch (_: org.json.JSONException) { }
            assertNull(repo.migrationBackup())
            assertTrue(repo.read().isEmpty())
            assertTrue(repo.migrate(Source(fixture())))
            val before = repo.read()
            try { repo.replace(mapOf("broken" to "{}")); fail("Invalid replacement accepted") }
            catch (_: org.json.JSONException) { }
            assertEquals(before, repo.read())
            SqliteLibraryRepository(context, "$profile-other").use { other -> assertTrue(other.read().isEmpty()) }
        }
    }
}
