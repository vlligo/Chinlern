package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class ListeningAvailabilityTest {
    private fun word(hanzi: String, numbered: String) =
        Word(hanzi, hanzi, numbered, numbered, listOf(hanzi), emptyList())

    @Test fun hanpingSkipsMissingAndUnverifiedErhuaButKeepsVerifiedRecordings() {
        assertTrue(ListeningAvailability.allows(word("哪儿", "nar3"), "hanping"))
        assertTrue(ListeningAvailability.allows(word("这儿", "zhe4 r5"), "hanping"))
        assertFalse(ListeningAvailability.allows(word("那儿", "nar4"), "hanping"))
        assertFalse(ListeningAvailability.allows(word("那儿", "na4 r5"), "hanping"))
        assertFalse(ListeningAvailability.allows(word("那儿", "na4r5"), "hanping"))
        assertFalse(ListeningAvailability.allows(word("一点儿", "yi4 dianr3"), "hanping"))
        assertTrue(ListeningAvailability.allows(word("儿子", "er2 zi5"), "hanping"))
    }

    @Test fun changingProviderRestoresListeningWithoutLosingOtherTasksOrProgress() {
        val words = listOf(word("那儿", "nar4"), word("哪儿", "nar3"), word("那", "na4"))
        val engine = LearningEngine()
        val tasks = engine.tasks(words, false, true)
        val blocked = tasks.filter { it.wordId == "那儿" && it.skill in setOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER) }
        assertEquals(2, blocked.size)
        engine.answer(blocked.first(), true)
        val progress = engine.peek(blocked.first()).copy()
        val hanping = ListeningAvailability.filter(tasks, words, "hanping")
        assertEquals(tasks.toSet() - blocked.toSet(), hanping.toSet())
        assertEquals(tasks, ListeningAvailability.filter(tasks, words, "system"))
        assertEquals(progress, engine.peek(blocked.first()))
    }
}
