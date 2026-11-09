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
class SyncStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val task = Task("water", Skill.CHARACTER_MEANING)
    @Before fun reset() {
        listOf("chinlearn_v1", "chinlearn_profiles", "chinlearn_user_test-a", "chinlearn_user_test-b").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun firstAccountReceivesGuestDataAndOtherAccountsAreIsolated() {
        val guest = StudyStore(context)
        guest.renamePack("Existing vocabulary")
        guest.engine.answer(task, false); guest.save()
        val first = StudyStore(context, "test-a")
        assertEquals("Existing vocabulary", first.packName)
        assertEquals(1, first.engine.state(task).attempts)
        val second = StudyStore(context, "test-b")
        assertEquals("Мои слова", second.packName)
        assertEquals(0, second.engine.state(task).attempts)
        second.renamePack("Other account")
        assertEquals("Existing vocabulary", StudyStore(context, "test-a").packName)
    }

    @Test fun cloudProgressAndBaselinePersistWithoutDraftOrVoiceChanges() {
        val store = StudyStore(context)
        val pending = store.syncSnapshot()
        store.audioProvider = "hanping"
        val pack = JSONObject(pending.getValue("default"))
        pack.getJSONObject("progress").put(task.key, JSONObject().put("attempts", 1).put("errors", 1))
        pack.put("step", 1)
        val remote = mapOf("default" to SyncSnapshot.pack(pack))
        assertEquals(0, store.applySync(pending, remote, "server-1"))
        val restored = StudyStore(context)
        assertEquals(1, restored.engine.state(task).errors)
        assertEquals(remote, restored.syncBase)
        assertEquals("server-1", restored.syncRevision)
        assertEquals("hanping", restored.audioProvider)
        assertTrue(restored.lastSyncedAt > 0)
    }

    @Test fun answerEnteredDuringUploadIsNotOverwritten() {
        val store = StudyStore(context)
        val pending = store.syncSnapshot()
        store.engine.answer(task, false)
        store.draft = "unfinished"
        val current = store.syncSnapshot()
        store.applySync(pending, pending, "server-1")
        assertEquals(current, store.syncSnapshot())
        assertEquals("unfinished", store.draft)
        assertNotEquals(store.syncBase, store.syncSnapshot())
    }

    @Test fun independentDeviceAnswersAndMidFlightEditsConvergeWithoutCopies() {
        val phone = StudyStore(context, "test-a")
        val tablet = StudyStore(context, "test-b")
        val base = phone.syncSnapshot()
        tablet.applySync(tablet.syncSnapshot(), base, "initial")
        val other = Task("fire", Skill.CHARACTER_PINYIN)
        phone.engine.answer(task, false, delay = 4)
        tablet.engine.answer(other, true, delay = 4)
        val pending = phone.syncSnapshot()
        val server = SyncSnapshot.merge(base, pending, tablet.syncSnapshot()).packs
        // A third answer arrives while the merged response is in flight.
        val duringUpload = Task("tree", Skill.WRITING)
        phone.engine.answer(duringUpload, true, delay = 4)
        assertEquals(0, phone.applySync(pending, server, "merged"))
        assertEquals(1, phone.packNames.size)
        assertEquals(1, phone.engine.state(task).errors)
        assertEquals(Fsrs.Phase.LEARNING, phone.engine.state(other).memory!!.phase)
        assertEquals(Fsrs.Phase.LEARNING, phone.engine.state(duringUpload).memory!!.phase)
        val next = SyncSnapshot.merge(server, phone.syncSnapshot(), server).packs
        tablet.applySync(tablet.syncSnapshot(), next, "final")
        phone.applySync(phone.syncSnapshot(), next, "final")
        assertEquals(phone.syncSnapshot(), tablet.syncSnapshot())
        assertEquals(phone.syncSnapshot(), StudyStore(context, "test-a").syncSnapshot())
    }

    @Test fun unchangedRemoteRetainsUnfinishedQuestion() {
        val store = StudyStore(context)
        store.nextQuestion(); store.draft = "answer in progress"; store.strokes = "drawing"
        val currentTask = store.current
        val pending = store.syncSnapshot()
        store.applySync(pending, pending, "server-1")
        assertEquals(currentTask, store.current)
        assertEquals("answer in progress", store.draft)
        assertEquals("drawing", store.strokes)
    }

    @Test fun acknowledgingUploadPreservesNewerAnswerAndQuestionAfterReload() {
        val store = StudyStore(context)
        val pending = store.syncSnapshot()
        store.beginSession(focus = listOf(task, Task("fire", Skill.CHARACTER_PINYIN)))
        store.nextQuestion(); store.recordAnswer(false); store.nextQuestion()
        store.draft = "still typing"; store.saveDraft()
        val latest = store.savedSyncSnapshot()
        val question = store.current
        store.acknowledgeSync(pending, "acknowledged")
        val restored = StudyStore(context)
        assertEquals(latest, restored.savedSyncSnapshot())
        assertEquals(pending, restored.syncBase)
        assertEquals("acknowledged", restored.syncRevision)
        assertEquals(question, restored.current)
        assertEquals("still typing", restored.draft)
        assertEquals(1, restored.sessionCount)
    }

    @Test fun cachedSnapshotsReflectSelectionAndAnswerChanges() {
        val store = StudyStore(context)
        val first = store.syncSnapshot()
        assertEquals(first, store.savedSyncSnapshot())
        store.selectLibraryWord(store.activePackId, "water", false)
        val changed = store.savedSyncSnapshot()
        assertNotEquals(first, changed)
        store.selectLibraryWord(store.activePackId, "water", true)
        assertEquals(first, store.savedSyncSnapshot())
        store.current = task; store.recordAnswer(true)
        assertNotEquals(first, store.savedSyncSnapshot())
        store.ignoreAnswer()
        assertEquals(first, store.savedSyncSnapshot())
    }

    @Test fun invalidCloudDataDoesNotChangeLocalLibraryOrBaseline() {
        val store = StudyStore(context)
        val pending = store.syncSnapshot()
        val malformed = mapOf("default" to JSONObject(pending.getValue("default")).put("name", "").toString())
        try { store.applySync(pending, malformed, "broken"); fail("Accepted invalid cloud data") } catch (_: IllegalArgumentException) { }
        assertEquals(pending, store.syncSnapshot())
        assertNull(store.syncRevision)
        assertTrue(store.syncBase.isEmpty())
    }
}
