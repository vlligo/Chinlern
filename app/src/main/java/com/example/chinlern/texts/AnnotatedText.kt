package com.example.chinlern.texts

import com.example.chinlern.dictionary.Pinyin
import java.text.Normalizer

/** Optional editor format. The reader uses only the decoded passage and its original-text offsets. */
internal object AnnotatedText {
    data class Passage(val text: String, val notes: List<TextLibrary.WordNote>)

    fun parse(input: String): Passage {
        require(input.length <= 500_000) { "Разметка слишком длинная." }
        val passage = StringBuilder()
        val notes = mutableListOf<TextLibrary.WordNote>()
        val field = StringBuilder()
        var open = false
        var index = 0
        while (index < input.length) {
            val character = input[index++]
            val target = if (open) field else passage
            if (character == '\\' && index < input.length && input[index] in "\\{}") {
                target.append(input[index++])
                continue
            }
            when (character) {
                '{' -> {
                    require(!open) { "Закройте предыдущую пометку знаком }." }
                    open = true
                }
                '}' -> {
                    require(open) { "Перед знаком } нужна открывающая скобка {." }
                    val parts = field.split('|', limit = 3)
                    val word = parts[0]
                    val pinyin = parts.getOrNull(1)?.trim().orEmpty()
                    val gloss = parts.getOrNull(2)?.trim().orEmpty()
                    require(word.length in 1..30 && word.none(Char::isWhitespace) &&
                        word.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) {
                        "Укажите китайское слово без пробелов. Пример {水|shuǐ}."
                    }
                    require(pinyin.length in 1..120 && validPinyin(pinyin)) {
                        "Проверьте пиньинь для «$word». Пример shuǐ или shui3."
                    }
                    require(gloss.length <= 200 && gloss.none { it == '\n' || it == '\r' }) {
                        "Укажите один короткий перевод для «$word», до 200 знаков."
                    }
                    val start = passage.length
                    passage.append(word)
                    notes.add(TextLibrary.WordNote(start, passage.length, pinyin, gloss))
                    field.clear()
                    open = false
                }
                else -> target.append(character)
            }
        }
        require(!open) { "Закройте последнюю пометку знаком }." }
        return Passage(passage.toString(), notes)
    }

    fun format(document: TextLibrary.Document): String = buildString {
        var offset = 0
        document.wordNotes.forEach { note ->
            append(escape(document.text.substring(offset, note.start)))
            append('{').append(escape(document.text.substring(note.start, note.end)))
            append('|').append(escape(note.pinyin))
            if (note.gloss.isNotEmpty()) append('|').append(escape(note.gloss))
            append('}')
            offset = note.end
        }
        append(escape(document.text.substring(offset)))
    }

    private fun escape(text: String) = buildString {
        text.forEach { if (it in "\\{}") append('\\'); append(it) }
    }

    private val syllable = Regex("[a-züv]+[1-5]", RegexOption.IGNORE_CASE)
    private val separators = Regex("[ \\t'’·-]+")
    private val marked = Regex("[a-zA-ZüÜvV\u0300\u0301\u0304\u030c\u0308 '’·-]+")

    fun numbered(pinyin: String): String {
        val compact = Normalizer.normalize(pinyin, Normalizer.Form.NFC)
            .replace("u:", "ü").replace("U:", "Ü").replace(separators, "")
        val syllables = syllable.findAll(compact).map { it.value }.toList()
        return if (syllables.joinToString("") == compact) syllables.joinToString(" ") else ""
    }

    fun display(pinyin: String) = numbered(pinyin).takeIf { it.isNotEmpty() }
        ?.let(Pinyin::marked) ?: pinyin

    private fun validPinyin(value: String) = if (value.any(Char::isDigit)) numbered(value).isNotEmpty()
        else value.any(Char::isLetter) && marked.matches(Normalizer.normalize(value, Normalizer.Form.NFD))
}
