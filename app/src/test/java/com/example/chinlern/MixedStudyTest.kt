package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class MixedStudyTest {
    private val word = Word("water", "水", "shuǐ", "shui3", listOf("вода"), listOf("火", "木", "人"))
    @Test fun duplicateIdsHaveIndependentProgressAndPreservedDelays() {
        val key = Task("water", Skill.CHARACTER_MEANING).key
        val a = MixedStudy.Source("a", listOf(word), setOf("water"), mapOf(key to Progress(attempts=1, errors=1, nextAt=104)), 100)
        val b = MixedStudy.Source("b", listOf(word), setOf("water"), emptyMap(), 5)
        val session = MixedStudy(listOf(a,b))
        assertEquals(2, session.words.map { it.id }.distinct().size)
        val first = Task(session.words[0].id, Skill.CHARACTER_MEANING)
        val second = Task(session.words[1].id, Skill.CHARACTER_MEANING)
        assertEquals(4, session.engine.state(first).nextAt)
        session.engine.answer(second, true)
        assertEquals(1, session.progressFor(a).getValue(key).errors)
        assertEquals(104, session.progressFor(a).getValue(key).nextAt)
        assertFalse(session.progressFor(b).getValue(key).mastered)
        assertEquals(Fsrs.Phase.LEARNING, session.progressFor(b).getValue(key).memory!!.phase)
        assertFalse(session.engine.state(first).mastered)
    }
    @Test fun uncheckedWordsRemainOutsideTheQueue() {
        val session = MixedStudy(listOf(MixedStudy.Source("a", listOf(word), emptySet(), emptyMap(), 20)))
        assertTrue(session.words.isEmpty())
        assertTrue(session.progressFor(session.sources.single()).isEmpty())
    }
}
