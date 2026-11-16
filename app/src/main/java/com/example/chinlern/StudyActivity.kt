package com.example.chinlern

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Calendar days use the device's local time, including daylight-saving transitions. */
object StudyActivity {
    data class Day(val answers: Int = 0, val correct: Int = 0)
    data class Summary(val days: Map<String, Day>, val streak: Int, val best: Int,
        val answers: Long, val correct: Long, val writing: Long, val incompleteHistory: Boolean)

    fun date(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(time))
    fun shifted(time: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = time; add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    fun fromHistory(history: List<Fsrs.Review>): Map<String, Day> = history.groupBy { date(it.at) }
        .mapValues { (_, events) -> Day(events.size, events.count { it.rating != 1 }) }

    fun merge(maps: List<Map<String, Day>>): Map<String, Day> = maps.flatMap { it.keys }.distinct().associateWith { date ->
        Day(maps.maxOf { it[date]?.answers ?: 0 }, maps.maxOf { it[date]?.correct ?: 0 })
    }

    fun summarize(skills: Map<Skill, List<Progress>>, now: Long): Summary {
        val states = skills.values.flatten()
        val days = mutableMapOf<String, Day>()
        states.forEach { p -> p.activity.forEach { (date, value) ->
            val old = days[date] ?: Day()
            days[date] = Day(old.answers + value.answers, old.correct + value.correct)
        } }
        val today = date(now)
        val active = days.filter { it.value.answers > 0 && it.key <= today }.keys
        var cursor = if (today in active) now else shifted(now, -1)
        var streak = 0
        while (date(cursor) in active) { streak++; cursor = shifted(cursor, -1) }
        var best = 0; var run = 0; var previous: String? = null
        active.sorted().forEach { day ->
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(day)!!.time
            run = if (previous == date(shifted(parsed, -1))) run + 1 else 1
            best = maxOf(best, run); previous = day
        }
        return Summary(days, streak, best, states.sumOf { maxOf(it.attempts.toLong(), it.activity.values.sumOf { day -> day.answers.toLong() }) },
            states.sumOf { maxOf((it.attempts - it.errors).toLong(), it.activity.values.sumOf { day -> day.correct.toLong() }) },
            skills[Skill.WRITING].orEmpty().sumOf { maxOf(it.attempts.toLong(), it.activity.values.sumOf { day -> day.answers.toLong() }) },
            states.any { it.attempts > it.activity.values.sumOf { day -> day.answers } })
    }
}
