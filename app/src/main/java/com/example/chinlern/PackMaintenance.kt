package com.example.chinlern

/** Additive updates retain local IDs, selections and progress, including words absent from the file. */
object PackMaintenance {
    data class Update(val words: List<Word>, val added: List<Word>, val changed: List<Pair<Word, Word>>, val retained: List<Word>)

    fun preview(existing: List<Word>, incoming: List<Word>): Update {
        require(incoming.size in 1..2000) { "Добавьте от 1 до 2000 слов." }
        require(incoming.map { it.id }.distinct().size == incoming.size && incoming.map { it.hanzi }.distinct().size == incoming.size) { "В наборе есть повторяющиеся слова или id." }
        val matched = mutableSetOf<String>()
        val added = mutableListOf<Word>()
        val changed = mutableListOf<Pair<Word, Word>>()
        val replacements = mutableMapOf<String, Word>()
        incoming.forEach { word ->
            val byId = existing.find { it.id == word.id }
            val byHanzi = existing.find { it.hanzi == word.hanzi }
            require(byId == null || byHanzi == null || byId.id == byHanzi.id) { "Конфликт id для ${word.hanzi}. Исправьте файл." }
            require(byId == null || byId.hanzi == word.hanzi) { "id ${word.id} уже принадлежит ${byId?.hanzi}. Для нового слова нужен новый id." }
            val old = byId ?: byHanzi
            if (old == null) added.add(word) else {
                require(matched.add(old.id)) { "Несколько карточек соответствуют ${old.hanzi}." }
                val updated = word.copy(id = old.id)
                replacements[old.id] = updated
                if (old != updated) changed.add(old to updated)
            }
        }
        val result = existing.map { replacements[it.id] ?: it } + added
        require(result.size <= 2000) { "После обновления будет больше 2000 слов." }
        return Update(result, added, changed, existing.filter { it.id !in matched })
    }
}
