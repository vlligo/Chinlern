package com.example.chinlern.dictionary

import java.text.Normalizer

/** Notation conversion and comparison shared by dictionary and reader. */
internal object Pinyin {
    fun readingKey(numbered: String): String = Normalizer.normalize(numbered, Normalizer.Form.NFC)
        .lowercase(java.util.Locale.ROOT).replace("u:", "ü").replace('v', 'ü')
        .filterNot { it.isWhitespace() || it in "'’-" }

    private val syllable = Regex("([a-züvA-ZÜV:]+)([1-5])")
    private val syllableSpace = Regex("(?<=[1-5]) +(?=[a-züvA-ZÜV:]+[1-5])")
    private val accents = "\u0304\u0301\u030c\u0300"

    fun marked(numbered: String): String = syllable.replace(syllableSpace.replace(numbered) {
        if (numbered[it.range.last + 1].lowercaseChar() in "aeo") "'" else ""
    }) { match ->
        val letters = match.groupValues[1].replace("u:", "ü").replace("U:", "Ü")
            .replace('v', 'ü').replace('V', 'Ü')
        val tone = match.groupValues[2].toInt()
        val lower = letters.lowercase()
        val vowel = sequenceOf(lower.indexOf('a'), lower.indexOf('e'), lower.indexOf("ou"))
            .firstOrNull { it >= 0 } ?: lower.indexOfLast { it in "iouü" }
        val index = if (vowel >= 0) vowel else lower.indexOfFirst { it in "mn" }
        if (tone == 5 || index < 0) letters else Normalizer.normalize(
            letters.substring(0, index + 1) + accents[tone - 1] + letters.substring(index + 1),
            Normalizer.Form.NFC
        )
    }
}
