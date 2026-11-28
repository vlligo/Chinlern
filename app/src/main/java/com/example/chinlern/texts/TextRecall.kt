package com.example.chinlern.texts

import android.content.SharedPreferences
import android.icu.text.BreakIterator
import com.example.chinlern.Fsrs
import com.example.chinlern.ProgressCodec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Scheduled recall of original text spans in the current profile. */
internal class TextRecall(
    private val storage: SharedPreferences,
    private val library: TextLibrary,
    private val clock: () -> Long
) {
    data class Target(
        val id: String, val documentId: String, val revision: Int, val start: Int, val end: Int,
        val title: String, val quote: String, val due: Long, val reviewId: String?,
        val reviews: Int, val assistedReviews: Int
    )
    data class Queue(val targets: List<Target>, val due: List<Target>)

    fun queue(): Queue {
        val root = read(storage)
        val targets = root.keys().asSequence().flatMap { documentId ->
            val rows = root.getJSONObject(documentId)
            rows.keys().asSequence().map { id -> decode(documentId, id, rows.getJSONObject(id)) }
        }.sortedWith(compareBy<Target> { it.due }.thenBy { it.title }.thenBy { it.start }).toList()
        val now = clock()
        return Queue(targets, targets.takeWhile { it.due <= now })
    }

    fun add(selection: TextLibrary.Selection): Target {
        val document = requireNotNull(library.resolve(selection.document)) { "Версия текста больше недоступна." }
        val checked = library.select(document, selection.start, selection.end)
        require(checked.end - checked.start <= MAX_LENGTH && checked.quote.codePoints().anyMatch(Character::isLetterOrDigit)) {
            "Выберите фрагмент от 1 до $MAX_LENGTH знаков с буквами или цифрами."
        }
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(checked.document.text) }
        require(boundaries.isBoundary(checked.start) && boundaries.isBoundary(checked.end)) { "Выберите целые символы." }
        val root = read(storage)
        val rows = root.optJSONObject(checked.document.id) ?: JSONObject()
        rows.keys().asSequence().forEach { id ->
            val existing = decode(checked.document.id, id, rows.getJSONObject(id))
            if (existing.revision == checked.document.revision && existing.start == checked.start && existing.end == checked.end) {
                return existing
            }
        }
        val id = UUID.randomUUID().toString()
        val row = JSONObject().put("revision", checked.document.revision)
            .put("start", checked.start).put("end", checked.end)
            .put("title", checked.document.title).put("quote", checked.quote)
        rows.put(id, row)
        root.put(checked.document.id, rows)
        write(root)
        return decode(checked.document.id, id, row)
    }

    fun selection(target: Target): TextLibrary.Selection? {
        val row = read(storage).optJSONObject(target.documentId)?.optJSONObject(target.id) ?: return null
        return selection(target, row)
    }

    private fun selection(target: Target, row: JSONObject): TextLibrary.Selection? {
        if (row.getInt("start") != target.start || row.getInt("end") != target.end ||
            row.getString("quote") != target.quote) return null
        val document = library.revision(target.documentId, row.getInt("revision")) ?: return null
        return runCatching { library.select(document, target.start, target.end) }.getOrNull()?.takeIf {
            it.quote == target.quote
        }
    }

    fun record(target: Target, attemptId: String, rating: Fsrs.Rating, assisted: Boolean): Target {
        val root = read(storage)
        val row = requireNotNull(root.optJSONObject(target.documentId)?.optJSONObject(target.id)) {
            "Фрагмент больше недоступен."
        }
        require(selection(target, row) != null) { "Исходный фрагмент больше недоступен." }
        val memory = row.optJSONObject("memory")?.let(ProgressCodec::readMemory)
        if (memory?.revision == attemptId) return decode(target.documentId, target.id, row)
        require(memory?.revision == target.reviewId) { "Фрагмент уже повторён. Откройте его снова." }
        val effective = if (assisted && rating.value > Fsrs.Rating.HARD.value) Fsrs.Rating.HARD else rating
        val schedule = Fsrs.schedule(memory, effective, clock(), reviewId = attemptId)
        val assistedIds = row.optJSONArray("assistedHistory")
        val rememberedAssistance = assistedIds?.let { (0 until it.length()).map(it::getString) }.orEmpty().toSet()
        row.put("memory", ProgressCodec.writeMemory(schedule.memory)).put("due", schedule.due)
            .put("reviews", row.optInt("reviews") + 1)
            .put("assistedReviews", row.optInt("assistedReviews") + if (assisted) 1 else 0)
            .put("assistedHistory", JSONArray(schedule.memory.history.map { it.id }.filter {
                it in rememberedAssistance || (assisted && it == attemptId)
            }))
        write(root)
        return decode(target.documentId, target.id, row)
    }

    fun remove(target: Target) {
        val root = read(storage)
        val rows = root.optJSONObject(target.documentId) ?: return
        if (rows.remove(target.id) == null) return
        if (rows.length() == 0) root.remove(target.documentId)
        write(root)
    }

    private fun decode(documentId: String, id: String, row: JSONObject) = Target(id, documentId,
        row.getInt("revision"), row.getInt("start"), row.getInt("end"), row.getString("title"),
        row.getString("quote"), row.optLong("due"), row.optJSONObject("memory")?.getString("revision"),
        row.optInt("reviews"), row.optInt("assistedReviews"))

    private fun write(root: JSONObject) { check(stage(storage.edit(), root).commit()) }

    companion object {
        const val MAX_LENGTH = 400
        internal const val KEY = "textRecallV1"
        private fun read(storage: SharedPreferences) = JSONObject(storage.getString(KEY, "{}")!!)
        private fun stage(editor: SharedPreferences.Editor, root: JSONObject) =
            if (root.length() == 0) editor.remove(KEY) else editor.putString(KEY, root.toString())

        fun removeDocument(storage: SharedPreferences, editor: SharedPreferences.Editor, documentId: String) {
            val root = read(storage)
            if (root.remove(documentId) != null) stage(editor, root)
        }
    }
}
