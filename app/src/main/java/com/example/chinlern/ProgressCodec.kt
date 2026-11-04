package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject

/** One schema for local storage, undo, backups and cloud snapshots. Old progress remains readable. */
object ProgressCodec {
    fun read(j: JSONObject): Progress = Progress(
        j.optInt("attempts"), j.optInt("errors"), j.optInt("streak"), j.optBoolean("mastered"),
        j.optInt("nextAt"), j.optLong("due"), j.optInt("interval"),
        j.optJSONObject("confusions")?.let { c -> c.keys().asSequence().associateWith { c.getInt(it) } } ?: emptyMap(),
        j.optJSONObject("srs")?.let(::readMemory)
    )
    fun write(p: Progress): JSONObject = JSONObject().put("attempts", p.attempts).put("errors", p.errors)
        .put("streak", p.streak).put("mastered", p.mastered).put("nextAt", p.nextAt).put("due", p.due)
        .put("interval", p.interval).put("confusions", JSONObject(p.confusions)).apply {
            p.memory?.let { put("srs", writeMemory(it)) }
        }
    fun writeMemory(m: Fsrs.Memory): JSONObject = JSONObject().put("version", 6)
        .put("stability", m.stability).put("difficulty", m.difficulty).put("phase", m.phase.name)
        .put("learningStep", m.learningStep).put("lastReview", m.lastReview).put("updatedAt", m.updatedAt)
        .put("revision", m.revision).put("history", JSONArray(m.history.map {
            // Compact positional records keep per-skill histories bounded in the existing snapshot format.
            JSONArray(listOf(it.id, it.at, it.rating, it.elapsedDays))
        }))
    private fun readMemory(j: JSONObject): Fsrs.Memory {
        require(j.getInt("version") == 6) { "Неподдерживаемая версия расписания повторений." }
        val history = j.getJSONArray("history")
        require(history.length() <= Fsrs.HISTORY_LIMIT) { "Слишком длинная история повторений." }
        return Fsrs.Memory(j.getDouble("stability"), j.getDouble("difficulty"), Fsrs.Phase.valueOf(j.getString("phase")),
            j.getInt("learningStep"), j.getLong("lastReview"), j.getLong("updatedAt"), j.getString("revision"),
            (0 until history.length()).map { i -> history.getJSONArray(i).let {
                require(it.length() == 4)
                Fsrs.Review(it.getString(0), it.getLong(1), it.getInt(2), it.getInt(3))
            } }).also(::validateMemory)
    }
    fun validate(p: Progress) {
        require(p.attempts >= 0 && p.errors in 0..p.attempts && p.streak in 0..p.attempts &&
            p.nextAt >= 0 && p.due >= 0 && p.interval in 0..Fsrs.MAX_INTERVAL &&
            p.confusions.all { (form, count) -> form.isNotBlank() && count >= 0 }) { "Некорректный прогресс." }
        p.memory?.let {
            validateMemory(it)
            require(p.mastered == (it.phase == Fsrs.Phase.REVIEW)) { "Несогласованное расписание повторений." }
        }
    }
    private fun validateMemory(m: Fsrs.Memory) {
        require(m.stability.isFinite() && m.stability in 0.001..1_000_000_000.0 &&
            m.difficulty.isFinite() && m.difficulty in 1.0..10.0 && m.lastReview >= 0 && m.updatedAt >= m.lastReview &&
            m.revision.length in 1..100 && m.learningStep in 0..1 &&
            (m.phase == Fsrs.Phase.LEARNING || m.learningStep == 0) &&
            m.history.size <= Fsrs.HISTORY_LIMIT && m.history.zipWithNext().all { (a, b) -> a.at <= b.at } && m.history.map { it.id }.distinct().size == m.history.size &&
            m.history.all { it.id.length in 1..100 && it.at in 0..m.lastReview && it.rating in 1..4 && it.elapsedDays >= 0 }) {
            "Некорректное расписание повторений."
        }
    }
}
