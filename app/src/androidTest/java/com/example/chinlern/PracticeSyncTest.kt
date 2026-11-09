package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PracticeSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() {
        listOf("chinlearn_v1", "chinlearn_profiles", "chinlearn_user_test-a", "chinlearn_user_test-b").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun devices(): Pair<StudyStore, StudyStore> {
        val phone = StudyStore(context, "test-a")
        phone.importPack("Second", listOf(phone.words.first()))
        phone.importPack("Third", listOf(phone.words.first()))
        phone.choosePracticePacks(setOf("default"))
        val tablet = StudyStore(context, "test-b")
        val initial = phone.syncSnapshot()
        phone.acknowledgeSync(initial, "initial")
        tablet.applySync(tablet.syncSnapshot(), initial, "initial")
        assertEquals(phone.practicePackIds, tablet.practicePackIds)
        return phone to tablet
    }

    @Test fun remotePackAndWordChoicesArriveTogetherAndPersist() {
        val (phone, tablet) = devices()
        val second = phone.packNames.first { it.second == "Second" }.first
        var notifications = 0
        phone.onSyncDataChanged = { notifications++ }
        phone.choosePracticePacks(setOf("default", second))
        assertTrue("Pack selection must schedule sync", notifications > 0)
        phone.selectLibraryWord("default", "water", false)
        val pending = tablet.syncSnapshot()
        tablet.beginSession(); tablet.nextQuestion(); tablet.draft = "old prompt"
        val remote = phone.savedSyncSnapshot()
        tablet.applySync(pending, remote, "selected")
        assertEquals(setOf("default", second), tablet.practicePackIds)
        assertFalse(tablet.selected.contains("water"))
        assertFalse(tablet.shortSession)
        assertNull(tablet.current)
        assertEquals("", tablet.draft)
        assertEquals(phone.selectedStudyWords.map { it.id }.toSet(), tablet.selectedStudyWords.map { it.id }.toSet())
        val restored = StudyStore(context, "test-b")
        assertEquals(tablet.practicePackIds, restored.practicePackIds)
        assertEquals(remote, restored.savedSyncSnapshot())
    }

    @Test fun selectionDuringUploadMergesWithRemoteChoiceAndLocalAnswer() {
        val (phone, tablet) = devices()
        val second = phone.packNames.first { it.second == "Second" }.first
        val third = phone.packNames.first { it.second == "Third" }.first
        val pending = phone.syncSnapshot()
        tablet.choosePracticePacks(setOf(second))
        val remote = tablet.syncSnapshot()
        val task = Task("water", Skill.CHARACTER_MEANING)
        phone.engine.answer(task, false); phone.save()
        phone.choosePracticePacks(setOf(third))
        phone.applySync(pending, remote, "in-flight")
        assertEquals(setOf(second, third), phone.practicePackIds)
        assertEquals(1, phone.engine.state(task).errors)
        val final = SyncSnapshot.merge(remote, phone.syncSnapshot(), remote).packs
        tablet.applySync(tablet.syncSnapshot(), final, "final")
        phone.applySync(phone.syncSnapshot(), final, "final")
        assertEquals(phone.practicePackIds, tablet.practicePackIds)
        assertEquals(phone.savedSyncSnapshot(), tablet.savedSyncSnapshot())
        assertEquals(final, StudyStore(context, "test-a").savedSyncSnapshot())
    }

    @Test fun legacyCloudRetainsLocalPracticeSelection() {
        val (phone, _) = devices()
        val pending = phone.syncSnapshot()
        val legacy = pending.mapValues { (_, text) ->
            SyncSnapshot.pack(JSONObject(text).apply { remove("practice"); put("name", getString("name") + " old") })
        }
        phone.applySync(pending, legacy, "legacy")
        assertEquals(setOf("default"), phone.practicePackIds)
        assertTrue(phone.packNames.all { it.second.endsWith(" old") })
    }

    @Test fun concurrentDeselectionsKeepOneVisiblePackAndConverge() {
        val (phone, tablet) = devices()
        val second = phone.packNames.first { it.second == "Second" }.first
        phone.choosePracticePacks(setOf("default", second))
        val base = phone.syncSnapshot()
        tablet.applySync(tablet.syncSnapshot(), base, "both")
        phone.choosePracticePacks(setOf("default"))
        tablet.choosePracticePacks(setOf(second))
        val local = phone.syncSnapshot(); val other = tablet.syncSnapshot()
        val merged = SyncSnapshot.merge(base, local, other).packs
        phone.applySync(local, merged, "neither")
        tablet.applySync(other, merged, "neither")
        assertEquals(1, phone.practicePackIds.size)
        assertEquals(phone.practicePackIds, tablet.practicePackIds)
        assertEquals(phone.savedSyncSnapshot(), tablet.savedSyncSnapshot())
    }
}
