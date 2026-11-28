package com.example.chinlern.texts

import android.util.LruCache
import com.example.chinlern.VocabularyLookup
import com.example.chinlern.dictionary.OfflineDictionary

internal object ReaderAnnotations {
    private data class Key(
        val document: TextLibrary.Document,
        val dictionaryVersion: String,
        val personal: Map<String, Set<String>>
    )

    // Generated readings can be rebuilt. The cache has no profile data writes or user choices.
    private val cache = LruCache<Key, List<ReaderWords.Annotation>>(4)

    fun load(
        document: TextLibrary.Document,
        boundaries: IntArray,
        vocabulary: VocabularyLookup,
        dictionary: OfflineDictionary
    ): List<ReaderWords.Annotation> {
        val personal = vocabulary.readingForms()
        val byInitial = personal.entries.groupBy {
            String(Character.toChars(it.key.codePointAt(0)))
        }.mapValues { (_, entries) -> entries.associate { it.key to it.value } }
        val supplied = document.wordNotes.associate { it.span to AnnotatedText.display(it.pinyin) }
        return runCatching {
            val key = Key(document, dictionary.version, personal)
            cache.get(key) ?: dictionary.withReadings { read, exact ->
                ReaderWords.annotate(document.text, boundaries, fallback = { form ->
                    ReaderPronunciation.preferred(form, exact(form), personal[form].orEmpty())
                }, supplied = supplied) { initial ->
                    val entries = byInitial[initial].orEmpty()
                    val bundled = read(initial)
                    if (entries.isEmpty()) bundled else bundled.toMutableMap().apply {
                        entries.forEach { (form, readings) -> put(form, this[form].orEmpty() + readings) }
                    }
                }
            }.also { if (!Thread.currentThread().isInterrupted) cache.put(key, it) }
        }.getOrElse {
            ReaderWords.annotate(document.text, boundaries, supplied = supplied) { byInitial[it].orEmpty() }
        }
    }
}
