package com.example.chinlern.dictionary

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.example.chinlern.DetailUi
import com.example.chinlern.R
import com.example.chinlern.StudyLayout
import com.example.chinlern.VocabularyLookup
import com.example.chinlern.Word
import com.example.chinlern.WordCardUi
import com.example.chinlern.WordDetailsSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class DictionaryScreen(
    private val context: Activity,
    private val bookmarks: DictionaryBookmarks,
    private val vocabulary: () -> VocabularyLookup,
    private val searchField: (String, LinearLayout) -> TextInputEditText,
    private val personalDetails: (Word) -> Dialog,
    private val play: (Word) -> Unit,
    private val addWord: (String, OfflineDictionary.Entry, String, (Dialog) -> Unit, () -> Unit) -> Unit,
    private val dismissed: (DictionaryScreen) -> Unit
) {
    private val dialog = Dialog(context, R.style.Theme_Chinlern)
    private val dictionaries = listOf(OfflineDictionary.Source.RU_WIKTIONARY, OfflineDictionary.Source.CEDICT)
        .associateWith { OfflineDictionary(context, it) }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val children = mutableSetOf<Dialog>()
    private val status = column()
    private val results = ListView(context).apply {
        divider = null
        dividerHeight = 0
        clipToPadding = false
        setPadding(dp(18), dp(4), dp(18), dp(20))
    }
    private data class SearchResult(val title: String, val open: () -> Unit)
    private var rows = emptyList<SearchResult>()
    private val resultAdapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val result = getItem(position)
            val button = convertView as? com.google.android.material.button.MaterialButton
                ?: DetailUi.button(context, "", {}).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            button.text = result.title
            button.setOnClickListener { result.open() }
            return button
        }
    }
    private lateinit var query: TextInputEditText
    private var snapshot = vocabulary()
    private var pending: Runnable? = null
    private var searchWork: Future<*>? = null
    private var searchCancellation: CancellationSignal? = null
    private var generation = 0
    private var closed = false
    var selectedSource: OfflineDictionary.Source? = null
        private set

    fun currentQuery(): String? = if (dialog.isShowing) query.text?.toString() else null

    fun show(initial: String, source: OfflineDictionary.Source? = null) {
        selectedSource = source
        val root = column().apply {
            isFocusableInTouchMode = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_paper))
        }
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(DetailUi.icon(context, R.drawable.ic_stroke_back, "Назад", action = dialog::dismiss), dp(48), dp(48))
        toolbar.addView(TextView(context).apply {
            text = "Поиск слов"
            textSize = 22f
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(DetailUi.icon(context, R.drawable.ic_bookmark, "Закладки") {
            hideKeyboard()
            showBookmarks()
        }, dp(48), dp(48))
        toolbar.addView(DetailUi.icon(context, R.drawable.ic_list_more, "Действия словаря") {
            hideKeyboard()
            showMenu()
        }, dp(48), dp(48))
        root.addView(toolbar)
        val input = column().apply { setPadding(dp(18), dp(6), dp(18), 0) }
        root.addView(input)
        query = searchField(initial, input)
        query.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }

        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        results.addHeaderView(status, null, false)
        results.adapter = resultAdapter
        root.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = StudyLayout.centered(root, 720, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_paper))
        }
        dialog.setContentView(frame)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
                val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                insets
            }
        }
        dialog.setOnDismissListener { dispose(); dismissed(this) }
        query.doAfterTextChanged { search() }
        root.requestFocus()
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        search()
    }

    private fun activeDictionaries() = dictionaries.values.filter { selectedSource == null || it.source == selectedSource }

    private fun search() {
        // Inline library results open an article without creating a search window.
        if (closed || !::query.isInitialized) return
        val token = ++generation
        pending?.let(handler::removeCallbacks)
        searchWork?.cancel(false)
        searchCancellation?.cancel()
        val entered = query.text.toString().trim()
        rows = emptyList()
        resultAdapter.notifyDataSetChanged()
        status.removeAllViews()
        if (entered.isEmpty()) return
        status.label("Поиск…", 15f)
        val sources = activeDictionaries()
        pending = Runnable {
            if (closed || token != generation) return@Runnable
            val library = snapshot
            val request = CancellationSignal().also { searchCancellation = it }
            searchWork = worker.submit {
                val matches = library.search(entered)
                if (request.isCanceled) return@submit
                val found = sources.associate { dictionary ->
                    dictionary.source to runCatching { dictionary.search(entered, request) }
                }
                if (request.isCanceled) return@submit
                val definitions = found.flatMap { (source, lookup) ->
                    lookup.getOrDefault(emptyList()).map { SourcedDefinition(source, it) }
                }
                val merged = dictionaryResults(matches.take(50), definitions, entered)
                val rendered = merged.map { result ->
                    val primary = result.forms.preferred(entered)
                    val reading = result.cards.firstOrNull()?.word?.dictionaryReading()
                        ?: result.definitions.firstOrNull { it.entry.pinyin.isNotBlank() }?.entry?.pinyin.orEmpty()
                    val key = dictionaryReadingKey(reading)
                    val summaries = buildList {
                        result.cards.firstOrNull { dictionaryReadingKey(it.word.dictionaryReading()) == key }
                            ?.let { add("Мои карточки · ${it.word.meanings.firstOrNull().orEmpty().take(80)}") }
                        val byReading = result.definitions.filter {
                            val definitionKey = dictionaryReadingKey(it.entry.pinyin)
                            definitionKey == key || definitionKey.isEmpty()
                        }
                        byReading.distinctBy { it.source }.forEach { definition ->
                            val language = if (definition.source == OfflineDictionary.Source.CEDICT) "English" else "Русский"
                            add("$language · ${definition.entry.meanings.firstOrNull().orEmpty().take(80)}")
                        }
                    }
                    SearchResult((listOf("$primary — ${readingLabel(reading)}") + summaries).joinToString("\n")) {
                        hideKeyboard()
                        showEntry(result, primary, reading, sources)
                    }
                }
                handler.post {
                    if (closed || token != generation) return@post
                    status.removeAllViews()
                    rows = rendered
                    resultAdapter.notifyDataSetChanged()
                    results.setSelection(0)
                    if (merged.isEmpty()) status.label("Совпадений нет.", 15f)
                    if (matches.size > 50 || found.values.any { it.getOrNull()?.size == 50 }) {
                        status.label("Уточните запрос, чтобы увидеть больше совпадений.", 14f)
                    }
                    val failed = found.filterValues { it.isFailure }.keys
                    failed.forEach { status.label("${it.label} · словарь недоступен.", 14f) }
                    if (failed.isNotEmpty()) status.action("Повторить поиск", ::search)
                }
            }
        }.also { handler.postDelayed(it, 200) }
    }

    fun open(result: DictionaryResult, word: Word) {
        showEntry(result, word.hanzi, word.dictionaryReading(), dictionaries.values.toList())
    }

    private fun showEntry(
        seed: DictionaryResult, primary: String, initialReading: String, sources: List<OfflineDictionary>,
        saved: DictionaryBookmarks.Reference? = null
    ) {
        var forms = seed.forms
        val content = column().apply { setPadding(dp(20), dp(10), dp(20), dp(8)) }
        val sheet = WordDetailsSheet.present(context, content).also(::track)
        var definitions = seed.definitions
        var cards = seed.cards
        var reading = initialReading
        var loading = true
        var unavailable = emptyList<OfflineDictionary.Source>()

        fun render() {
            content.removeAllViews()
            val alternatives = cards.flatMap { it.word.writtenForms }.filterNot { it in forms.values }.distinct()
            val title = entryText((forms.values + alternatives).joinToString(" / "), 27f, selectable = true)
            if (reading.isNotBlank()) {
                val pronunciation = DetailUi.icon(context, R.drawable.ic_speaker, "Слушать") {
                    play(Word("", primary, Pinyin.marked(reading), reading, emptyList(), emptyList(), ""))
                }
                content.addView(WordCardUi.titleRow(title, pronunciation))
            } else content.addView(title)
            val readings = (cards.map { it.word.dictionaryReading() } + definitions.map { it.entry.pinyin })
                .distinctBy(::dictionaryReadingKey)
            val key = dictionaryReadingKey(reading)
            val current = definitions.filter { dictionaryReadingKey(it.entry.pinyin) == key }
            val unassigned = if (key.isEmpty()) emptyList() else definitions.filter { it.entry.pinyin.isBlank() }
            val currentCards = cards.filter { dictionaryReadingKey(it.word.dictionaryReading()) == key }
            val reference = saved?.takeIf { dictionaryReadingKey(it.pinyin) == key }
                ?: current.firstOrNull()?.let {
                    DictionaryBookmarks.Reference(forms.simplified, forms.traditional, it.entry.pinyin, it.source)
                }
            val existing = bookmarks.all().firstOrNull {
                val sameForms = it.forms == forms.values || it.forms == seed.forms.values
                sameForms && dictionaryReadingKey(it.pinyin) == key
            }
            content.label(readingLabel(reading), 21f, selectable = true)
            if (!loading && (readings.size > 1 || (readings.isNotEmpty() && readings.none { dictionaryReadingKey(it) == key }))) {
                content.action("Выбрать чтение") {
                    track(MaterialAlertDialogBuilder(context).setTitle("Выберите чтение")
                        .setSingleChoiceItems(readings.map(::readingLabel).toTypedArray(),
                            readings.indexOfFirst { dictionaryReadingKey(it) == key }) { picker, index ->
                            reading = readings[index]
                            picker.dismiss()
                            render()
                        }.setNegativeButton("Отмена", null).show())
                }
            }
            if (currentCards.isNotEmpty()) {
                content.label("Мои карточки", 14f)
                currentCards.forEach { card ->
                    content.label(card.word.meanings.joinToString("\n"), 17f, selectable = true)
                    content.action("Карточка · ${card.packName}") { track(personalDetails(card.word)) }
                }
            }
            content.dictionaryDefinitions(current)
            if (unassigned.isNotEmpty()) {
                content.label(readingLabel(""), 19f)
                content.dictionaryDefinitions(unassigned)
            }
            if (loading) content.label("Поиск значений…", 15f)
            else if (current.isEmpty() && currentCards.isEmpty() && unassigned.isEmpty()) {
                content.label("Для этого чтения нет совпадений.", 15f)
            }
            unavailable.forEach { content.label("${it.label} · словарь недоступен.", 14f) }
            val senses = (current + unassigned).flatMap { definition ->
                definition.entry.meanings.map { meaning -> meaning to definition }
            }.distinctBy { (meaning, definition) -> Triple(meaning, definition.source, dictionaryReadingKey(definition.entry.pinyin)) }
            if (senses.isNotEmpty() && !loading) content.action("Добавить в набор") {
                fun edit(index: Int) {
                    val (meaning, definition) = senses[index]
                    addWord(primary, definition.entry, meaning, ::track) {
                        snapshot = vocabulary()
                        cards = (cards + forms.values.flatMap(snapshot::exact)).distinctBy { it.reference }
                        render()
                        search()
                    }
                }
                if (senses.size == 1) edit(0)
                else track(MaterialAlertDialogBuilder(context).setTitle("Выберите значение")
                    .setItems(senses.map { (meaning, definition) ->
                        if (definition in unassigned) "$meaning · ${readingLabel("")}" else meaning
                    }.toTypedArray()) { _, index -> edit(index) }
                    .setNegativeButton("Отмена", null).show())
            }
            (existing ?: reference)?.let { bookmark ->
                content.action(if (existing != null) "Убрать закладку" else "В закладки") {
                    try { bookmarks.toggle(bookmark); render() }
                    catch (error: Exception) { toast(error.message ?: "Не удалось изменить закладку.") }
                }
            }
            if (!dialog.isShowing && bookmarks.all().isNotEmpty()) {
                content.addView(DetailUi.icon(context, R.drawable.ic_bookmark, "Закладки", action = ::showBookmarks), dp(48), dp(48))
            }
            content.action("Копировать слово") {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Словарь", primary))
            }
        }
        render()
        val library = snapshot
        worker.execute {
            val lookupForms = if (seed.definitions.isEmpty()) {
                (seed.forms.values + seed.cards.flatMap { it.word.writtenForms }).distinct()
            } else seed.forms.values
            val found = sources.associate { dictionary -> dictionary.source to runCatching {
                dictionary.exactForms(lookupForms).map { SourcedDefinition(dictionary.source, it) }
            } }
            val all = (seed.definitions + found.values.flatMap { it.getOrDefault(emptyList()) }).distinct()
            val grouped = groupDictionaryDefinitions(all)
            val resolved = if (seed.forms in grouped || seed.forms.values.size > 1) seed.forms
            else grouped.keys.singleOrNull { primary in it.values } ?: seed.forms
            val related = grouped[resolved].orEmpty()
            val linkedCards = (seed.cards + resolved.values.flatMap(library::exact)).distinctBy { it.reference }
            handler.post {
                if (closed || !sheet.isShowing) return@post
                forms = resolved
                definitions = related
                cards = linkedCards
                if (reading.isBlank() && saved == null && cards.none { it.word.dictionaryReading().isBlank() }) {
                    reading = related.firstOrNull { it.entry.pinyin.isNotBlank() }?.entry?.pinyin.orEmpty()
                }
                loading = false
                unavailable = found.filterValues { it.isFailure }.keys.toList()
                render()
            }
        }
    }

    private fun showBookmarks() {
        val saved = bookmarks.all()
        val picker = MaterialAlertDialogBuilder(context).setTitle("Закладки").setNegativeButton("Закрыть", null)
        if (saved.isEmpty()) picker.setMessage("Сохраните нужное чтение из словарной статьи.")
        else picker.setItems(saved.map {
            "${it.forms.joinToString(" / ")} · ${readingLabel(it.pinyin)}\n${it.source.label}"
        }.toTypedArray()) { _, index ->
            val ref = saved[index]
            showEntry(DictionaryResult(DictionaryForms(ref.simplified, ref.traditional), emptyList(), emptyList()),
                ref.simplified, ref.pinyin, dictionaries.values.toList(), ref)
        }
        track(picker.show())
    }

    private fun showMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add("Источники словаря" to ::chooseSources)
        dictionaries.values.forEach { dictionary ->
            val name = if (dictionary.source == OfflineDictionary.Source.CEDICT) "О словаре CC-CEDICT" else "О Викисловаре"
            actions.add(name to {
                track(MaterialAlertDialogBuilder(context).setTitle("${dictionary.source.label} · CC BY-SA 4.0")
                    .setMessage(dictionary.notice() + "\n\n" + dictionary.license())
                    .setPositiveButton("Закрыть", null).show())
            })
        }
        track(MaterialAlertDialogBuilder(context).setItems(actions.map { it.first }.toTypedArray()) { _, index ->
            actions[index].second()
        }.show())
    }

    private fun chooseSources() {
        val sources = listOf(null) + dictionaries.keys
        val body = column().apply { setPadding(dp(18), 0, dp(18), dp(8)) }
        val picker = MaterialAlertDialogBuilder(context).setTitle("Источники словаря")
            .setView(NestedScrollView(context).apply { addView(body) })
            .setNegativeButton("Отмена", null).create()
        sources.forEach { source ->
            body.addView(android.widget.RadioButton(context).apply {
                text = source?.label ?: "Все словари"
                textSize = 16f
                minHeight = dp(52)
                isChecked = selectedSource == source
                setOnClickListener {
                    selectedSource = source
                    picker.dismiss()
                    search()
                }
            }, -1, -2)
        }
        picker.show()
        track(picker)
    }

    private fun track(child: Dialog) {
        children.add(child)
        child.setOnDismissListener {
            children.remove(child)
            handler.post {
                if (children.isEmpty() && !dialog.isShowing && !closed) {
                    dispose()
                    dismissed(this)
                }
            }
        }
    }

    fun close() {
        dispose()
        dialog.dismiss()
    }

    private fun dispose() {
        if (closed) return
        closed = true
        pending?.let(handler::removeCallbacks)
        searchCancellation?.cancel()
        children.toList().asReversed().forEach(Dialog::dismiss)
        worker.shutdownNow()
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(query.windowToken, 0)
    }

    private fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.action(title: String, click: () -> Unit) { addView(DetailUi.button(context, title, click)) }
    private fun LinearLayout.label(value: String, size: Float, selectable: Boolean = false) {
        addView(entryText(value, size, selectable))
    }

    private fun entryText(value: String, size: Float, selectable: Boolean) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.chinlern_jade))
        setTextIsSelectable(selectable)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
