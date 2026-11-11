package com.example.chinlern

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Disposable emulator only. These fixtures never use the personal install or cloud. */
class SharedWordsStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE)
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun singlePackAnswersReachUncheckedHiddenCopiesAndUndoRestoresEveryCopyAfterReload() {
        var store = StudyStore(context)
        val first = store.activePackId
        val word = store.words.first()
        store.importPack("Copy", listOf(word.copy(id = "copy", hint = "its own note")))
        val second = store.activePackId
        store.selectLibraryWord(second, "copy", false)
        store.setPackHidden(second, true)
        store.switchPack(first)
        val before = store.syncSnapshot()
        store.current = Task(word.id, Skill.WRITING)
        store.recordAnswer(false)
        val after = store.syncSnapshot()
        val progress = JSONObject(after.getValue(second)).getJSONObject("progress").getJSONObject("copy:WRITING")
        assertEquals(1, progress.getInt("errors"))
        assertTrue(JSONObject(after.getValue(second)).getJSONArray("selected").length() == 0)
        assertEquals("its own note", StudyStore.decodeWords(JSONObject(after.getValue(second)).getString("words")).single().hint)
        store = StudyStore(context)
        assertTrue(store.ignoreAnswer())
        assertEquals(before, store.syncSnapshot())
    }

    @Test fun newCopyInheritsLearningAndPreviewWithoutUsingAnotherDailySlot() {
        val store = StudyStore(context)
        val word = store.words.first()
        store.dailyNewWords = 1
        store.introduce(word)
        store.current = Task(word.id, Skill.WRITING); store.recordAnswer(true)
        val state = store.engine.peek(Task(word.id, Skill.WRITING)).copy()
        store.importPack("Copy", listOf(word.copy(id = "copy"), store.words[1]))
        assertEquals(state.memory, store.engine.peek(Task("copy", Skill.WRITING)).memory)
        assertFalse(store.needsPreview(store.words.first()))
        assertFalse(store.tasks().any { it.wordId == "fire" })
        store.choosePracticePacks(store.packNames.map { it.first }.toSet())
        assertEquals(16, store.studyWords.size)
        assertEquals(16, store.overview(includeTotal = true).last().total)
    }

    @Test fun metadataEditsKeepSharedProgressAndMeaningEditsDetachOnlyThatCard() {
        val store = StudyStore(context)
        val first = store.activePackId
        val word = store.words.first()
        store.setWordKnown(first, word.id, WordKnowledge.WRITING, true)
        store.importPack("Copy", listOf(word.copy(id = "copy")))
        val second = store.activePackId
        store.replaceWord(store.words.single().copy(hint = "my note"))
        assertTrue(store.engine.peek(Task("copy", Skill.WRITING)).mastered)
        store.replaceWord(store.words.single().copy(meanings = listOf("другое значение")))
        assertEquals(Progress(), store.engine.peek(Task("copy", Skill.WRITING)))
        store.choosePracticePacks(setOf(first, second))
        assertEquals(17, store.studyWords.size)
        assertTrue(store.libraryPacks().first { it.id == first }.writable.contains(word.id))
    }

    @Test fun legacyMigrationCombinesSkillsPreservesSelectionsAndKeepsRecoveryCopy() {
        val seed = StudyStore(context)
        val word = seed.words.first()
        seed.importPack("Copy", listOf(word.copy(id = "copy")))
        val copy = seed.activePackId
        val snapshots = seed.syncSnapshot().mapValues { (id, text) ->
            JSONObject(text).apply {
                val key = if (id == "default") "water:WRITING" else "copy:CHARACTER_MEANING"
                put("progress", JSONObject().put(key, ProgressCodec.write(Progress(attempts = 2, errors = 1))))
            }
        }
        val saved = JSONObject(snapshots)
        prefs.edit().remove("sharedWordsVersion").putString("packs", saved.toString())
            .putString("progress", snapshots.getValue(copy).getJSONObject("progress").toString())
            .putString("introductions", JSONObject().put("default/water", "2026-11-01").put("$copy/copy", "2026-11-02").toString()).commit()
        val migrated = StudyStore(context)
        assertEquals(saved.toString(), prefs.getString("beforeSharedWords", null))
        assertFalse(migrated.needsPreview(migrated.words.single()))
        val snapshot = migrated.syncSnapshot()
        snapshot.forEach { (id, text) ->
            val pack = JSONObject(text)
            val wordId = if (id == "default") "water" else "copy"
            assertEquals(2, pack.getJSONObject("progress").getJSONObject("$wordId:WRITING").getInt("attempts"))
            assertEquals(2, pack.getJSONObject("progress").getJSONObject("$wordId:CHARACTER_MEANING").getInt("attempts"))
            assertEquals(snapshots.getValue(id).getJSONArray("selected").toString(), pack.getJSONArray("selected").toString())
        }
        assertEquals(snapshot, StudyStore(context).syncSnapshot())
    }

    @Test fun remoteReviewOnAnotherCopyAndBackupRestoreKeepSharedResults() {
        val store = StudyStore(context)
        val word = store.words.first()
        store.importPack("Copy", listOf(word.copy(id = "copy")))
        val pending = store.syncSnapshot()
        val remote = pending.toMutableMap()
        val remotePack = JSONObject(remote.getValue("default"))
        val engine = LearningEngine()
        engine.answer(Task(word.id, Skill.WRITING), false)
        remotePack.getJSONObject("progress").put("water:WRITING", ProgressCodec.write(engine.peek(Task(word.id, Skill.WRITING))))
        remotePack.put("step", engine.step)
        remote["default"] = SyncSnapshot.pack(remotePack)
        store.applySync(pending, remote, "shared-review")
        assertEquals(1, store.engine.peek(Task("copy", Skill.WRITING)).errors)
        val before = store.syncSnapshot()
        store.importBackup(store.inspectBackup(store.exportBackup()))
        assertEquals(before, store.syncSnapshot())
        StudyStore.validateSyncPacks(before)
        store.deletePack()
        assertEquals(1, store.engine.peek(Task("water", Skill.WRITING)).errors)
    }

    @Test fun manualUnlearningAtSameTimestampReachesAllCopies() {
        val store = StudyStore(context, clock = { 2_000_000_000_000L })
        val word = store.words.first()
        store.importPack("Copy", listOf(word.copy(id = "copy")))
        store.choosePracticePacks(store.packNames.map { it.first }.toSet())
        store.setWordKnown("default", word.id, WordKnowledge.WRITING, true)
        store.setWordKnown(store.activePackId, "copy", WordKnowledge.WRITING, false)
        assertTrue(StudyStore(context).libraryPacks().all { it.writable.isEmpty() })
    }

    @Test fun reimportingAnOlderConflictingBackupDoesNotMultiplySharedCopies() {
        val store = StudyStore(context)
        val word = store.words.first()
        store.current = Task(word.id, Skill.WRITING); store.recordAnswer(true)
        val backup = store.inspectBackup(store.exportBackup())
        store.clearQuestion(); store.current = Task(word.id, Skill.WRITING); store.recordAnswer(false)
        store.importBackup(backup)
        val firstImport = store.syncSnapshot()
        assertEquals(2, firstImport.size)
        assertEquals(0, store.importBackup(backup).conflicts)
        assertEquals(firstImport, store.syncSnapshot())
    }

    @Test fun acceptedSynonymPreservesNotesAndUndoAcrossSharedMixedCards() {
        var store = StudyStore(context)
        val word = store.words.first()
        store.importPack("Copy", listOf(word.copy(id = "copy", hint = "own note")))
        store.choosePracticePacks(store.packNames.map { it.first }.toSet())
        val task = store.tasks().first { it.skill == Skill.CHARACTER_MEANING && store.studyWords.first { w -> w.id == it.wordId }.hanzi == word.hanzi }
        val mixedWord = store.studyWords.first { it.id == task.wordId }
        store.current = task; store.recordAnswer(false)
        assertTrue(store.correctAnswer())
        store.addStudySynonym(mixedWord, "водичка")
        store.save()
        store = StudyStore(context)
        assertNotNull(store.current)
        assertEquals(1, store.studyWords.count { it.hanzi == word.hanzi })
        assertTrue(store.studyWords.first { it.hanzi == word.hanzi }.meanings.contains("водичка"))
        assertTrue(store.ignoreAnswer())
        val snapshots = store.syncSnapshot()
        snapshots.values.forEach { text ->
            val pack = JSONObject(text)
            val occurrence = StudyStore.decodeWords(pack.getString("words")).first { it.hanzi == word.hanzi }
            assertTrue(occurrence.meanings.contains("водичка"))
            assertFalse(pack.getJSONObject("progress").has("${occurrence.id}:CHARACTER_MEANING"))
            assertEquals(if (occurrence.id == "copy") "own note" else word.hint, occurrence.hint)
        }
    }

    @Test fun acceptedSynonymCanJoinAnotherMixedEntryWithoutLeavingDuplicateTasks() {
        val store = StudyStore(context)
        val word = store.words.first()
        store.importPack("Extended meanings", listOf(word.copy(id = "extended", meanings = word.meanings + "водичка")))
        store.choosePracticePacks(store.packNames.map { it.first }.toSet())
        val source = store.studyWords.first { it.hanzi == word.hanzi && it.meanings == word.meanings }
        store.current = Task(source.id, Skill.CHARACTER_MEANING); store.recordAnswer(false)
        store.correctAnswer()
        store.addStudySynonym(source, "водичка"); store.save()
        assertEquals(1, store.studyWords.count { it.hanzi == word.hanzi })
        assertTrue(store.studyWords.any { it.id == store.current!!.wordId })
        assertTrue(StudyStore(context).ignoreAnswer())
        assertTrue(StudyStore(context).syncSnapshot().values.all { JSONObject(it).getJSONObject("progress").length() == 0 })
    }
}
