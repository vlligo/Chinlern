package com.example.chinlern.texts

import android.icu.text.BreakIterator
import java.util.Locale

internal object ReaderSentences {
    fun spans(text: String): List<ReaderWords.Span> {
        val sentences = BreakIterator.getSentenceInstance(Locale.SIMPLIFIED_CHINESE).apply { setText(text) }
        val characters = characters(text)
        return buildList {
            var start = sentences.first()
            while (start < text.length) {
                val boundary = sentences.following(start).let { if (it == BreakIterator.DONE) text.length else it }
                val end = if (characters.isBoundary(boundary)) boundary else characters.following(boundary)
                var left = start
                var right = end
                while (left < right && text[left].isWhitespace()) left = characters.following(left)
                while (left < right) {
                    val last = characters.preceding(right)
                    if (!text[last].isWhitespace()) break
                    right = last
                }
                if (hasWords(text, left, right)) add(ReaderWords.Span(left, right))
                start = end
            }
        }
    }

    fun forRecall(text: String): List<ReaderWords.Span> {
        val characters = characters(text)
        return spans(text).flatMap { sentence ->
            if (sentence.end - sentence.start <= TextRecall.MAX_LENGTH) {
                listOf(sentence)
            } else {
                buildList {
                    var start = sentence.start
                    while (start < sentence.end) {
                        val limit = minOf(sentence.end, start + TextRecall.MAX_LENGTH)
                        val end = if (characters.isBoundary(limit)) limit else characters.preceding(limit)
                        if (end <= start) {
                            start = characters.following(start)
                            continue
                        }
                        if (hasWords(text, start, end)) add(ReaderWords.Span(start, end))
                        start = end
                    }
                }
            }
        }
    }

    private fun characters(text: String) =
        BreakIterator.getCharacterInstance().apply { setText(text) }

    private fun hasWords(text: String, start: Int, end: Int) =
        text.substring(start, end).codePoints().anyMatch(Character::isLetterOrDigit)
}
