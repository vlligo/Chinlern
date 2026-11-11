package com.example.chinlern

import java.text.Normalizer

/** Only interchangeable cards share learning. Context, IDs and pack membership remain local. */
object SharedWords {
    data class Identity(val forms: List<String>, val pronunciation: String, val meanings: List<String>)

    fun identity(word: Word): Identity = word.learningIdentity

    internal fun buildIdentity(word: Word) = Identity(
        word.writtenForms.map { Normalizer.normalize(it, Normalizer.Form.NFC) }.distinct().sorted(),
        Normalizer.normalize(Answers.pronunciationKey(word), Normalizer.Form.NFC),
        word.meanings.map { Answers.meaning(Normalizer.normalize(it, Normalizer.Form.NFC)) }.distinct().sorted()
    )

    /** Inputs use relative queue positions. Never add duplicated counters or combine scheduler fields. */
    fun merge(states: List<Progress>): Progress? {
        val meaningful = states.filter { it.copy(nextAt = 0) != Progress() }
        if (meaningful.isEmpty()) return null
        if (meaningful.all { it == meaningful.first() }) return meaningful.first().copy()
        val scheduled = meaningful.filter { it.memory != null }
        val chosen = if (scheduled.isNotEmpty()) scheduled.maxWith(
            compareBy<Progress> { it.memory!!.updatedAt }.thenBy { it.memory!!.revision }
        ) else meaningful.maxWith(compareBy<Progress> { it.attempts }.thenBy { it.errors }
            .thenBy { !it.mastered }.thenBy { it.due }.thenBy { it.interval }.thenBy { it.streak })
        val history = meaningful.flatMap { it.memory?.history.orEmpty() }.distinctBy { it.id }
            .sortedWith(compareBy<Fsrs.Review> { it.at }.thenBy { it.id }).takeLast(Fsrs.HISTORY_LIMIT)
        val confusions = meaningful.flatMap { it.confusions.keys }.distinct().associateWith { form ->
            meaningful.maxOf { it.confusions[form] ?: 0 }
        }
        return chosen.copy(
            attempts = meaningful.maxOf { it.attempts }, errors = meaningful.maxOf { it.errors },
            nextAt = meaningful.maxOf { it.nextAt }, confusions = confusions,
            memory = chosen.memory?.let { memory -> memory.copy(history = history.filter { it.at <= memory.lastReview }) }
        )
    }
}
