package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class MixedStudyTest {
    private val word = Word("water", "水", "shuǐ", "shui3", listOf("вода"), listOf("火", "木", "人"))
    @Test fun identicalWordsShareOneQueueEntryAndPreserveDelays() {
        val key = Task("water", Skill.CHARACTER_MEANING).key
        val a = MixedStudy.Source("a", listOf(word), setOf("water"), mapOf(key to Progress(attempts=1, errors=1, nextAt=104)), 100)
        val b = MixedStudy.Source("b", listOf(word), setOf("water"), emptyMap(), 5)
        val session = MixedStudy(listOf(a,b))
        assertEquals(1, session.words.size)
        val task = Task(session.words.single().id, Skill.CHARACTER_MEANING)
        assertEquals(4, session.engine.state(task).nextAt)
        assertEquals(104, session.progressFor(a).getValue(key).nextAt)
        assertEquals(9, session.progressFor(b).getValue(key).nextAt)
        session.engine.answer(task, true)
        assertEquals(2, session.progressFor(a).getValue(key).attempts)
        assertEquals(2, session.progressFor(b).getValue(key).attempts)
        assertEquals(1, session.progressFor(b).getValue(key).errors)
        assertEquals(session.progressFor(a).getValue(key).memory, session.progressFor(b).getValue(key).memory)

    }
    @Test fun uncheckedWordsRemainOutsideTheQueue() {
        val session = MixedStudy(listOf(MixedStudy.Source("a", listOf(word), emptySet(), emptyMap(), 20)))
        assertTrue(session.words.isEmpty())
        assertTrue(session.progressFor(session.sources.single()).isEmpty())
    }
}
