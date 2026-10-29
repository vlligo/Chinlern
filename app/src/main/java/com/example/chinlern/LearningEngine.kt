package com.example.chinlern

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

data class Word(
    val id: String, val hanzi: String, val pinyin: String, val numbered: String,
    val meanings: List<String>, val distractors: List<String>, val hint: String = "",
    val alternatives: List<String> = emptyList()
) {
    val writtenForms: List<String> get() = (listOf(hanzi) + alternatives).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun acceptsForm(value: String) = value.trim() in writtenForms
}

enum class Skill(val title: String, val instruction: String) {
    CHARACTER_MEANING("Иероглиф → перевод", "Введите перевод на русском"),
    CHARACTER_PINYIN("Иероглиф → пиньинь", "Введите пиньинь с тонами"),
    MEANING_PINYIN("Перевод → пиньинь", "Вспомните произношение и тоны"),
    PINYIN_MEANING("Пиньинь → перевод", "Введите перевод на русском"),
    MEANING_CHARACTER("Перевод → иероглиф", "Выберите правильное написание"),
    PINYIN_CHARACTER("Пиньинь → иероглиф", "Выберите правильное написание"),
    CHARACTER_TONE("Иероглиф → тон", "Укажите тон каждого слога по порядку"),
    AUDIO_MEANING("Аудио → перевод", "Послушайте и введите перевод"),
    AUDIO_CHARACTER("Аудио → иероглиф", "Послушайте и выберите написание"),
    WRITING("Написание", "Напишите по памяти, затем сравните")
}

data class Task(val wordId: String, val skill: Skill) { val key get() = "$wordId:${skill.name}" }
data class Progress(
    var attempts: Int = 0, var errors: Int = 0, var streak: Int = 0,
    var mastered: Boolean = false, var nextAt: Int = 0, var due: Long = 0,
    var interval: Int = 0
)

object Answers {
    fun meaning(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е')
        .trim().replace(Regex("\\s+"), " ").trim('.', '!', '?', ';', ',')
    fun meaningMatches(value: String, word: Word) = word.meanings.any { meaning(it) == meaning(value) }
    fun tones(word: Word) = Regex("[1-5]").findAll(word.numbered).map { it.value.toInt() }.toList()
    fun pronunciationKey(word: Word) = word.numbered.lowercase(Locale.ROOT)
        .replace("u:", "ü").replace('v', 'ü').replace(Regex("\\s+"), "")

    // Compare syllable by syllable; an omitted non-neutral tone never silently passes.
    fun pinyinMatches(input: String, expected: String): Boolean {
        val source = Normalizer.normalize(input, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim().replace("u:", "ü").replace('v', 'ü')
            .replace(Regex("[\\s'’·-]"), "")
        val letters = mutableListOf<Pair<Char, Int?>>()
        for (c in source) {
            if (c in '1'..'5') { letters.add(c to null); continue }
            val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            var base = decomposed.first()
            if ('\u0308' in decomposed && base == 'u') base = 'ü'
            val tone = when {
                '\u0304' in decomposed -> 1
                '\u0301' in decomposed -> 2
                '\u030c' in decomposed -> 3
                '\u0300' in decomposed -> 4
                else -> null
            }
            if (base !in 'a'..'z' && base != 'ü') return false
            letters.add(base to tone)
        }
        val normalized = expected.lowercase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü')
        val syllables = Regex("([a-zü]+)([1-5])").findAll(normalized).toList()
        if (syllables.isEmpty() || syllables.joinToString("") { it.value } != normalized.replace(" ", "")) return false
        var cursor = 0
        for (syllable in syllables) {
            val base = syllable.groupValues[1]
            val targetTone = syllable.groupValues[2].toInt()
            val marks = mutableListOf<Int>()
            for (c in base) {
                if (cursor >= letters.size || letters[cursor].first != c) return false
                letters[cursor].second?.let { marks.add(it) }
                cursor++
            }
            if (marks.size > 1) return false
            val numeric = if (cursor < letters.size && letters[cursor].first in '1'..'5')
                letters[cursor++].first.digitToInt() else null
            if (numeric != null && marks.isNotEmpty() && marks[0] != numeric) return false
            if ((numeric ?: marks.firstOrNull() ?: 5) != targetTone) return false
        }
        return cursor == letters.size
    }
}

class LearningEngine(val progress: MutableMap<String, Progress> = mutableMapOf()) {
    var step = 0
    var previous: Task? = null
    fun state(task: Task) = progress.getOrPut(task.key) { Progress() }
    fun tasks(words: List<Word>, writing: Boolean, audio: Boolean): List<Task> {
        val sounds = words.groupingBy { Answers.pronunciationKey(it) }.eachCount()
        val meanings = mutableMapOf<String, MutableSet<String>>()
        words.forEach { w -> w.meanings.forEach { meanings.getOrPut(Answers.meaning(it)) { mutableSetOf() }.add(w.id) } }
        return words.flatMap { word ->
        val homophone = (sounds[Answers.pronunciationKey(word)] ?: 0) > 1
        val sameMeaning = word.meanings.any { (meanings[Answers.meaning(it)]?.size ?: 0) > 1 }
        Skill.entries.filter { skill ->
            if (writing) skill == Skill.WRITING else skill != Skill.WRITING &&
                (audio || skill !in listOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER)) &&
                (!homophone || skill !in listOf(Skill.PINYIN_MEANING, Skill.PINYIN_CHARACTER, Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER)) &&
                (!sameMeaning || skill !in listOf(Skill.MEANING_PINYIN, Skill.MEANING_CHARACTER))
        }.map { Task(word.id, it) }
        }
    }
    fun next(tasks: List<Task>, random: Random = Random.Default): Task? {
        val remaining = tasks.filter { !state(it).mastered }
        if (remaining.isEmpty()) return null
        val ready = remaining.filter { state(it).nextAt <= step }
        // At the end, mastered tasks provide spacing instead of immediately repeating a failure.
        val candidates = if (ready.isEmpty()) tasks.filter { state(it).mastered } else ready
        val pool = candidates.ifEmpty { remaining }
        val differentSkill = pool.filter { it.skill != previous?.skill }.ifEmpty { pool }
        val differentWord = differentSkill.filter { it.wordId != previous?.wordId }.ifEmpty { differentSkill }
        return differentWord.random(random)
    }
    fun answer(task: Task, correct: Boolean, now: Long = System.currentTimeMillis(), delay: Int = Random.nextInt(4, 8)) {
        val p = state(task)
        p.attempts++
        step++
        previous = task
        if (correct) {
            p.streak++
            if (p.streak >= if (p.errors > 0) 2 else 1) {
                if (!p.mastered) {
                    p.interval = if (p.interval == 0) 1 else (p.interval * 2 + 1).coerceAtMost(30)
                    p.due = now + p.interval * 86_400_000L
                }
                p.mastered = true
            }
        } else {
            p.errors++
            p.streak = 0
            p.mastered = false
            p.interval = 0
            p.due = 0
        }
        p.nextAt = step + delay
    }
    fun restart(tasks: List<Task>, dueOnly: Boolean = false, now: Long = System.currentTimeMillis()) {
        tasks.forEach { task ->
            val p = state(task)
            if (!dueOnly || p.due <= now) { p.mastered = false; p.streak = 0; p.nextAt = step }
        }
    }
    fun options(word: Word, random: Random = Random.Default): List<String> =
        (word.distractors.filter { !word.acceptsForm(it) }.distinct().shuffled(random).take(5) + word.writtenForms.random(random)).shuffled(random)
}
