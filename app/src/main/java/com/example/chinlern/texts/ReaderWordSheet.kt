package com.example.chinlern.texts

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.example.chinlern.Answers
import com.example.chinlern.R
import com.example.chinlern.VocabularyLookup
import com.example.chinlern.Word
import com.example.chinlern.WordDraft
import com.example.chinlern.WordCardUi
import com.example.chinlern.WordCardUi.append
import com.example.chinlern.dictionary.OfflineDictionary
import com.example.chinlern.dictionary.Pinyin
import com.example.chinlern.dictionary.SourcedDefinition
import com.example.chinlern.dictionary.dictionaryDefinitions
import com.example.chinlern.dictionary.dictionaryReadingKey
import com.example.chinlern.dictionary.dictionaryReading
import com.example.chinlern.dictionary.dictionaryResults
import com.example.chinlern.dictionary.readingLabel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

internal class ReaderWordSheet(
    private val context: Context,
    private val dictionaries: List<OfflineDictionary>,
    private val vocabulary: () -> VocabularyLookup,
    private val play: (Word) -> Unit,
    private val strokeOrder: (Word) -> Unit,
    private val external: (String, Boolean) -> Unit,
    private val saveContext: (TextLibrary.Selection, VocabularyLookup.Entry) -> Unit,
    private val addWord: (TextLibrary.Selection, WordDraft, () -> Unit) -> Unit,
    private val track: (Dialog) -> Unit
) {
    data class OccurrenceReading(val current: () -> String?, val choose: (String?) -> Unit)

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var request: java.util.concurrent.Future<*>? = null
    private var generation = 0
    private var sheet: BottomSheetDialog? = null
    var selection: TextLibrary.Selection? = null
        private set

    fun open(
        selected: TextLibrary.Selection, occurrence: OccurrenceReading?, note: TextLibrary.WordNote?,
        adjust: (TextLibrary.Selection) -> Unit, dismissed: () -> Unit
    ) {
        selection = selected
        val dialog = sheet ?: BottomSheetDialog(context).also { created ->
            sheet = created
            created.setOnDismissListener {
                if (sheet === created) sheet = null
                selection = null
                generation++
                request?.cancel(false)
                dismissed()
            }
        }

        val quote = selected.quote
        val authored = column()
        val readingActions = column()
        val results = column()
        val content = column().apply {
            setPadding(dp(16), dp(10), dp(16), dp(20))
            addView(authored)
            addView(readingActions)
            addView(results)
        }
        val scroller = NestedScrollView(context).apply {
            clipToPadding = false
            addView(content)
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(12), dp(8))
        }
        header.addView(ReadingUi.title(context, quote).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ReadingUi.icon(context, R.drawable.ic_close, "Закрыть", dialog::dismiss), dp(48), dp(48))
        val actions = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val page = column().apply {
            background = ReadingUi.sheetSurface(context)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(WordCardUi.line); cornerRadius = dp(2).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(30), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
                bottomMargin = dp(8)
            })
            addView(header)
            addView(ReadingUi.rule(context), LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(16); marginEnd = dp(16)
            })
            addView(actions)
            addView(View(context).apply { setBackgroundColor(WordCardUi.line) }, -1, dp(1))
            addView(scroller, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.behavior.maxHeight = context.resources.displayMetrics.heightPixels * 4 / 5
        dialog.setContentView(page, android.view.ViewGroup.LayoutParams(-1, -2))
        if (!dialog.isShowing) dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val token = ++generation
        request?.cancel(false)
        val snapshot = vocabulary()
        fun matchesNote(numbered: String) = note != null && Answers.pinyinMatches(note.pinyin, numbered)
        val personalMatches = snapshot.exact(quote).sortedByDescending { matchesNote(it.word.numbered) }
        val personalSuggestions = personalMatches.flatMap { entry ->
            entry.word.meanings.map { WordDraft(displayPinyin(entry.word), entry.word.numbered, it) }
        }
        var supplied = note?.let {
            val marked = AnnotatedText.display(it.pinyin)
            WordDraft(marked, AnnotatedText.numbered(it.pinyin).ifBlank {
                personalMatches.firstOrNull { entry -> matchesNote(entry.word.numbered) }?.word?.numbered.orEmpty()
            }, it.gloss)
        }
        fun addWith(suggestions: List<WordDraft>) {
            val fromText = supplied
            chooseWordDraft(listOfNotNull(fromText?.takeIf { it.meaning.isNotBlank() }) + suggestions, selected,
                fromText?.copy(meaning = "") ?: WordDraft())
        }
        fun suppliedVoice() = supplied?.takeIf { it.numbered.isNotEmpty() }?.let {
            Word("", quote, it.pinyin, it.numbered, emptyList(), emptyList(), "")
        }

        var notePronunciation: View? = null
        val noteCard = note?.let {
            val pronunciation = ReadingUi.icon(context, R.drawable.ic_speaker, "Слушать") { suppliedVoice()?.let(play) }
            pronunciation.visibility = View.GONE
            notePronunciation = pronunciation
            WordCardUi.surface(context).also { card ->
                authored.append(card)
                card.label("Из текста", 14f, color = WordCardUi.muted)
                card.append(WordCardUi.titleRow(readingText(AnnotatedText.display(it.pinyin)), pronunciation))
                card.label(it.gloss, 17f)
            }
        }

        results.label("Поиск в словарях…", 15f)
        actions.addView(ReadingUi.button(context, "Изменить границы") { adjust(selected) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        val add = ReadingUi.button(context, "Добавить в набор", primary = true) { addWith(personalSuggestions) }.apply {
            isEnabled = false
        }
        actions.addView(add, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        if (selected.sentence != quote) content.label(selected.sentence, 15f, maxLines = 4, color = WordCardUi.muted)
        content.action("Открыть во внешнем словаре") {
            track(MaterialAlertDialogBuilder(context).setTitle("Открыть в словаре")
                .setItems(arrayOf("Pleco", "Hanping")) { _, which -> external(quote, which == 0) }.show())
        }

        request = worker.submit {
            val resultsBySource = dictionaries.associate { dictionary -> dictionary.source to runCatching {
                dictionary.exact(quote).sortedByDescending { matchesNote(it.pinyin) }
            } }
            handler.post {
                if (sheet !== dialog || !dialog.isShowing || token != generation) return@post
                results.removeAllViews()
                val entries = resultsBySource.values.flatMap { it.getOrDefault(emptyList()) }
                supplied = supplied?.let { prefill ->
                    prefill.copy(numbered = prefill.numbered.ifBlank {
                        entries.firstOrNull { matchesNote(it.pinyin) }?.pinyin.orEmpty()
                    })
                }
                val definitions = resultsBySource.flatMap { (source, result) ->
                    result.getOrDefault(emptyList()).map { SourcedDefinition(source, it) }
                }
                val groups = dictionaryResults(personalMatches, definitions, quote)
                groups.forEach { group ->
                    val readings = (group.cards.map { it.word.dictionaryReading() } + group.definitions.map { it.entry.pinyin })
                        .distinctBy(::dictionaryReadingKey)
                        .sortedWith(compareByDescending<String>(::matchesNote).thenBy(String::isBlank))
                    readings.forEach { reading ->
                        val key = dictionaryReadingKey(reading)
                        val saved = group.cards.filter { dictionaryReadingKey(it.word.dictionaryReading()) == key }
                        val senses = group.definitions.filter { dictionaryReadingKey(it.entry.pinyin) == key }
                        val forms = (listOf(quote) + group.forms.values + saved.flatMap { it.word.writtenForms })
                            .distinct()
                        val voice = saved.firstOrNull()?.word ?: Word("", quote, Pinyin.marked(reading), reading,
                            emptyList(), emptyList(), alternatives = forms.drop(1))
                        val card = WordCardUi.surface(context)
                        if (forms.size > 1) card.append(ReadingUi.title(context, forms.joinToString(" / ")))
                        card.showReading(voice, readingLabel(reading))
                        saved.forEach { entry ->
                            card.label(entry.packName, 14f, color = WordCardUi.muted)
                            card.label(entry.word.meanings.joinToString("\n"), 17f)
                            card.label(entry.word.hint, 14f, color = WordCardUi.muted)
                        }
                        card.dictionaryDefinitions(senses) { title, open -> ReadingUi.button(context, title, action = open) }
                        card.addActions(voice, forms, selected, saved)
                        results.append(card, if (results.childCount == 0 && noteCard == null) 0 else 10)
                    }
                }
                if (groups.isEmpty()) {
                    val card = WordCardUi.surface(context)
                    if (resultsBySource.values.any { it.isSuccess }) card.label("Совпадений нет.", 15f)
                    val word = suppliedVoice() ?: Word("", quote, "", "", emptyList(), emptyList())
                    card.addActions(word, listOf(quote), selected, emptyList())
                    results.append(card, if (noteCard == null) 0 else 10)
                }
                resultsBySource.filterValues { it.isFailure }.keys.forEach { source ->
                    results.label("${source.label} · словарь недоступен.", 15f)
                }
                val suggestions = entries.flatMap { entry ->
                    entry.meanings.map {
                        WordDraft(Pinyin.marked(entry.pinyin), entry.pinyin, it, hint = OfflineDictionary.wiktionaryCredit(entry.article))
                    }
                }
                val pronunciations = (listOfNotNull(suppliedVoice()) + personalMatches.map { it.word } +
                    entries.filter { it.pinyin.isNotBlank() }.map { entry ->
                    Word("", quote, Pinyin.marked(entry.pinyin), entry.pinyin, emptyList(), emptyList(), "")
                }).distinctBy { Pinyin.readingKey(it.numbered) }
                suppliedVoice()?.let { word ->
                    val inResults = entries.any { dictionaryReadingKey(it.pinyin) == dictionaryReadingKey(word.numbered) } ||
                        personalMatches.any { dictionaryReadingKey(it.word.dictionaryReading()) == dictionaryReadingKey(word.numbered) }
                    if (groups.isEmpty() || !inResults) notePronunciation?.visibility = View.VISIBLE
                }
                if (occurrence != null) {
                    val readings = pronunciations.map(::displayPinyin).distinct()
                    if (readings.size > 1 || occurrence.current() != null) {
                        readingControl(readingActions, occurrence, readings, if (note == null) "Автоматически" else "Как в тексте")
                    }
                }
                add.setOnClickListener { addWith(personalSuggestions + suggestions) }
                add.isEnabled = true
            }
        }
    }

    private fun readingText(reading: String) = ReadingUi.text(context, reading, 20f).apply {
        setTextIsSelectable(true)
        setPadding(0, dp(3), 0, dp(4))
    }

    private fun LinearLayout.showReading(word: Word, reading: String) {
        if (word.dictionaryReading().isBlank()) {
            label(reading, 20f)
        } else {
            val pronunciation = ReadingUi.icon(context, R.drawable.ic_speaker, "Слушать") { play(word) }
            append(WordCardUi.titleRow(readingText(reading), pronunciation))
        }
    }

    private fun LinearLayout.addActions(
        word: Word, forms: List<String>, selection: TextLibrary.Selection, cards: List<VocabularyLookup.Entry>
    ) {
        append(ReadingUi.button(context, "Порядок черт") {
            strokeOrder(word.copy(hanzi = forms.first(), alternatives = forms.drop(1)))
        }.apply { contentDescription = "Порядок черт · ${forms.first()}" }, 8)
        if (cards.isNotEmpty()) append(ReadingUi.button(context, "Сохранить контекст") {
            if (cards.size == 1) saveContext(selection, cards.single())
            else track(MaterialAlertDialogBuilder(context).setTitle("Сохранить контекст")
                .setItems(cards.map { "${it.packName} · ${it.word.meanings.joinToString("; ")}" }.toTypedArray()) { _, which ->
                    saveContext(selection, cards[which])
                }.show())
        }, 8)
    }

    private fun readingControl(actions: LinearLayout, occurrence: OccurrenceReading, readings: List<String>, defaultLabel: String) {
        fun title() = occurrence.current()?.let { "Чтение в этом месте · $it" } ?: "Чтение в этом месте"
        val button = ReadingUi.button(context, title()) {}
        button.setOnClickListener {
            val choices = listOf(null) + (readings + listOfNotNull(occurrence.current())).distinct()
            track(MaterialAlertDialogBuilder(context).setTitle("Чтение в этом месте")
                .setSingleChoiceItems(choices.map { it ?: defaultLabel }.toTypedArray(),
                    choices.indexOf(occurrence.current())) { picker, index ->
                    occurrence.choose(choices[index])
                    button.text = title()
                    picker.dismiss()
                }.setNegativeButton("Отмена", null).show())
        }
        actions.append(button, 8)
    }

    private fun chooseWordDraft(suggestions: List<WordDraft>, selection: TextLibrary.Selection, custom: WordDraft) {
        val choices = suggestions.distinctBy { Pinyin.readingKey(it.numbered) to it.meaning }
        if (choices.isEmpty()) {
            addWord(selection, custom) { sheet?.dismiss() }
            return
        }
        track(MaterialAlertDialogBuilder(context).setTitle("Чтение и значение")
            .setItems((choices.map { if (it.pinyin.isBlank()) it.meaning else "${it.pinyin} · ${it.meaning}" } +
                "Ввести свои").toTypedArray()) { _, index ->
                addWord(selection, choices.getOrElse(index) { custom }) { sheet?.dismiss() }
            }.setNegativeButton("Отмена", null).show())
    }

    fun close() {
        request?.cancel(true)
        worker.shutdownNow()
        sheet?.dismiss()
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun displayPinyin(word: Word) = Pinyin.marked(word.pinyin.ifBlank { word.numbered })
    private fun LinearLayout.label(value: String, size: Float = 16f, maxLines: Int = Int.MAX_VALUE,
        color: Int = context.getColor(R.color.chinlern_ink)
    ) {
        if (value.isBlank()) return
        addView(ReadingUi.text(context, value, size).apply {
            setTextColor(color)
            setTextIsSelectable(true)
            this.maxLines = maxLines
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(4))
        })
    }
    private fun LinearLayout.action(name: String, run: () -> Unit) {
        append(ReadingUi.button(context, name, action = run), 8)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
