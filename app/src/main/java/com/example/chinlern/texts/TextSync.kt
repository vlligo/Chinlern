package com.example.chinlern.texts

import android.content.SharedPreferences
import android.icu.text.BreakIterator
import com.example.chinlern.Fsrs
import com.example.chinlern.ProgressCodec
import com.example.chinlern.SyncSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Cloud references use immutable revision IDs. The reader still displays local revision numbers. */
internal object TextSync {
    private const val PLAIN = "document"
    private const val ANNOTATIONS = "annotations"
    private const val RECALL = "recall"
    private const val PINYIN = "pinyin"
    private const val POSITION = "position"

    fun revisionId(row: JSONObject): String = row.optString("syncId").ifEmpty {
        // Pre-sync revisions have the same ID wherever an identical local library was copied.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.digest(SyncSnapshot.canonical(row).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isDataKey(key: String?) = key == TextLibrary.KEY || key == TextRecall.KEY ||
        key?.startsWith(TextLibrary.PINYIN_PREFIX) == true ||
        key?.startsWith(TextLibrary.POSITION_PREFIX) == true

    fun snapshot(storage: SharedPreferences): Map<String, String> {
        fun read(key: String) = JSONObject(storage.getString(key, "{}")!!)
        val library = read(TextLibrary.KEY)
        val recall = read(TextRecall.KEY)
        return library.keys().asSequence().associateWith { id ->
            val document = library.getJSONObject(id)
            val pinyin = read(TextLibrary.PINYIN_PREFIX + id)
            val positions = read(TextLibrary.POSITION_PREFIX + id)
            val targets = recall.optJSONObject(id)
            val revisions = document.getJSONArray("revisions").rows().map { row ->
                val number = row.getString("revision")
                val plain = JSONObject(row.toString())
                plain.put("syncId", revisionId(row)).remove("revision")
                JSONObject().put(PLAIN, plain).put(ANNOTATIONS, JSONObject()).put(RECALL, JSONObject())
                    .put(PINYIN, pinyin.optJSONObject(number) ?: JSONObject())
                    .put(POSITION, positions.optInt(number))
            }
            document.getJSONArray("annotations").rows().forEach { row ->
                val entry = JSONObject(row.toString()).apply { remove("revision") }
                val key = SyncSnapshot.canonical(JSONArray(listOf(spanKey(row), row.getString("packId"), row.getString("wordId"))))
                revisions[row.getInt("revision") - 1].getJSONObject(ANNOTATIONS).put(key, entry)
            }
            targets?.keys()?.forEach { targetId ->
                val row = targets.getJSONObject(targetId)
                val entry = JSONObject(row.toString()).apply { remove("revision"); put("id", targetId) }
                revisions[row.getInt("revision") - 1].getJSONObject(RECALL).put(spanKey(row), entry)
            }
            SyncSnapshot.canonical(JSONArray(revisions))
        }
    }

    fun merge(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>): Map<String, String> =
        (base.keys + local.keys + remote.keys).mapNotNull { id ->
            val b = base[id]
            val l = local[id]
            val r = remote[id]
            val chosen = when {
                l == r -> l
                l == b -> r
                r == b -> l
                // A deletion also removes all reading state for that text.
                l == null || r == null -> if (b == null) l ?: r else null
                else -> mergeDocument(b, l, r)
            }
            chosen?.let { id to it }
        }.toMap()

    private fun mergeDocument(base: String?, local: String, remote: String): String {
        fun revisions(text: String?) = JSONArray(text ?: "[]").rows().associateBy {
            it.getJSONObject(PLAIN).getString("syncId")
        }
        val baseRows = revisions(base)
        val localRows = revisions(local)
        val remoteRows = revisions(remote)
        // Keep published revision numbers and append any unpublished revisions in their local order.
        val merged = (remoteRows.keys + localRows.keys).map { id ->
            val l = localRows[id]
            val r = remoteRows[id]
            if (l == null || r == null) return@map (l ?: r)!!
            val b = baseRows[id]
            require(same(l.getJSONObject(PLAIN), r.getJSONObject(PLAIN))) { "Версии текста не совпадают." }
            JSONObject().put(PLAIN, r.getJSONObject(PLAIN))
                .put(ANNOTATIONS, mergeFields(b, l, r, ANNOTATIONS))
                .put(PINYIN, mergeFields(b, l, r, PINYIN))
                .put(POSITION, choose(b?.opt(POSITION), l.get(POSITION), r.get(POSITION)))
                .put(RECALL, mergeFields(b, l, r, RECALL) { localTarget, remoteTarget ->
                    mergeTarget(localTarget as JSONObject, remoteTarget as JSONObject)
                })
        }
        return SyncSnapshot.canonical(JSONArray(merged))
    }

    private fun mergeFields(base: JSONObject?, local: JSONObject, remote: JSONObject, field: String,
        mergeConflict: (Any, Any) -> Any = { l, _ -> l }): JSONObject {
        val b = base?.getJSONObject(field) ?: JSONObject()
        val l = local.getJSONObject(field)
        val r = remote.getJSONObject(field)
        return JSONObject().apply {
            (b.keys().asSequence() + l.keys().asSequence() + r.keys().asSequence()).distinct().forEach { key ->
                choose(b.opt(key), l.opt(key), r.opt(key), mergeConflict)?.let { put(key, it) }
            }
        }
    }

    private fun choose(base: Any?, local: Any?, remote: Any?, mergeConflict: (Any, Any) -> Any = { l, _ -> l }): Any? =
        when {
            same(local, remote) -> local
            same(local, base) -> remote
            same(remote, base) -> local
            local == null || remote == null -> null
            else -> mergeConflict(local, remote)
        }

    private fun same(a: Any?, b: Any?) = a == b || SyncSnapshot.canonical(a) == SyncSnapshot.canonical(b)

    private fun mergeTarget(local: JSONObject, remote: JSONObject): JSONObject {
        val lm = local.optJSONObject("memory")?.let(ProgressCodec::readMemory)
        val rm = remote.optJSONObject("memory")?.let(ProgressCodec::readMemory)
        val chosen = listOfNotNull(lm, rm).maxWithOrNull(compareBy<Fsrs.Memory> { it.updatedAt }.thenBy { it.revision })
        val state = if (chosen == rm) remote else local
        val result = JSONObject(state.toString()).put("id", remote.getString("id"))
        if (chosen == null) return result
        val histories = listOfNotNull(lm, rm).flatMap { it.history }.distinctBy { it.id }
            .sortedWith(compareBy<Fsrs.Review> { it.at }.thenBy { it.id })
        val assisted = listOf(local, remote).flatMap { row ->
            row.optJSONArray("assistedHistory")?.let { ids -> List(ids.length(), ids::getString) }.orEmpty()
        }.toSet()
        val retained = histories.filter { it.at <= chosen.lastReview }.takeLast(Fsrs.HISTORY_LIMIT)
        return result.put("memory", ProgressCodec.writeMemory(chosen.copy(history = retained)))
            .put("reviews", maxOf(local.optInt("reviews"), remote.optInt("reviews"), histories.size))
            .put("assistedReviews", maxOf(local.optInt("assistedReviews"), remote.optInt("assistedReviews"), assisted.size))
            .put("assistedHistory", JSONArray(retained.map { it.id }.filter { it in assisted }))
    }

    fun validate(snapshot: Map<String, String>) {
        snapshot.forEach { (id, data) -> decodeDocument(id, data) }
    }

    fun stage(editor: SharedPreferences.Editor, currentIds: Set<String>, snapshot: Map<String, String>) {
        val library = JSONObject()
        val recall = JSONObject()
        (currentIds - snapshot.keys).forEach { id ->
            editor.remove(TextLibrary.PINYIN_PREFIX + id).remove(TextLibrary.POSITION_PREFIX + id)
        }
        snapshot.forEach { (id, data) ->
            val document = decodeDocument(id, data)
            library.put(id, document.text)
            if (document.recall.length() > 0) recall.put(id, document.recall)
            editor.putOrRemove(TextLibrary.PINYIN_PREFIX + id, document.pinyin)
            editor.putOrRemove(TextLibrary.POSITION_PREFIX + id, document.positions)
        }
        editor.putOrRemove(TextLibrary.KEY, library)
        editor.putOrRemove(TextRecall.KEY, recall)
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: JSONObject) {
        if (value.length() == 0) remove(key) else putString(key, value.toString())
    }

    private data class LocalDocument(
        val text: JSONObject, val recall: JSONObject, val pinyin: JSONObject, val positions: JSONObject
    )

    private fun decodeDocument(id: String, data: String): LocalDocument {
        require(id.length in 1..100)
        val revisions = JSONArray()
        val annotations = JSONArray()
        val targets = JSONObject()
        val pinyin = JSONObject()
        val positions = JSONObject()
        val seenIds = mutableSetOf<String>()
        JSONArray(data).rows().forEachIndexed { index, row ->
            val number = index + 1
            val plain = row.getJSONObject(PLAIN)
            val revisionId = plain.getString("syncId")
            require(revisionId.length in 1..100 && seenIds.add(revisionId))
            val title = plain.getString("title")
            val text = plain.getString("text")
            require(title.length in 1..120 && text.isNotBlank() && text.length <= 20_000 &&
                plain.optString("source").length <= 1_000 && plain.optString("translation").length <= 20_000)
            val boundaries by lazy { BreakIterator.getCharacterInstance().apply { setText(text) } }
            plain.optJSONArray("wordNotes")?.let { spans ->
                val values = plain.getJSONArray("wordNoteValues")
                repeat(spans.length()) {
                    val span = spans.getJSONArray(it)
                    val start = span.getInt(0)
                    val end = span.getInt(1)
                    requireSpan(text, start, end)
                    require(boundaries.isBoundary(start) && boundaries.isBoundary(end))
                    val value = values.getJSONArray(span.getInt(2))
                    value.getString(0)
                    value.getString(1)
                }
            }
            revisions.put(plain.put("revision", number))
            val offset = row.getInt(POSITION)
            require(offset in 0..text.length)
            if (offset > 0) positions.put(number.toString(), offset)
            val choices = row.getJSONObject(PINYIN)
            choices.keys().forEach { key ->
                val indexes = key.split(':', limit = 2)
                require(indexes.size == 2)
                requireSpan(text, indexes[0].toInt(), indexes[1].toInt())
                choices.getString(key)
            }
            if (choices.length() > 0) pinyin.put(number.toString(), choices)
            val links = row.getJSONObject(ANNOTATIONS)
            links.keys().forEach { key ->
                val link = links.getJSONObject(key)
                requireQuote(text, link)
                val range = TextLibrary.sentenceRange(text, link.getInt("start"), link.getInt("end"))
                require(link.getString("sentence") == text.substring(range.start, range.end))
                require(key == SyncSnapshot.canonical(JSONArray(listOf(spanKey(link), link.getString("packId"), link.getString("wordId")))))
                annotations.put(link.put("revision", number))
            }
            val recall = row.getJSONObject(RECALL)
            recall.keys().forEach { key ->
                val target = recall.getJSONObject(key)
                requireQuote(text, target)
                val quote = target.getString("quote")
                require(key == spanKey(target) && quote.length <= TextRecall.MAX_LENGTH &&
                    quote.codePoints().anyMatch(Character::isLetterOrDigit))
                require(boundaries.isBoundary(target.getInt("start")) && boundaries.isBoundary(target.getInt("end")))
                require(target.getString("title") == title)
                require(target.optLong("due") >= 0 && target.optInt("assistedReviews") in 0..target.optInt("reviews"))
                val memory = target.optJSONObject("memory")?.let(ProgressCodec::readMemory)
                val assisted = target.optJSONArray("assistedHistory")
                require(assisted == null || (0 until assisted.length()).all { i ->
                    memory?.history?.any { it.id == assisted.getString(i) } == true
                })
                val targetId = target.getString("id")
                require(targetId.length in 1..100 && !targets.has(targetId))
                target.remove("id")
                targets.put(targetId, target.put("revision", number))
            }
        }
        require(revisions.length() > 0)
        return LocalDocument(JSONObject().put("revisions", revisions).put("annotations", annotations), targets, pinyin, positions)
    }

    private fun JSONArray.rows() = List(length(), ::getJSONObject)

    private fun spanKey(row: JSONObject) = "${row.getInt("start")}:${row.getInt("end")}"

    private fun requireQuote(text: String, row: JSONObject) {
        val start = row.getInt("start")
        val end = row.getInt("end")
        requireSpan(text, start, end)
        require(row.getString("quote") == text.substring(start, end)) { "Сохранённый фрагмент не совпадает с текстом." }
    }

    private fun requireSpan(text: String, start: Int, end: Int) {
        fun boundary(index: Int) = index == 0 || index == text.length ||
            !(Character.isHighSurrogate(text[index - 1]) && Character.isLowSurrogate(text[index]))
        require(start >= 0 && end > start && end <= text.length && boundary(start) && boundary(end)) {
            "Некорректные границы фрагмента текста."
        }
    }
}
