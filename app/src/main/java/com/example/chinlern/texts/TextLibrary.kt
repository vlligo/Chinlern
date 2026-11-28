package com.example.chinlern.texts

import android.content.SharedPreferences
import android.icu.text.BreakIterator
import com.example.chinlern.data.VocabularyReference
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Original text and immutable revisions; selections always refer to one exact revision. */
class TextLibrary(private val storage: SharedPreferences) {
    data class WordNote(val start: Int, val end: Int, val pinyin: String, val gloss: String = "") {
        internal val span get() = ReaderWords.Span(start, end)
    }
    data class Document(
        val id: String, val title: String, val text: String, val revision: Int, val source: String,
        val wordNotes: List<WordNote> = emptyList(), val translation: String = "",
        internal val revisionId: String = ""
    )
    internal data class ReadingStyle(val size: Int = 28, val spacing: Int = 6) {
        companion object {
            val sizes = listOf(24, 28, 34, 40)
            val spacings = listOf(3, 6, 12)
        }
    }
    internal data class SavedContext(
        val revision: Int, val start: Int, val end: Int, val quote: String, val sentence: String,
        val revisionId: String
    ) {
        val label get() = "$quote — $sentence"
    }
    data class Selection(val document: Document, val start: Int, val end: Int) {
        val quote get() = document.text.substring(start, end)
        val sentence: String get() = sentenceRange(document.text, start, end).let {
            document.text.substring(it.start, it.end)
        }
    }
    companion object {
        internal const val KEY = "textLibraryV1"
        internal const val POSITION_PREFIX = "readerPosition:"
        internal const val PINYIN_PREFIX = "readerPinyin:"

        internal fun sentenceRange(text: String, start: Int, end: Int): ReaderWords.Span {
            val separators = "。！？!?\n"
            val left = (start - 1 downTo 0).firstOrNull { text[it] in separators }?.plus(1) ?: 0
            val right = (end until text.length).firstOrNull { text[it] in separators }?.plus(1) ?: text.length
            return ReaderWords.Span(left, right)
        }
    }
    private fun read() = JSONObject(storage.getString(KEY, "{}")!!)
    private fun write(root: JSONObject) { check(storage.edit().putString(KEY, root.toString()).commit()) }
    internal var inlinePinyin: Boolean
        get() = storage.getBoolean("readerInlinePinyin", false)
        set(enabled) { storage.edit().putBoolean("readerInlinePinyin", enabled).apply() }

    internal var readingStyle: ReadingStyle
        get() = ReadingStyle(storage.getInt("readerTextSize", 28), storage.getInt("readerLineSpacing", 6))
        set(style) {
            require(style.size in ReadingStyle.sizes && style.spacing in ReadingStyle.spacings)
            storage.edit().putInt("readerTextSize", style.size).putInt("readerLineSpacing", style.spacing).apply()
        }

    private fun positionKey(id: String) = "$POSITION_PREFIX$id"
    private fun readingPositions(id: String) = JSONObject(storage.getString(positionKey(id), "{}")!!)

    internal fun readingPosition(document: Document): Int =
        resolve(document)?.let {
            readingPositions(it.id).optInt(it.revision.toString()).coerceIn(0, it.text.length)
        } ?: 0

    internal fun saveReadingPosition(document: Document, offset: Int) {
        require(offset in 0..document.text.length)
        val positions = readingPositions(document.id)
        val revision = resolve(document)?.revision?.toString() ?: return
        if (positions.optInt(revision) == offset) return
        if (offset == 0) positions.remove(revision) else positions.put(revision, offset)
        val editor = storage.edit()
        if (positions.length() == 0) editor.remove(positionKey(document.id))
        else editor.putString(positionKey(document.id), positions.toString())
        editor.apply()
    }

    internal fun pinyinChoices(document: Document): Map<ReaderWords.Span, String> {
        val revision = resolve(document)?.revision?.toString() ?: return emptyMap()
        return pinyinOverrides(document.id).optJSONObject(revision)?.let { choices ->
            choices.keys().asSequence().associate { key ->
                val (start, end) = key.split(':', limit = 2)
                ReaderWords.Span(start.toInt(), end.toInt()) to choices.getString(key)
            }
        }.orEmpty()
    }

