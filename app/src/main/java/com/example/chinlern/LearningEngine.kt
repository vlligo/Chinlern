package com.example.chinlern

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

data class Word(
    val id: String, val hanzi: String, val pinyin: String, val numbered: String,
    val meanings: List<String>, val distractors: List<String>, val hint: String = "",
    val alternatives: List<String> = emptyList(),
    val example: String = "", val examplePinyin: String = "", val exampleTranslation: String = ""
) {
    internal val learningIdentity by lazy { SharedWords.buildIdentity(this) }
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

/** The two independently adjustable groups shown in the word library. */
enum class WordKnowledge(val title: String) {
    RECOGNITION("Узнаю слово"), WRITING("Умею писать");
    val skills: List<Skill> get() = Skill.entries.filter { (it == Skill.WRITING) == (this == WRITING) }
}

data class Task(val wordId: String, val skill: Skill) { val key get() = "$wordId:${skill.name}" }
data class Progress(
    var attempts: Int = 0, var errors: Int = 0, var streak: Int = 0,
    var mastered: Boolean = false, var nextAt: Int = 0, var due: Long = 0,
    var interval: Int = 0,
    var confusions: Map<String, Int> = emptyMap(),
    var memory: Fsrs.Memory? = null
)

object Answers {
    fun meaning(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е')
        .trim().replace(Regex("\\s+"), " ").trim('.', '!', '?', ';', ',')
    fun meaningMatches(value: String, word: Word) = word.meanings.any { meaning(it) == meaning(value) }
    fun tones(word: Word) = Regex("[1-5]").findAll(word.numbered).map { it.value.toInt() }.toList()
    fun pronunciationKey(word: Word) = word.numbered.lowercase(Locale.ROOT)
        .replace("u:", "ü").replace('v', 'ü').replace(Regex("\\s+"), "")

    /** Explain each mismatch without relaxing the answer validator. */
    fun pinyinFeedback(input: String, expected: String): List<String> {
        if (input.isBlank() || pinyinMatches(input, expected)) return emptyList()
        fun base(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT)
            .replace("u:", "ü").replace('v', 'ü'), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300\\u0301\\u0304\\u030c1-5\\s'’·-]"), "")
        val targets = Regex("[a-züv:]+[1-5]").findAll(expected.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (base(input) != base(expected)) {
            val given = input.trim().split(Regex("[\\s'’·-]+"))
            if (given.size == targets.size) return targets.indices.mapNotNull { i ->
                when {
                    base(given[i]) != base(targets[i]) -> "Слог ${i + 1}: ${given[i]} → ${targets[i]} — неверный слог"
                    !pinyinMatches(given[i], targets[i]) -> "Тон ${i + 1}: ${given[i]} → ${targets[i]}"
                    else -> null
                }
            }
            return listOf("Неверные слоги: $input → $expected")
        }
        // Expected spelling supplies boundaries even for compact input such as ni3hao2.
        val compact = Normalizer.normalize(input.lowercase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü'), Normalizer.Form.NFC)
            .replace(Regex("[\\s'’·-]"), "")
        var cursor = 0
        val differences = targets.mapIndexedNotNull { index, target ->
            val start = cursor
            repeat(target.dropLast(1).replace("u:", "ü").length) { if (cursor < compact.length) cursor++ }
            if (cursor < compact.length && compact[cursor] in '1'..'5') cursor++
            val given = compact.substring(start, cursor)
            if (pinyinMatches(given, target)) null else "Тон ${index + 1}: $given → $target (нужен ${target.last()}-й тон)"
        }
        return if (cursor != compact.length || differences.isEmpty())
            differences + "Проверьте запись тонов: $input → $expected (один тон на слог)"
        else differences
    }

    // Compare syllable by syllable; an omitted non-neutral tone never silently passes.
    fun pinyinMatches(input: String, expected: String): Boolean {
        // A leading Latin initialism (CD盘, USB接口) has no Mandarin tone.
        val initialism = Regex("^[A-Z]{2,}(?=\\s)").find(expected.trim())?.value
        val expectedPinyin = if (initialism != null) expected.trim().removePrefix(initialism).trim() else expected
        val inputPinyin = if (initialism != null) {
            if (!input.trim().startsWith(initialism, ignoreCase = true)) return false
            input.trim().drop(initialism.length).trim()
        } else input
        val source = Normalizer.normalize(inputPinyin, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim().replace("u:", "ü").replace('v', 'ü')
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
        val normalized = expectedPinyin.lowercase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü')
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
    fun peek(task: Task): Progress = progress[task.key] ?: Progress()
    fun state(task: Task) = progress.getOrPut(task.key) { Progress() }
    private var taskWords: List<Word> = emptyList()
    private val taskCache = mutableMapOf<Pair<Boolean, Boolean>, List<Task>>()

    fun tasks(words: List<Word>, writing: Boolean, audio: Boolean): List<Task> {
        if (taskWords != words) {
            taskWords = words.toList()
            taskCache.clear()
        }
        return taskCache.getOrPut(writing to audio) { buildTasks(words, writing, audio) }
    }

    private fun buildTasks(words: List<Word>, writing: Boolean, audio: Boolean): List<Task> {
        if (writing) return words.map { Task(it.id, Skill.WRITING) }
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
    /** Due means wall-clock due, independent of the session and the learned indicator. */
    fun isDue(task: Task, now: Long = System.currentTimeMillis()): Boolean =
        (progress[task.key]?.due ?: 0L) <= now

    /** Delay unseen sibling prompts after an answer exposed the same word's solution. O(n). */
    fun dueSchedule(tasks: List<Task>): Map<Task, Long> {
        val lastWordReview = mutableMapOf<String, Long>()
        progress.forEach { (key, p) ->
            p.memory?.history?.lastOrNull()?.at?.let { at ->
                val wordId = key.substringBefore(':')
                lastWordReview[wordId] = maxOf(lastWordReview[wordId] ?: 0L, at)
            }
        }
        return tasks.associateWith { task ->
            val p = progress[task.key]
            val unseen = p == null || (p.attempts == 0 && !p.mastered && p.memory == null)
            val siblingDue = if (unseen) lastWordReview[task.wordId]?.let { it + 600_000L } ?: 0L else 0L
            maxOf(p?.due ?: 0L, siblingDue)
        }
    }

    fun next(tasks: List<Task>, random: Random = Random.Default, now: Long = System.currentTimeMillis()): Task? {
        val due = dueSchedule(tasks).filterValues { it <= now }.keys.toList()
        if (due.isEmpty()) return null
        // Learning/relearning first, then scheduled reviews, then never-attempted skills.
        fun priority(t: Task): Int {
            val p = progress[t.key] ?: return 2
            return when {
                p.memory?.phase == Fsrs.Phase.LEARNING || p.memory?.phase == Fsrs.Phase.RELEARNING -> 0
                p.attempts > 0 || p.mastered -> 1
                else -> 2
            }
        }
        val best = due.minOf(::priority)
        val candidates = due.filter { priority(it) == best }
        val otherWords = candidates.filter { it.wordId != previous?.wordId }.ifEmpty { candidates }
        if (best < 2) return otherWords.minWithOrNull(compareBy<Task> { progress[it.key]?.due ?: 0L }.thenBy { it.key })
        val otherSkills = otherWords.filter { it.skill != previous?.skill }.ifEmpty { otherWords }
        return otherSkills.random(random)
    }
    @Suppress("UNUSED_PARAMETER") // Kept for compatibility with older callers; spacing now uses real time.
    fun answer(task: Task, correct: Boolean, now: Long = System.currentTimeMillis(), delay: Int = 0,
               retention: Double = 0.9) = answer(task, if (correct) Fsrs.Rating.GOOD else Fsrs.Rating.AGAIN, now, retention)

    fun answer(task: Task, rating: Fsrs.Rating, now: Long = System.currentTimeMillis(), retention: Double = 0.9) {
        val p = state(task)
        val scheduled = Fsrs.schedule(Fsrs.migrate(p, now), rating, now, retention)
        p.attempts++
        step++
        previous = task
        if (rating == Fsrs.Rating.AGAIN) { p.errors++; p.streak = 0 } else p.streak++
        p.memory = scheduled.memory
        p.due = scheduled.due
        p.interval = scheduled.interval
        p.mastered = scheduled.memory.phase == Fsrs.Phase.REVIEW
        p.nextAt = step // Legacy field retained for old backups and queue undo coordinates.
    }
    /** Manual assessment changes mastery, never fabricates answers or erases history. */
    fun setKnown(wordId: String, category: WordKnowledge, known: Boolean, now: Long = System.currentTimeMillis()) {
        category.skills.forEach { skill ->
            val p = state(Task(wordId, skill))
            if (p.mastered != known) {
                val old = Fsrs.migrate(p, now)
                p.mastered = known
                p.streak = 0
                p.nextAt = step
                // A manual mark is not a successful review. Preserve history and verify it tomorrow.
                p.memory = old?.copy(phase = if (known) Fsrs.Phase.REVIEW else Fsrs.Phase.RELEARNING,
                    learningStep = 0, updatedAt = maxOf(now, old.lastReview), revision = java.util.UUID.randomUUID().toString())
                    ?: if (known) Fsrs.Memory(1.0, 5.0, Fsrs.Phase.REVIEW, 0, now, now,
                        java.util.UUID.randomUUID().toString()) else null
                p.interval = if (known) 1 else 0
                p.due = if (known) now + 86_400_000L else 0
            }
        }
    }
    fun restart(tasks: List<Task>, dueOnly: Boolean = false, now: Long = System.currentTimeMillis()) {
        tasks.forEach { task ->
            val p = state(task)
            if (!dueOnly || p.due <= now) { p.due = minOf(p.due, now); p.nextAt = step }
        }
    }
    fun options(word: Word, random: Random = Random.Default): List<String> =
        (word.distractors.filter { !word.acceptsForm(it) }.distinct().shuffled(random).take(5) + word.writtenForms.random(random)).shuffled(random)
}
