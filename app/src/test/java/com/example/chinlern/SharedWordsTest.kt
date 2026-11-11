package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class SharedWordsTest {
    private val word = Word("woman", "女", "nǚ", "nü3", listOf("женщина", "женский"), listOf("文", "母", "汝"))

    @Test fun identityIgnoresPackMetadataAndMeaningOrderButRequiresEveryMeaningAndForm() {
        assertEquals(SharedWords.identity(word), SharedWords.identity(word.copy(id = "other", numbered = " NU:3 ",
            meanings = listOf(" Женский. ", "ЖЕНЩИНА"), hint = "different note", example = "example")))
        for (different in listOf(word.copy(numbered = "nü4"), word.copy(meanings = listOf("женщина")),
            word.copy(hanzi = "妇"), word.copy(alternatives = listOf("妇")))) {
            assertNotEquals(SharedWords.identity(word), SharedWords.identity(different))
        }
    }

    @Test fun latestScheduleWinsWithoutAddingRepeatedCountersAndMergeIsIdempotent() {
        val a = LearningEngine()
        val b = LearningEngine()
        val task = Task(word.id, Skill.WRITING)
        a.answer(task, true, now = 1000)
        b.answer(task, false, now = 2000)
        val merged = SharedWords.merge(listOf(a.peek(task), b.peek(task)))!!
        assertEquals(b.peek(task).due, merged.due)
        assertEquals(b.peek(task).memory!!.stability, merged.memory!!.stability, 0.0)
        assertEquals(2, merged.memory!!.history.size)
        assertEquals(1, merged.attempts)
        assertEquals(1, merged.errors)
        assertEquals(merged, SharedWords.merge(listOf(merged, a.peek(task), b.peek(task), merged)))
        ProgressCodec.validate(merged)
    }

    @Test fun differentReadingsAndMeaningsRemainIndependentInMixedPractice() {
        val different = word.copy(id = "other", meanings = listOf("дочь"))
        val session = MixedStudy(listOf(
            MixedStudy.Source("a", listOf(word), setOf(word.id), emptyMap(), 0),
            MixedStudy.Source("b", listOf(different), setOf(different.id), emptyMap(), 0)))
        assertEquals(2, session.words.size)
        session.engine.answer(Task(session.words.first().id, Skill.WRITING), true)
        assertTrue(session.progressFor(session.sources.last()).isEmpty())
    }

    @Test fun laterManualAssessmentKeepsItsScheduleValidWhenAnotherCopyHasLaterReviewHistory() {
        val a = LearningEngine(); val b = LearningEngine()
        val task = Task(word.id, Skill.WRITING)
        a.answer(task, true, now = 1000)
        a.setKnown(word.id, WordKnowledge.WRITING, true, now = 3000)
        b.answer(task, false, now = 2000)
        val merged = SharedWords.merge(listOf(a.peek(task), b.peek(task)))!!
        assertEquals(a.peek(task).memory!!.lastReview, merged.memory!!.lastReview)
        assertTrue(merged.mastered)
        ProgressCodec.validate(merged)
    }
}
