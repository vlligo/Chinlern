package com.example.chinlern

import com.example.chinlern.dictionary.OfflineDictionary
import java.text.Normalizer
import java.util.Locale

/** Immutable lookup snapshot. Deliberately independent of learning state and Android. */
class VocabularyLookup(private val entries: List<Entry>) {
    data class Entry(val word: Word, val packId: String, val packName: String) {
        val reference get() = com.example.chinlern.data.VocabularyReference(packId, word.id)
    }

    private data class Indexed(
        val entry: Entry, val forms: Set<String>, val meanings: List<String>,
        val readings: List<String>, val readingKey: String, val cardText: String
    )
    private val index by lazy {
        entries.map { entry ->
            val word = entry.word
            Indexed(entry, word.writtenForms.map(::normalize).toSet(), word.meanings.map(::normalize),
                listOf(word.pinyin, word.numbered).map(::normalize), OfflineDictionary.pinyinKey(word.numbered),
                normalize("${entry.packName} ${word.hanzi} ${word.pinyin} ${word.numbered} ${word.meanings.joinToString(" ")}"))
        }
    }
    private val byForm by lazy {
        buildMap<String, MutableList<Entry>> {
            this@VocabularyLookup.entries.forEach { entry ->
                entry.word.writtenForms.map(::normalize).toSet().forEach { form ->
                    getOrPut(form) { mutableListOf() }.add(entry)
                }
            }
        }
    }

    fun exact(form: String): List<Entry> = byForm[normalize(form)]?.toList().orEmpty()

    internal fun readingForms(): Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        this@VocabularyLookup.entries.forEach { entry ->
            entry.word.writtenForms.forEach { form ->
                getOrPut(form) { linkedSetOf() }.add(entry.word.numbered)
            }
        }
    }

    fun search(query: String): List<Entry> {
        val key = normalize(query)
        if (key.isEmpty()) return emptyList()
        val readingKey = OfflineDictionary.pinyinKey(key)
        val toned = OfflineDictionary.hasExplicitTone(key)
        val tonesMatched = mutableMapOf<String, Boolean>()
        val matches = index.filter { item ->
            key in item.cardText || item.forms.any { key in it } || item.meanings.any { key in it } ||
                if (readingKey.isNotEmpty() && readingKey == item.readingKey) {
                    !toned || tonesMatched.getOrPut(item.entry.word.numbered) {
                        Answers.pinyinMatches(key, item.entry.word.numbered)
                    }
                } else item.readings.any { key in it }
        }
        return (matches.filter { key in it.forms } + matches.filterNot { key in it.forms }).map { it.entry }
    }

    fun memberships(word: Word): List<String> = entries.filter {
        SharedWords.identity(it.word) == SharedWords.identity(word)
    }.map { it.packName }.distinct()

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).trim().replace('ё', 'е')
}
