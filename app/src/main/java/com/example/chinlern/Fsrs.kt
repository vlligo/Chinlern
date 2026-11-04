package com.example.chinlern

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import java.util.UUID

/** FSRS-6 equations/default weights from open-spaced-repetition/py-fsrs (MIT).
 * See assets/fsrs-license.txt. Pure, deterministic scheduling; no network or training at review time.
 * Typed answers use Again/Good; all four ratings are supported by the model.
 */
object Fsrs {
    const val DAY = 86_400_000L
    const val MAX_INTERVAL = 36500
    const val HISTORY_LIMIT = 32
    enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }
    enum class Phase { LEARNING, REVIEW, RELEARNING }
    data class Review(val id: String, val at: Long, val rating: Int, val elapsedDays: Int)
    // Immutable values make answer undo and combined-pack copies safe.
    data class Memory(
        val stability: Double, val difficulty: Double, val phase: Phase,
        val learningStep: Int, val lastReview: Long, val updatedAt: Long,
        val revision: String, val history: List<Review> = emptyList()
    )
    data class Scheduled(val memory: Memory, val due: Long, val interval: Int)
    private val w = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    )
    private val decay = -w[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0
    private fun initialDifficulty(rating: Rating) = w[4] - exp(w[5] * (rating.value - 1)) + 1.0
    fun retrievability(memory: Memory, now: Long): Double {
        val days = ((now - memory.lastReview).coerceAtLeast(0L) / DAY).toDouble()
        return (1.0 + factor * days / memory.stability).pow(decay)
    }
    fun interval(stability: Double, retention: Double): Int {
        require(retention.isFinite() && retention in 0.80..0.97)
        return round(stability / factor * (retention.pow(1.0 / decay) - 1.0))
            .coerceIn(1.0, MAX_INTERVAL.toDouble()).toInt()
    }
    /** Legacy data has no review timestamps: infer only an initial state from its existing interval.
     * Do not fabricate review events, overwrite counters, or move a legacy due date on load.
     */
    fun migrate(p: Progress, now: Long): Memory? {
        p.memory?.let { return it }
        if (p.attempts == 0 && !p.mastered) return null
        val stability = if (p.interval > 0) p.interval.toDouble() else w[0]
        val last = if (p.due > 0 && p.interval > 0) (p.due - p.interval * DAY).coerceIn(0L, now) else now
        return Memory(stability, 5.0, if (p.mastered) Phase.REVIEW else Phase.LEARNING,
            if (p.streak > 0) 1 else 0, last, last, "legacy")
    }
    fun schedule(previous: Memory?, rating: Rating, now: Long, retention: Double = 0.9,
                 reviewId: String = UUID.randomUUID().toString()): Scheduled {
        require(now >= 0 && reviewId.isNotBlank())
        require(retention.isFinite() && retention in 0.80..0.97)
        val at = max(now, previous?.lastReview ?: 0L)
        val elapsed = previous?.let { ((at - it.lastReview) / DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 0
        val stability: Double
        val difficulty: Double
        if (previous == null) {
            stability = w[rating.value - 1]
            difficulty = initialDifficulty(rating).coerceIn(1.0, 10.0)
        } else {
            val s = previous.stability; val d = previous.difficulty
            val r = retrievability(previous, at)
            stability = (if (elapsed == 0) {
                val increase = exp(w[17] * (rating.value - 3 + w[18])) * s.pow(-w[19])
                s * if (rating == Rating.AGAIN) increase else max(increase, 1.0)
            } else if (rating == Rating.AGAIN) {
                min(w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1.0 - r) * w[14]),
                    s / exp(w[17] * w[18]))
            } else {
                val hard = if (rating == Rating.HARD) w[15] else 1.0
                val easy = if (rating == Rating.EASY) w[16] else 1.0
                s * (1.0 + exp(w[8]) * (11.0 - d) * s.pow(-w[9]) *
                    (exp((1.0 - r) * w[10]) - 1.0) * hard * easy)
            }).coerceIn(0.001, 1_000_000_000.0)
            val delta = -w[6] * (rating.value - 3)
            difficulty = (w[7] * initialDifficulty(Rating.EASY) +
                (1.0 - w[7]) * (d + (10.0 - d) * delta / 9.0)).coerceIn(1.0, 10.0)
        }
        var phase = previous?.phase ?: Phase.LEARNING
        var step = previous?.learningStep ?: 0
        var minutes = 0.0
        when (phase) {
            Phase.LEARNING -> when (rating) {
                Rating.AGAIN -> { step = 0; minutes = 1.0 }
                Rating.HARD -> { minutes = if (step == 0) 5.5 else 10.0 }
                Rating.GOOD -> if (step == 0) { step = 1; minutes = 10.0 } else { phase = Phase.REVIEW; step = 0 }
                Rating.EASY -> { phase = Phase.REVIEW; step = 0 }
            }
            Phase.REVIEW -> if (rating == Rating.AGAIN) { phase = Phase.RELEARNING; step = 0; minutes = 10.0 }
            Phase.RELEARNING -> when (rating) {
                Rating.AGAIN -> { minutes = 10.0 }
                Rating.HARD -> { minutes = 15.0 }
                Rating.GOOD, Rating.EASY -> { phase = Phase.REVIEW; step = 0 }
            }
        }
        val days = if (phase == Phase.REVIEW) interval(stability, retention) else 0
        val delay = if (days > 0) days * DAY else (minutes * 60_000).toLong()
        val event = Review(reviewId, at, rating.value, elapsed)
        val history = ((previous?.history ?: emptyList()) + event).distinctBy { it.id }.takeLast(HISTORY_LIMIT)
        return Scheduled(Memory(stability, difficulty, phase, step, at, at, reviewId, history),
            at.coerceAtMost(Long.MAX_VALUE - delay) + delay, days)
    }
}
