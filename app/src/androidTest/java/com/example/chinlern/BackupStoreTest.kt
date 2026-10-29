package com.example.chinlern

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only. */
@RunWith(AndroidJUnit4::class)
class BackupStoreTest {
    @Test fun wholeLibraryMergesOfflineAndRepeatImportDoesNotDuplicateResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val original = StudyStore(context)
        original.engine.progress[Task("water", Skill.CHARACTER_MEANING).key] = Progress(attempts = 3, errors = 1, nextAt = 8)
        val first = original.activePackId
        original.importPack("Extra", original.words.take(2))
        original.choosePracticePacks(setOf(first, original.activePackId))
        val file = original.exportBackup()
        val source = original.syncSnapshot()
        prefs.edit().clear().commit()
        val target = StudyStore(context)
        target.syncAccess.disable()
        val baseline = target.syncBase
        val data = target.inspectBackup(file)
        target.importBackup(data)
        source.forEach { (id, pack) -> assertEquals(pack, target.syncSnapshot()[id]) }
        assertFalse(target.syncAccess.enabled)
        assertEquals(baseline, target.syncBase)
        val once = target.syncSnapshot()
        target.importBackup(data)
        assertEquals(once, target.syncSnapshot())
        assertEquals(once, StudyStore(context).syncSnapshot())
        val broken = data.copy(packs = data.packs.mapValues { (_, value) -> JSONObject(value).put("step", -1).toString() })
        assertTrue(runCatching { target.importBackup(broken) }.isFailure)
        assertEquals(once, target.syncSnapshot())
    }
}
