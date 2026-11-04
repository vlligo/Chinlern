package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class FsrsTest {
    private val start = 1_700_000_000_000L
    @Test fun referenceEquationGoldenSequenceIncludesLearningRecallAndLapse() {
        // FSRS-6 / py-fsrs default weights, no fuzz, learning [1m,10m], relearning [10m].
        val first = Fsrs.schedule(null, Fsrs.Rating.GOOD, start, reviewId = "one")
        assertEquals(2.3065, first.memory.stability, 1e-12)
        assertEquals(2.118103970459015, first.memory.difficulty, 1e-12)
        assertEquals(start + 600_000, first.due)
        val graduate = Fsrs.schedule(first.memory, Fsrs.Rating.GOOD, first.due, reviewId = "two")
        assertEquals(Fsrs.Phase.REVIEW, graduate.memory.phase)
        assertEquals(2, graduate.interval)
        assertEquals(2.1112142357853942, graduate.memory.difficulty, 1e-12)
        val review = Fsrs.schedule(graduate.memory, Fsrs.Rating.GOOD, graduate.due, reviewId = "three")
        assertEquals(10.971048263078137, review.memory.stability, 1e-10)
        assertEquals(2.1043313908464474, review.memory.difficulty, 1e-12)
        assertEquals(11, review.interval)
        val lapse = Fsrs.schedule(review.memory, Fsrs.Rating.AGAIN, review.memory.lastReview + 20 * Fsrs.DAY, reviewId = "four")
        assertEquals(1.6602921714895944, lapse.memory.stability, 1e-10)
        assertEquals(7.389975788014609, lapse.memory.difficulty, 1e-10)
        assertEquals(Fsrs.Phase.RELEARNING, lapse.memory.phase)
        assertEquals(lapse.memory.lastReview + 600_000, lapse.due)
        assertEquals(4, lapse.memory.history.size)
    }
    @Test fun retentionControlsWorkloadAndIntervalsCanExceedThirtyDays() {
        assertTrue(Fsrs.interval(100.0, 0.95) < Fsrs.interval(100.0, 0.90))
        assertTrue(Fsrs.interval(100.0, 0.90) < Fsrs.interval(100.0, 0.85))
        assertEquals(100, Fsrs.interval(100.0, 0.90))
    }
    @Test fun actualDelayChangesStabilityAndFailureDoesNotEraseMemory() {
        val old = Fsrs.schedule(null, Fsrs.Rating.EASY, start).memory
        val early = Fsrs.schedule(old, Fsrs.Rating.GOOD, start + Fsrs.DAY)
        val late = Fsrs.schedule(old, Fsrs.Rating.GOOD, start + 20 * Fsrs.DAY)
        assertTrue(late.memory.stability > early.memory.stability)
        assertTrue(Fsrs.retrievability(old, start + Fsrs.DAY) > Fsrs.retrievability(old, start + 20 * Fsrs.DAY))
        val failed = Fsrs.schedule(old, Fsrs.Rating.AGAIN, start + 20 * Fsrs.DAY)
        assertTrue(failed.memory.stability > 0)
        assertTrue(failed.memory.stability < old.stability)
        assertTrue(failed.memory.difficulty > old.difficulty)
    }
    @Test fun clockRollbackDoesNotMakeNegativeIntervals() {
        val old = Fsrs.schedule(null, Fsrs.Rating.GOOD, start).memory
        val next = Fsrs.schedule(old, Fsrs.Rating.AGAIN, start - Fsrs.DAY)
        assertTrue(next.due > old.lastReview)
        assertEquals(0, next.memory.history.last().elapsedDays)
        assertTrue(next.memory.stability.isFinite())
    }
    @Test fun legacyMigrationKeepsHistoryAndFutureDueDates() {
        val p = Progress(attempts = 17, errors = 3, streak = 2, mastered = true, interval = 15, due = start + 5 * Fsrs.DAY)
        val before = p.copy()
        val memory = Fsrs.migrate(p, start)!!
        assertEquals(before, p)
        assertEquals(15.0, memory.stability, 0.0)
        assertEquals(start - 10 * Fsrs.DAY, memory.lastReview)
        assertTrue(memory.history.isEmpty())
        assertEquals(Fsrs.Phase.REVIEW, memory.phase)
    }
    @Test fun repeatedReviewsRemainFiniteAndHistoryIsBounded() {
        var memory: Fsrs.Memory? = null
        var at = start
        repeat(500) { i ->
            val next = Fsrs.schedule(memory, Fsrs.Rating.entries[i % 4], at, reviewId = "review-$i")
            assertTrue(next.memory.stability.isFinite())
            assertTrue(next.memory.difficulty in 1.0..10.0)
            assertTrue(next.due > at)
            memory = next.memory; at = next.due
        }
        assertEquals(Fsrs.HISTORY_LIMIT, memory!!.history.size)
        assertEquals("review-499", memory!!.history.last().id)
    }
    @Test fun dueReviewsWinOverNewSkillsAndUnseenSiblingsWait() {
        val engine = LearningEngine()
        val due = Task("old", Skill.CHARACTER_MEANING)
        val new = Task("new", Skill.CHARACTER_MEANING)
        val sibling = Task("new", Skill.CHARACTER_TONE)
        engine.answer(due, Fsrs.Rating.EASY, start)
        assertEquals(due, engine.next(listOf(new, due), now = engine.state(due).due))
        engine.answer(new, true, now = start)
        assertNull(engine.next(listOf(new, sibling), now = start + 1))
        assertEquals(new, engine.next(listOf(new, sibling), now = start + 600_000))
        assertEquals(sibling, engine.next(listOf(sibling), now = start + 600_000))
    }
    @Test fun ratingsHaveOrderedIntervalsForScheduledReviews() {
        val old = Fsrs.schedule(null, Fsrs.Rating.EASY, start).memory
        val hard = Fsrs.schedule(old, Fsrs.Rating.HARD, start + 8 * Fsrs.DAY)
        val good = Fsrs.schedule(old, Fsrs.Rating.GOOD, start + 8 * Fsrs.DAY)
        val easy = Fsrs.schedule(old, Fsrs.Rating.EASY, start + 8 * Fsrs.DAY)
        assertTrue(hard.interval < good.interval)
        assertTrue(good.interval < easy.interval)
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidRetentionRejected() { Fsrs.schedule(null, Fsrs.Rating.GOOD, start, Double.NaN) }
}
