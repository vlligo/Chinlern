package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class RecallDifficultyTest {
    private fun task(id: String) = Task(id, Skill.CHARACTER_MEANING)
    private fun rank(vararg items: Pair<String, Progress>) = RecallDifficulty.rank(
        items.map { task(it.first) }, items.associate { task(it.first).key to it.second }
    ).map { it.wordId }

    @Test fun frequentFailuresOutrankLargerLifetimeErrorTotals() {
        assertEquals(listOf("hard", "familiar"), rank(
            "familiar" to Progress(attempts = 100, errors = 10),
            "hard" to Progress(attempts = 5, errors = 4)
        ))
    }
    @Test fun successfulRecallStreakLowersPriority() {
        assertEquals(listOf("forgotten", "recovering"), rank(
            "recovering" to Progress(attempts = 10, errors = 6, streak = 1),
            "forgotten" to Progress(attempts = 10, errors = 6, streak = 0)
        ))
    }
    @Test fun unmasteredBeforePreviouslyMastered() {
        assertEquals(listOf("learning", "mastered"), rank(
            "mastered" to Progress(attempts = 30, errors = 25, streak = 2, mastered = true),
            "learning" to Progress(attempts = 30, errors = 1)
        ))
    }
    @Test fun smallSamplesDoNotOutrankRepeatedFailuresAtTheSameRawRate() {
        assertEquals(listOf("repeated", "new"), rank(
            "new" to Progress(attempts = 1, errors = 1),
            "repeated" to Progress(attempts = 5, errors = 5)
        ))
    }
    @Test fun excludesUnansweredAndErrorFreeAndUsesStableTies() {
        assertEquals(listOf("a", "b"), rank(
            "b" to Progress(attempts = 4, errors = 2),
            "unanswered" to Progress(),
            "correct" to Progress(attempts = 4, errors = 0),
            "a" to Progress(attempts = 4, errors = 2)
        ))
        assertTrue(RecallDifficulty.score(Progress()).isFinite())
    }
}
