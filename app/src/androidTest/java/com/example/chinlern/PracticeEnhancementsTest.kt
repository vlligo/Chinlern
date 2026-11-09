package com.example.chinlern

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PracticeEnhancementsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun confusionPersistsDrivesChoicesAndCanBeUndone() {
        val store = StudyStore(context)
        val task = Task("water", Skill.MEANING_CHARACTER)
        store.current = task; store.choices = listOf("水", "永"); store.draft = "永"
        store.recordAnswer(false)
        val reopened = StudyStore(context)
        assertEquals(1, reopened.engine.state(task).confusions["永"])
        assertTrue(reopened.ignoreAnswer())
        assertTrue(reopened.engine.state(task).confusions.isEmpty())
        reopened.clearQuestion(); reopened.current = task; reopened.choices = listOf("水", "永"); reopened.draft = "永"
        reopened.recordAnswer(false)
        reopened.beginSession(focus = listOf(task)); reopened.nextQuestion()
        assertEquals(setOf("水", "永"), reopened.choices.toSet())
    }

    @Test fun comparisonsIncludeBothKnownWords() {
        val store = StudyStore(context)
        val task = Task("water", Skill.MEANING_CHARACTER)
        store.current = task; store.choices = listOf("水", "火"); store.draft = "火"
        store.recordAnswer(false)
        store.beginSession(focus = listOf(Task("fire", Skill.MEANING_CHARACTER)))
        store.nextQuestion()
        assertEquals(setOf("火", "水"), store.choices.toSet())
    }

    @Test fun updatePreservesProgressSelectionAndOverviewAcrossReload() {
        val store = StudyStore(context)
        val water = store.words.first()
        val task = Task(water.id, Skill.CHARACTER_MEANING)
        store.introduce(water)
        store.engine.progress[task.key] = Progress(attempts = 2, mastered = true, due = 1L)
        store.selected.remove(water.id)
        val before = store.engine.state(task).copy()
        store.updatePack("Updated", listOf(water.copy(id = water.hanzi, hint = "Обновлено")))
        val reopened = StudyStore(context)
        assertEquals(before, reopened.engine.state(task))
        assertFalse(water.id in reopened.selected)
        assertEquals("Обновлено", reopened.words.first().hint)
        assertEquals(16, reopened.words.size)
        val overview = reopened.overview().single()
        assertEquals(1, overview.introduced)
        assertEquals(0, overview.learned)
        assertEquals(1, overview.mastered)
        assertEquals(1, overview.due)
    }
}