    internal fun choosePinyin(selection: Selection, reading: String?) {
        val overrides = pinyinOverrides(selection.document.id)
        val revision = resolve(selection.document)?.revision?.toString() ?: return
        select(selection.document, selection.start, selection.end)
        val choices = overrides.optJSONObject(revision) ?: JSONObject()
        val key = "${selection.start}:${selection.end}"
        if (reading == null) choices.remove(key) else choices.put(key, reading)
        if (choices.length() == 0) overrides.remove(revision) else overrides.put(revision, choices)
        val editor = storage.edit()
        if (overrides.length() == 0) editor.remove(pinyinKey(selection.document.id))
        else editor.putString(pinyinKey(selection.document.id), overrides.toString())
        editor.apply()
    }

    private fun pinyinKey(documentId: String) = "$PINYIN_PREFIX$documentId"
    private fun pinyinOverrides(documentId: String) = JSONObject(storage.getString(pinyinKey(documentId), "{}")!!)
    fun documents(): List<Document> = read().let { root -> root.keys().asSequence().map { id ->
        val item = root.getJSONObject(id); val revisions = item.getJSONArray("revisions")
        decode(id, revisions.getJSONObject(revisions.length() - 1))
    }.sortedBy { it.title }.toList() }
    fun save(title: String, text: String, source: String = "", previous: Document? = null,
        translation: String = ""): Document =
        saveRevision(title, text, source, previous, emptyList(), translation)

    internal fun saveAnnotated(title: String, input: String, source: String, previous: Document?,
        translation: String = ""): Document {
        val passage = AnnotatedText.parse(input)
        return saveRevision(title, passage.text, source, previous, passage.notes, translation)
    }

    private fun saveRevision(title: String, text: String, source: String, previous: Document?,
        notes: List<WordNote>, translation: String): Document {
        require(title.trim().length in 1..120) { "Введите название до 120 символов." }
        require(text.isNotBlank() && text.length <= 20000) { "Текст должен содержать от 1 до 20 000 символов." }
        require(source.length <= 1000) { "Источник слишком длинный." }
        require(translation.length <= 20000) { "Перевод должен содержать не более 20 000 символов." }
        if (notes.isNotEmpty()) {
            val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
            require(notes.all { boundaries.isBoundary(it.start) && boundaries.isBoundary(it.end) }) {
                "Пометки должны охватывать целые символы."
            }
        }
        val root = read(); val id = previous?.id ?: UUID.randomUUID().toString()
        val item = if (previous == null) JSONObject().put("revisions", JSONArray()).put("annotations", JSONArray()) else requireNotNull(root.optJSONObject(id)) { "Текст больше недоступен." }
        val revisions = item.getJSONArray("revisions")
        require(previous == null || previous == decode(id, revisions.getJSONObject(revisions.length() - 1))) { "Текст уже изменён. Откройте его заново." }
        val revision = revisions.length() + 1
        val row = JSONObject().put("revision", revision).put("syncId", UUID.randomUUID().toString())
            .put("title", title.trim()).put("text", text).put("source", source)
        if (translation.isNotEmpty()) row.put("translation", translation)
        if (notes.isNotEmpty()) {
            val values = JSONArray()
            val spans = JSONArray()
            val ids = mutableMapOf<Pair<String, String>, Int>()
            notes.forEach { note ->
                val value = ids.getOrPut(note.pinyin to note.gloss) {
                    values.length().also { values.put(JSONArray(listOf(note.pinyin, note.gloss))) }
                }
                spans.put(JSONArray(listOf(note.start, note.end, value)))
            }
            row.put("wordNotes", spans).put("wordNoteValues", values)
        }
        revisions.put(row)
        root.put(id, item); write(root)
        return decode(id, row)
    }
    fun select(document: Document, start: Int, end: Int): Selection {
        require(start >= 0 && end > start && end <= document.text.length)
        fun boundary(index: Int) = index == 0 || index == document.text.length ||
            !(Character.isHighSurrogate(document.text[index - 1]) && Character.isLowSurrogate(document.text[index]))
        require(boundary(start) && boundary(end)) { "Выберите целый символ." }
        return Selection(document, start, end)
    }
    fun annotate(selection: Selection, reference: VocabularyReference) {
        check(stageAnnotation(storage.edit(), selection, reference).commit())
    }
    internal fun stageAnnotation(editor: SharedPreferences.Editor, selection: Selection,
        reference: VocabularyReference): SharedPreferences.Editor {
        val root = read()
        val item = requireNotNull(root.optJSONObject(selection.document.id)) { "Текст больше недоступен." }
        val revision = requireNotNull(resolve(selection.document)) {
            "Версия текста больше недоступна."
        }.revision
        select(selection.document, selection.start, selection.end)
        val annotations = item.getJSONArray("annotations")
        val row = JSONObject().put("revision", revision).put("start", selection.start)
            .put("end", selection.end).put("quote", selection.quote).put("sentence", selection.sentence)
            .put("packId", reference.packId).put("wordId", reference.wordId)
        val linked = (0 until annotations.length()).any { index ->
            val previous = annotations.getJSONObject(index)
            previous.getInt("revision") == revision &&
                previous.getInt("start") == selection.start && previous.getInt("end") == selection.end &&
                previous.getString("packId") == reference.packId && previous.getString("wordId") == reference.wordId
        }
        if (!linked) {
            annotations.put(row)
            editor.putString(KEY, root.toString())
        }
        return editor
    }
    fun savedContexts(document: Document): List<String> =
        savedContextLinks(document.id).filter { it.revisionId == document.revisionId }.map { it.label }

