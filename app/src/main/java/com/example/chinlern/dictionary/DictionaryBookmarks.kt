package com.example.chinlern.dictionary

import android.content.SharedPreferences
import org.json.JSONArray

/** A bookmark identifies one reading of a form pair in the bundled dictionary. */
internal class DictionaryBookmarks(private val storage: SharedPreferences) {
    data class Reference(val simplified: String, val traditional: String, val pinyin: String,
        val source: OfflineDictionary.Source = OfflineDictionary.Source.CEDICT) {
        val forms get() = listOf(simplified, traditional).distinct()
        val readingKey get() = Pinyin.readingKey(pinyin)
        fun matches(other: Reference) = simplified == other.simplified && traditional == other.traditional &&
            readingKey == other.readingKey && source == other.source
    }

    fun all(): List<Reference> {
        val rows = JSONArray(storage.getString(KEY, "[]")!!)
        return List(rows.length()) { index ->
            val row = rows.getJSONArray(index)
            val sourceId = row.optString(3, OfflineDictionary.Source.CEDICT.id)
            val source = OfflineDictionary.Source.entries.firstOrNull { it.id == sourceId } ?: OfflineDictionary.Source.CEDICT
            Reference(row.getString(0), row.getString(1), row.getString(2), source)
        }
    }

    fun contains(reference: Reference) = all().any(reference::matches)

    fun toggle(reference: Reference): Boolean {
        val current = all()
        val without = current.filterNot(reference::matches)
        val add = current.size == without.size
        val updated = if (add) listOf(reference) + current else without
        val editor = storage.edit()
        if (updated.isEmpty()) editor.remove(KEY)
        else editor.putString(KEY, JSONArray(updated.map { JSONArray(it.formsForStorage()) }).toString())
        check(editor.commit()) { "Не удалось сохранить закладки." }
        return add
    }

    private fun Reference.formsForStorage() = listOf(simplified, traditional, pinyin, source.id)

    private companion object { const val KEY = "dictionaryBookmarksV1" }
}
