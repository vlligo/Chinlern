package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class QueueReadTest {
    @Test fun selectingAndDisplayingLargeQueueDoesNotCreateProgressRecords() {
        val engine = LearningEngine()
        val tasks = (1..2000).flatMap { id -> Skill.entries.map { Task("word$id", it) } }
        assertNotNull(engine.next(tasks))
        assertEquals(0, tasks.count { engine.peek(it).mastered })
        assertTrue(engine.progress.isEmpty())
        engine.answer(tasks.first(), false)
        assertEquals(1, engine.progress.size)
        assertEquals(1, engine.peek(tasks.first()).errors)
        assertNotNull(engine.next(tasks))
        assertEquals(1, engine.progress.size)
    }
}
