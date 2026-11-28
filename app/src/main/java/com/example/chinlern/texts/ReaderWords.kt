package com.example.chinlern.texts

import com.example.chinlern.dictionary.Pinyin

/** Offsets refer to the original passage, including any supplementary characters. */
internal object ReaderWords {
    data class Span(val start: Int, val end: Int)
    data class Annotation(val span: Span, val ruby: String?)

    fun annotate(
        text: String,
        boundaries: IntArray,
        fallback: (String) -> String? = { null },
        supplied: Map<Span, String> = emptyMap(),
        readings: (String) -> Map<String, Set<String>>
    ): List<Annotation> {
        val lexicon = mutableMapOf<String, Set<String>>()
        val spans = segment(text, boundaries, supplied.keys.toList()) { initial ->
            readings(initial).also(lexicon::putAll).keys
        }
        val readingsByForm = mutableMapOf<String, Map<String, String>>()
        val automatic = mutableMapOf<String, String>()
        val marked = mutableMapOf<String, String>()
        return spans.map { span ->
            supplied[span]?.let { return@map Annotation(span, it) }
            val form = text.substring(span.start, span.end)
            val choices = readingsByForm.getOrPut(form) {
                buildMap { lexicon[form].orEmpty().forEach { numbered -> getOrPut(Pinyin.readingKey(numbered)) { numbered } } }
            }
            val reading = when (choices.size) {
                0 -> null
                1 -> choices.values.single()
                else -> {
                    val contextual = ReaderPronunciation.inContext(text, span, form)?.takeIf { Pinyin.readingKey(it) in choices }
                    contextual ?: if (ReaderPronunciation.mayBeName(text, span)) "?"
                    else automatic.getOrPut(form) {
                        fallback(form)?.takeIf { Pinyin.readingKey(it) in choices } ?: "?"
                    }
                }
            }
            Annotation(span, reading?.let { marked.getOrPut(it) { Pinyin.marked(it) } })
        }
    }

    fun segment(text: String, boundaries: IntArray, fixed: List<Span> = emptyList(), forms: (String) -> Set<String>): List<Span> {
        val byInitial = mutableMapOf<String, Pair<Set<String>, Int>>()
        return buildList {
            var index = 0
            var fixedIndex = 0
            while (index < boundaries.lastIndex && !Thread.currentThread().isInterrupted) {
                val start = boundaries[index]
                val supplied = fixed.getOrNull(fixedIndex)
                if (supplied?.start == start) {
                    add(supplied)
                    index = boundaries.binarySearch(supplied.end)
                    fixedIndex++
                    continue
                }
                val codePoint = text.codePointAt(start)
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) {
                    index++
                    continue
                }
                val initial = String(Character.toChars(codePoint))
                val (words, maxLength) = byInitial.getOrPut(initial) {
                    val matches = forms(initial)
                    matches to (matches.maxOfOrNull { it.length } ?: 0)
                }
                var next = index + 1
                var candidate = next + 1
                val limit = minOf(start + maxLength, supplied?.start ?: text.length)
                while (candidate < boundaries.size && boundaries[candidate] <= limit) {
                    if (text.substring(start, boundaries[candidate]) in words) next = candidate
                    candidate++
                }
                add(Span(start, boundaries[next]))
                index = next
            }
        }
    }
}
