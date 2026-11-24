package com.example.chinlern

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal class BookExamples(catalog: JSONObject) {
    private val cards = catalog.getJSONObject("cards")
    private val examples = catalog.getJSONArray("examples")

    fun fill(text: String): String = try {
        fillValidWords(text)
    } catch (_: org.json.JSONException) {
        // Leave malformed legacy data to the existing vocabulary loader.
        text
    }

    private fun fillValidWords(text: String): String {
        val root = if (text.trimStart().startsWith("[")) null else JSONObject(text)
        val words = root?.getJSONArray("words") ?: JSONArray(text)
        var changed = false
        for (i in 0 until words.length()) {
            val word = words.getJSONObject(i)
            val entry = cards.optJSONObject(word.optString("hanzi")) ?: continue
            if (!entry.getString("numbered").equals(word.optString("numbered"), ignoreCase = true)) continue
            val senses = entry.getJSONArray("senses")
            val meanings = word.getJSONArray("meanings").asMeanings()
            if ((0 until senses.length()).none { senses.getJSONArray(it).asMeanings() == meanings }) continue
            if (listOf("example", "examplePinyin", "exampleTranslation").any { word.optString(it).isNotBlank() }) continue
            val row = examples.getJSONArray(entry.getInt("row"))
            word.put("example", row.getString(0)).put("examplePinyin", row.getString(1))
                .put("exampleTranslation", row.getString(2))
            val hint = word.optString("hint")
            val note = row.getString(3)
            val combined = listOf(hint, note).filter { it.isNotBlank() }.joinToString("\n\n")
            if (note !in hint && combined.length <= 2000) word.put("hint", combined)
            changed = true
        }
        return if (changed) (root ?: words).toString() else text
    }

    private fun JSONArray.asMeanings() = (0 until length()).map { Answers.meaning(getString(it)) }.toSet()

    companion object {
        fun migrate(context: Context, prefs: SharedPreferences) {
            if (prefs.getBoolean("bookExamplesV1", false)) return
            val catalog = context.assets.open("book-examples.json").bufferedReader().use { BookExamples(JSONObject(it.readText())) }
            val editor = prefs.edit()
            val packs = JSONObject(prefs.getString("packs", "{}")!!)
            var changed = false
            for (id in packs.keys()) {
                val pack = packs.getJSONObject(id)
                if (!pack.has("words")) continue
                val original = pack.getString("words")
                val updated = catalog.fill(original)
                if (updated != original) {
                    pack.put("words", updated)
                    changed = true
                }
            }
            if (changed) editor.putString("packs", packs.toString())
            prefs.getString("words", null)?.let { original ->
                val updated = catalog.fill(original)
                if (updated != original) editor.putString("words", updated)
            }
            editor.putBoolean("bookExamplesV1", true).apply()
        }
    }
}
