package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

/** Portable learning data only: never Firebase credentials, sync unlocks or device permissions. */
object LibraryBackup {
    const val MAX_BYTES = 32_000_000
    const val FORMAT = "chinlearn-backup"
    data class Data(val packs: Map<String, String>, val practicePacks: Set<String>)
    data class Merge(val packs: Map<String, String>, val added: Int, val updated: Int, val conflicts: Int)

    fun encode(data: Data): String {
        val packs = JSONObject()
        data.packs.forEach { (id, value) -> packs.put(id, JSONObject(value)) }
        val result = JSONObject().put("format", FORMAT).put("version", 1)
            .put("createdAt", System.currentTimeMillis()).put("packs", packs)
            .put("practicePacks", JSONArray(data.practicePacks.sorted())).toString()
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Резервная копия превышает 32 МБ." }
        return result
    }

    fun decode(text: String): Data {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Резервная копия превышает 32 МБ." }
        val tokener = JSONTokener(text.removePrefix("\uFEFF"))
        val json = tokener.nextValue() as? JSONObject ?: error("Ожидался файл резервной копии Chinlearn.")
        require(tokener.nextClean() == '\u0000') { "Лишние данные после резервной копии." }
        require(json.getString("format") == FORMAT && json.getInt("version") == 1) { "Неподдерживаемый формат резервной копии." }
        val packs = json.getJSONObject("packs")
        val decoded = packs.keys().asSequence().associateWith { SyncSnapshot.pack(packs.getJSONObject(it)) }
        require(decoded.isNotEmpty()) { "В резервной копии нет наборов." }
        val selected = json.getJSONArray("practicePacks")
        val ids = (0 until selected.length()).map { selected.getString(it) }.toSet()
        require(ids.all { it in decoded }) { "В копии указан неизвестный набор для практики." }
        return Data(decoded, ids)
    }

    private fun same(a: Any?, b: Any?) = SyncSnapshot.canonical(a) == SyncSnapshot.canonical(b)
    private fun words(pack: JSONObject): List<JSONObject> {
        val array = JSONObject(pack.getString("words")).getJSONArray("words")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun progressAtStep(progress: JSONObject, sourceStep: Int, targetStep: Int): JSONObject = JSONObject(progress.toString()).apply {
        val remaining = (getLong("nextAt") - sourceStep).coerceAtLeast(0)
        put("nextAt", (targetStep.toLong() + remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Union additions. Keep local conflicting values and retain the complete incoming version as a copy. */
    fun merge(local: Map<String, String>, incoming: Map<String, String>): Merge {
        val result = local.toMutableMap()
        var added = 0; var updated = 0; var conflicts = 0
        incoming.toSortedMap().forEach { (id, text) ->
            val existing = local[id]
            if (existing == null) { result[id] = text; added++; return@forEach }
            if (existing == text) return@forEach
            val left = JSONObject(existing); val right = JSONObject(text)
            var conflict = left.getString("name") != right.getString("name") ||
                left.optString("folder", "") != right.optString("folder", "") ||
                left.optBoolean("hidden", false) != right.optBoolean("hidden", false)
            val mergedWords = words(left).associateByTo(linkedMapOf()) { it.getString("id") }
            val compatible = mutableSetOf<String>()
            words(right).forEach { word ->
                val wordId = word.getString("id")
                val old = mergedWords[wordId]
                when {
                    old != null && same(old, word) -> compatible.add(wordId)
                    old != null -> conflict = true
                    mergedWords.values.any { it.getString("hanzi") == word.getString("hanzi") } -> conflict = true
                    else -> { mergedWords[wordId] = word; compatible.add(wordId) }
                }
            }
            val wordDocument = JSONObject(left.getString("words")).put("words", JSONArray(mergedWords.values.toList()))
            left.put("words", wordDocument.toString())
            val selected = left.getJSONArray("selected")
            val selection = (0 until selected.length()).map { selected.getString(it) }.toMutableSet()
            val incomingSelection = right.getJSONArray("selected")
            (0 until incomingSelection.length()).map { incomingSelection.getString(it) }.filter { it in compatible }.forEach { selection.add(it) }
            left.put("selected", JSONArray(selection.sorted()))
            val lp = left.getJSONObject("progress"); val rp = right.getJSONObject("progress")
            rp.keys().forEach progressLoop@ { key ->
                if (key.substringBefore(':') !in compatible) return@progressLoop
                val newProgress = progressAtStep(rp.getJSONObject(key), right.getInt("step"), left.getInt("step"))
                val oldProgress = lp.optJSONObject(key)
                if (oldProgress == null || oldProgress.optInt("attempts") == 0) lp.put(key, newProgress)
                else if (newProgress.optInt("attempts") > 0 && !same(
                        progressAtStep(oldProgress, left.getInt("step"), 0),
                        progressAtStep(newProgress, left.getInt("step"), 0))) conflict = true
            }
            val merged = SyncSnapshot.pack(left)
            if (merged != existing) { result[id] = merged; updated++ }
            if (conflict) {
                val copy = JSONObject(text).put("name", right.getString("name").take(65) + " (из файла)")
                val copyText = SyncSnapshot.pack(copy)
                val hash = MessageDigest.getInstance("SHA-256").digest("$id\n$text".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }.take(32)
                var copyId = "backup-$hash"; var suffix = 0
                // Shared learning can update this copy after import. That is still the same
                // imported vocabulary, not a reason to create another copy on every retry.
                fun sameContent(text: String): Boolean {
                    val existingCopy = JSONObject(text)
                    return existingCopy.getString("name") == copy.getString("name") &&
                        existingCopy.optString("folder", "") == copy.optString("folder", "") &&
                        same(JSONObject(existingCopy.getString("words")), JSONObject(copy.getString("words")))
                }
                while ((copyId in result && !sameContent(result.getValue(copyId))) || copyId in incoming) { suffix++; copyId = "backup-$hash-$suffix" }
                if (copyId !in result) { result[copyId] = copyText; conflicts++ }
            }
        }
        require(SyncSnapshot.encode(result).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Объединённая библиотека превышает 32 МБ. Данные не изменены." }
        return Merge(result, added, updated, conflicts)
    }
}
