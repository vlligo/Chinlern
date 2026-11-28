package com.example.chinlern.dictionary

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.example.chinlern.DetailUi
import com.example.chinlern.VocabularyLookup
import com.example.chinlern.Word

internal data class DictionaryForms(val simplified: String, val traditional: String) {
    val values get() = listOf(simplified, traditional).distinct()
    val label get() = values.joinToString(" / ")
    fun preferred(query: String) = if (query == traditional) traditional else simplified
}

internal data class SourcedDefinition(val source: OfflineDictionary.Source, val entry: OfflineDictionary.Entry) {
    val forms get() = DictionaryForms(entry.simplified, entry.traditional)
}

internal fun Word.dictionaryReading() = numbered.ifBlank { pinyin }

internal fun dictionaryReadingKey(reading: String) = Pinyin.readingKey(Pinyin.marked(reading))

internal fun readingLabel(reading: String) = Pinyin.marked(reading).ifBlank { "Пиньинь не указан" }

/** Keep distinct spelling pairs. Resolve a single form only when its destination is unique. */
internal fun groupDictionaryDefinitions(entries: List<SourcedDefinition>): Map<DictionaryForms, List<SourcedDefinition>> {
    val pairsByForm = entries.map { it.forms }.distinct().filter { it.simplified != it.traditional }
        .flatMap { pair -> pair.values.map { it to pair } }.groupBy({ it.first }, { it.second })
    return entries.groupBy { definition ->
        val forms = definition.forms
        if (forms.simplified == forms.traditional) pairsByForm[forms.simplified]?.singleOrNull() ?: forms
        else forms
    }
}

internal fun LinearLayout.dictionaryDefinitions(
    definitions: List<SourcedDefinition>,
    linkButton: (String, () -> Unit) -> View = { title, open -> DetailUi.button(context, title, open) }
) {
    val density = resources.displayMetrics.density
    fun label(value: String, size: Float, heading: Boolean) {
        addView(TextView(context).apply {
            text = value
            textSize = size
            setTextIsSelectable(!heading)
            ViewCompat.setAccessibilityHeading(this, heading)
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        })
    }
    definitions.groupBy { it.source }.entries.sortedByDescending { it.key.ordinal }.forEach { (source, matches) ->
        label("${source.label} · CC BY-SA 4.0", 14f, true)
        matches.groupBy { it.entry.article }.forEach { (article, senses) ->
            label(senses.flatMap { it.entry.meanings }.distinct().joinToString("\n"), 17f, false)
            if (article != null) addView(linkButton("Статья в Викисловаре · $article") {
                if (!OfflineDictionary.openWiktionaryArticle(context, article)) {
                    Toast.makeText(context, "Браузер недоступен.", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}

internal data class DictionaryResult(
    val forms: DictionaryForms,
    val cards: List<VocabularyLookup.Entry>,
    val definitions: List<SourcedDefinition>
)

internal fun dictionaryResults(
    cards: List<VocabularyLookup.Entry>, definitions: List<SourcedDefinition>, query: String
): List<DictionaryResult> {
    val grouped = groupDictionaryDefinitions(definitions)
    val groupsByForm = grouped.keys.flatMap { forms -> forms.values.map { it to forms } }
        .groupBy({ it.first }, { it.second })
    val cardsByGroup = cards.groupBy { card ->
        val candidates = card.word.writtenForms.flatMap { groupsByForm[it].orEmpty() }.distinct()
        candidates.singleOrNull()
            ?: candidates.filter { forms -> grouped.getValue(forms).any {
                dictionaryReadingKey(it.entry.pinyin) == dictionaryReadingKey(card.word.dictionaryReading())
            } }.singleOrNull()
            ?: DictionaryForms(card.word.hanzi, card.word.hanzi)
    }
    return (cardsByGroup.keys + grouped.keys).map { forms ->
        DictionaryResult(forms, cardsByGroup[forms].orEmpty(), grouped[forms].orEmpty())
    }.sortedWith(compareByDescending<DictionaryResult> { query in it.forms.values }
        .thenByDescending { it.cards.isNotEmpty() })
}
