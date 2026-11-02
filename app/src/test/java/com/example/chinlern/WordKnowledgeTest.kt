package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class WordKnowledgeTest {
    @Test fun categoriesAreIndependentAndDoNotInventAnswers() {
        val engine = LearningEngine()
        engine.setKnown("water", WordKnowledge.RECOGNITION, true, 1000)
        WordKnowledge.RECOGNITION.skills.forEach {
            val p = engine.state(Task("water", it))
            assertTrue(p.mastered); assertEquals(0, p.attempts); assertEquals(0, p.errors)
            assertEquals(86_401_000L, p.due)
        }
        assertFalse(engine.state(Task("water", Skill.WRITING)).mastered)
        engine.setKnown("water", WordKnowledge.WRITING, true, 1000)
        engine.setKnown("water", WordKnowledge.RECOGNITION, false)
        assertTrue(engine.state(Task("water", Skill.WRITING)).mastered)
        assertTrue(WordKnowledge.RECOGNITION.skills.all { !engine.state(Task("water", it)).mastered })
    }
    @Test fun unmarkPreservesHistoryAndMakesTaskImmediatelyAvailable() {
        val task = Task("water", Skill.WRITING)
        val engine = LearningEngine(mutableMapOf(task.key to Progress(attempts = 8, errors = 3,
            streak = 2, mastered = true, due = 999999, interval = 7, confusions = mapOf("火" to 2))))
        engine.step = 8
        engine.setKnown("water", WordKnowledge.WRITING, false)
        val p = engine.state(task)
        assertEquals(8, p.attempts); assertEquals(3, p.errors); assertEquals(mapOf("火" to 2), p.confusions)
        assertEquals(0, p.streak); assertEquals(0L, p.due); assertEquals(0, p.interval)
        assertEquals(task, engine.next(listOf(task)))
    }
    @Test fun markingAlreadyKnownDoesNotPostponeReviewAndMistakesStillCount() {
        val task = Task("water", Skill.WRITING)
        val engine = LearningEngine()
        engine.setKnown("water", WordKnowledge.WRITING, true, 1000)
        engine.setKnown("water", WordKnowledge.WRITING, true, 2000)
        assertEquals(86_401_000L, engine.state(task).due)
        engine.answer(task, false)
        assertFalse(engine.state(task).mastered)
        assertEquals(1, engine.state(task).errors)
    }
}