    internal fun savedContextLinks(id: String): List<SavedContext> {
        val item = read().optJSONObject(id) ?: return emptyList()
        val revisions = item.getJSONArray("revisions")
        val rows = item.getJSONArray("annotations")
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val revision = row.getInt("revision")
            SavedContext(revision, row.getInt("start"), row.getInt("end"),
                row.getString("quote"), row.getString("sentence"), TextSync.revisionId(revisions.getJSONObject(revision - 1)))
        }
    }

    internal fun contextSelection(id: String, context: SavedContext): Selection? {
        val document = revision(id, context.revision, context.revisionId) ?: return null
        return runCatching { select(document, context.start, context.end) }.getOrNull()?.takeIf {
            it.quote == context.quote
        }
    }

    internal fun revision(id: String, number: Int, revisionId: String?): Document? {
        val rows = read().optJSONObject(id)?.getJSONArray("revisions") ?: return null
        val row = rows.optJSONObject(number - 1)
        if (row != null && (revisionId == null || TextSync.revisionId(row) == revisionId)) return decode(id, row)
        return (0 until rows.length()).asSequence().map(rows::getJSONObject)
            .firstOrNull { TextSync.revisionId(it) == revisionId }?.let { decode(id, it) }
    }

    internal fun revision(id: String, number: Int): Document? =
        read().optJSONObject(id)?.getJSONArray("revisions")?.optJSONObject(number - 1)?.let { decode(id, it) }

    fun revisions(id: String): List<Document> =
        read().optJSONObject(id)?.getJSONArray("revisions")?.let { rows ->
            List(rows.length()) { decode(id, rows.getJSONObject(it)) }
        }.orEmpty()

    internal fun resolve(document: Document): Document? =
        revision(document.id, document.revision, document.revisionId)
            ?.takeIf { it == document.copy(revision = it.revision) }

    fun delete(document: Document) {
        val root = read()
        root.remove(document.id)
        val editor = storage.edit().putString(KEY, root.toString())
            .remove(pinyinKey(document.id)).remove(positionKey(document.id))
        TextRecall.removeDocument(storage, editor, document.id)
        check(editor.commit())
    }
    private fun decode(id: String, row: JSONObject): Document {
        val notes = row.optJSONArray("wordNotes")?.let { spans ->
            val values = row.getJSONArray("wordNoteValues")
            List(spans.length()) { index ->
                val span = spans.getJSONArray(index)
                val value = values.getJSONArray(span.getInt(2))
                WordNote(span.getInt(0), span.getInt(1), value.getString(0), value.getString(1))
            }
        }.orEmpty()
        return Document(id, row.getString("title"), row.getString("text"), row.getInt("revision"),
            row.optString("source"), notes, row.optString("translation"), TextSync.revisionId(row))
    }
}
