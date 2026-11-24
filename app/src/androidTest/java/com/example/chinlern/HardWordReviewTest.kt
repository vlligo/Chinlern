package com.example.chinlern

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HardWordReviewTest {
    @Test fun rankedPackReviewSurvivesReloadCorrectionsAndReturnToMixedPractice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        var store = StudyStore(context)
        val original = store.activePackId
        val reviewWords = store.words.take(2)
        store.importPack("Повторение", reviewWords)
        val packs = setOf(original, store.activePackId)
        store.choosePracticePacks(packs)
        store.selected.clear()
        val hardest = Task(reviewWords.last().id, Skill.WRITING)
        val second = Task(reviewWords.first().id, Skill.MEANING_CHARACTER)
        store.engine.progress[hardest.key] = Progress(errors = 4, attempts = 4, due = Long.MAX_VALUE)
        store.engine.progress[second.key] = Progress(errors = 1, attempts = 6)
        val recovered = Task(reviewWords.first().id, Skill.CHARACTER_MEANING)
        store.engine.progress[recovered.key] = Progress(errors = 1, attempts = 8, mastered = true)
        store.save()
        val ranked = store.hardReviewTasks()
        assertEquals(listOf(hardest, second), ranked)
        val regularIds = store.selectedStudyWords.map { it.id }
        store.beginSession(focus = ranked, packReview = true)
        store.nextQuestion()
        assertEquals(hardest, store.current)
        assertEquals(2, store.session.getInt("limit"))
        store.recordAnswer(false)
        store = StudyStore(context)
        assertFalse(store.feedback!!)
        assertEquals(regularIds, store.selectedStudyWords.map { it.id })
        assertEquals(listOf(hardest), store.missedReviewTasks())
        assertTrue(store.correctAnswer())
        assertTrue(store.missedReviewTasks().isEmpty())
        store.nextQuestion()
        assertEquals(second, store.current)
        store.recordAnswer(false)
        store.nextQuestion()
        assertTrue(store.sessionFinished)
        assertEquals(1, store.sessionCorrect)
        store.beginSession(focus = store.missedReviewTasks(), packReview = true)
        store.nextQuestion()
        assertEquals(second, store.current)
        store.recordAnswer(true)
        store.nextQuestion()
        assertTrue(store.sessionFinished)
        store.leaveSession()
        store = StudyStore(context)
        assertEquals(packs, store.practicePackIds)
        assertTrue(store.selected.isEmpty())
        assertEquals(regularIds, store.studyWords.map { it.id })
        assertEquals(8, store.engine.peek(second).attempts)
    }
}
