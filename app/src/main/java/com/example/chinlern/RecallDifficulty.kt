package com.example.chinlern

/** Ranks the progress overview, without changing the practice scheduler. */
object RecallDifficulty {
    // Smooth small samples, then reduce priority as consecutive correct recalls accumulate.
    // Stored progress has no answer timestamps/history, so this is an estimate, not a recall probability.
    fun score(progress: Progress): Double =
        (progress.errors.toDouble() + 1.0) / (progress.attempts.toDouble() + 2.0) /
            (progress.streak.toDouble() + 1.0)

    fun rank(tasks: List<Task>, progress: Map<String, Progress>): List<Task> = tasks
        .mapNotNull { task -> progress[task.key]?.takeIf { it.errors > 0 && it.attempts > 0 }?.let { task to it } }
        .sortedWith(compareBy<Pair<Task, Progress>> { it.second.mastered }
            .thenByDescending { score(it.second) }
            .thenByDescending { it.second.errors }
            .thenBy { it.first.key })
        .map { it.first }
}
