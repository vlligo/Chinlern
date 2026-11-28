package com.example.chinlern.texts

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.example.chinlern.ExternalDictionary
import com.example.chinlern.R
import com.example.chinlern.WordCardUi
import com.example.chinlern.WordCardUi.append
import com.example.chinlern.WordCardUi.dp
import com.example.chinlern.SharedWords
import com.example.chinlern.StudyStore
import com.example.chinlern.VocabularyLookup
import com.example.chinlern.Word
import com.example.chinlern.WordDraft
import com.example.chinlern.data.VocabularyReference
import com.example.chinlern.dictionary.OfflineDictionary
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class ReaderDialogs(
    private val context: Activity,
    private val store: StudyStore,
    private val play: (Word) -> Unit,
    playSentence: (String) -> Unit,
    stopAudio: () -> Unit,
    private val strokeOrder: (Word) -> Unit,
    private val editCard: (TextLibrary.Selection, WordDraft, (Word, () -> Unit) -> Unit) -> Dialog,
    private val libraryChanged: () -> Unit
) {
    private val library = store.textLibrary
    private val dictionary = OfflineDictionary(context)
    private val russianDictionary = OfflineDictionary(context, OfflineDictionary.Source.RU_WIKTIONARY)
    private val dialogs = mutableSetOf<Dialog>()
    private var screen: ReaderScreen? = null
    private var librarySheet: BottomSheetDialog? = null
    private var closed = false
    private var snapshot: VocabularyLookup? = null
    private val recall = TextRecallDialogs(context, library, store.textRecall, ::track)
    private val sentencePractice = SentencePractice(context, library, playSentence, stopAudio, ::track)

    private fun vocabulary() = snapshot ?: store.vocabularyLookup().also { snapshot = it }
    private fun track(dialog: Dialog) = track(dialog) {}

    private fun track(dialog: Dialog, dismissed: () -> Unit) {
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            dismissed()
        }
    }

    fun close() {
        closed = true
        dialogs.toList().asReversed().forEach(Dialog::dismiss)
        screen?.close()
        screen = null
    }

    fun currentReading() = screen?.let { it.document to it.offset() }
    fun savePosition() { screen?.savePosition() }
    fun restore(id: String, revision: Int, offset: Int, revisionId: String? = null) {
        library.revision(id, revision, revisionId)?.let { read(it, offset) }
    }

    fun refreshAfterSync() {
        snapshot = null
        librarySheet?.takeIf { it.isShowing }?.let(::renderLibrary)
    }

    private fun toast(value: String) = Toast.makeText(context, value, Toast.LENGTH_SHORT).show()

    private fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = context.dp(20)
        setPadding(padding, padding, padding, padding)
    }
    private fun LinearLayout.label(value: String, size: Float) {
        append(ReadingUi.text(context, value, size, muted = true), 12)
    }

    fun library() {
        val sheet = BottomSheetDialog(context)
        librarySheet = sheet
        renderLibrary(sheet)
        sheet.show()
        track(sheet) { if (librarySheet === sheet) librarySheet = null }
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun renderLibrary(sheet: BottomSheetDialog) {
        val body = column()
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ReadingUi.title(context, "Мои тексты"), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(ReadingUi.icon(context, R.drawable.ic_close, "Закрыть", sheet::dismiss), context.dp(48), context.dp(48))
        body.append(toolbar)
        body.addView(ReadingUi.rule(context), LinearLayout.LayoutParams(-1, context.dp(1)).apply { topMargin = context.dp(12) })
        body.append(ReadingUi.button(context, "Добавить текст", primary = true) { sheet.dismiss(); edit(null) }, 16)
        body.append(ReadingUi.button(context, "Повторение текстов", action = recall::dashboard), 8)
        val documents = library.documents()
        if (documents.isEmpty()) body.label("Пока нет сохранённых текстов.", 15f)
        documents.forEach { document ->
            val card = WordCardUi.surface(context).apply {
                append(ReadingUi.title(context, document.title, 19f))
                if (document.source.isNotBlank()) append(ReadingUi.text(context, document.source, 14f, muted = true), 4)
                isFocusable = true
                val ripple = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                foreground = androidx.core.content.ContextCompat.getDrawable(context, ripple.resourceId)
                setOnClickListener { sheet.dismiss(); read(document) }
                append(ReadingUi.button(context, context.getString(R.string.reader_learn_text)) { recall.learn(document) }
                    .apply { contentDescription = context.getString(R.string.reader_learn_named_text, document.title) }, 8)
            }
            body.append(card, 10)
        }
        sheet.setContentView(NestedScrollView(context).apply {
            background = ReadingUi.sheetSurface(context)
            addView(body)
        })
        sheet.behavior.maxHeight = context.resources.displayMetrics.heightPixels * 9 / 10
    }
    private fun edit(previous: TextLibrary.Document?) {
        val body = column()
        fun input(hint: String, value: String, multiline: Boolean = false): EditText {
            val field = com.google.android.material.textfield.TextInputLayout(context).apply {
                this.hint = hint
                boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
                val radius = context.dp(12).toFloat()
                setBoxCornerRadii(radius, radius, radius, radius)
            }
            val editor = com.google.android.material.textfield.TextInputEditText(context).apply {
                setText(value)
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                if (multiline) {
                    minLines = 6
                    maxLines = 10
                    gravity = Gravity.TOP or Gravity.START
                } else setSingleLine(true)
            }
            field.addView(editor, -1, -2)
            body.append(field, 10)
            return editor
        }
        val title = input("Название текста", previous?.title.orEmpty())
        val source = input("Источник (необязательно)", previous?.source.orEmpty())
        val annotated = CheckBox(context).apply {
            text = "Текст с пиньинем и переводами"
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            buttonTintList = ColorStateList(states, intArrayOf(context.getColor(R.color.chinlern_red), WordCardUi.muted))
            isChecked = previous?.wordNotes?.isNotEmpty() == true
        }.also { body.append(it, 8) }
        val format = ReadingUi.text(context, "Пример: {我|wǒ}去{图书馆|tu2 shu1 guan3|библиотека}。\n" +
            "Перевод можно оставить пустым, чтобы искать значение в словаре.", 14f, muted = true).also { body.append(it, 6) }
        val initial = if (annotated.isChecked && previous != null) AnnotatedText.format(previous) else previous?.text.orEmpty()
        val text = input("Вставьте китайский текст", initial, true)
        val translation = input("Перевод текста (необязательно)", previous?.translation.orEmpty(), true)
        translation.minLines = 2
        fun showFormat() { format.visibility = if (annotated.isChecked) android.view.View.VISIBLE else android.view.View.GONE }
        showFormat()
        var lastAnnotated: AnnotatedText.Passage? = null
        var lastMarkup: String? = null
        annotated.setOnCheckedChangeListener { _, checked ->
            val draft = text.text.toString()
            if (checked && draft == lastAnnotated?.text) text.setText(lastMarkup)
            if (!checked) {
                val parsed = runCatching { AnnotatedText.parse(draft) }.getOrNull()
                lastAnnotated = parsed
                lastMarkup = draft
                if (parsed != null) text.setText(parsed.text)
            }
            showFormat()
        }
        if (previous != null) body.label("Изменения создадут новую версию. Сохранённые контексты останутся привязаны к прежнему тексту.", 14f)
        val dialog = MaterialAlertDialogBuilder(context).setTitle(if (previous == null) "Новый текст" else "Изменить текст")
            .setView(NestedScrollView(context).apply { addView(body) }).setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            try {
                val translated = translation.text.toString().trim()
                val document = if (annotated.isChecked) {
                    library.saveAnnotated(title.text.toString(), text.text.toString(), source.text.toString(), previous, translated)
                } else library.save(title.text.toString(), text.text.toString(), source.text.toString(), previous, translated)
                dialog.dismiss(); read(document)
            } catch (error: IllegalArgumentException) { text.error = error.message }
        } }
        dialog.show()
        track(dialog)
    }
    private fun read(document: TextLibrary.Document, offset: Int? = null, selection: TextLibrary.Selection? = null) {
        screen?.close()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        fun action(title: String, run: () -> Unit) { actions.add(title to run) }
        action(context.getString(R.string.reader_learn_text)) { recall.learn(document) }
        action("Сохранённые контексты") { showContexts(document) }
        action("Копировать текст") {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(document.title, document.text))
        }
        action("Версии текста") {
            val revisions = library.revisions(document.id)
            track(MaterialAlertDialogBuilder(context).setTitle("Версии текста")
                .setItems(revisions.map { "${it.revision}. ${it.title}" }.toTypedArray()) { _, index ->
                    read(revisions[index])
                }.show())
        }
        if (library.revisions(document.id).lastOrNull() == document) action("Изменить текст") {
            screen?.close()
            edit(document)
        }
        action("Удалить текст") {
            track(MaterialAlertDialogBuilder(context).setTitle("Удалить текст, его контексты и фрагменты для повторения?")
                .setPositiveButton("Удалить") { _, _ -> screen?.close(); library.delete(document); library() }
                .setNegativeButton("Отмена", null).show())
        }
        val details = ReaderWordSheet(context, listOf(russianDictionary, dictionary), ::vocabulary, play, strokeOrder, { quote, pleco ->
            ExternalDictionary(context).lookup(quote, pleco)?.let(::toast)
        }, { selected, entry ->
            try {
                attachContext(selected, entry.packId, entry.word)
                toast("Контекст сохранён · ${entry.packName}")
            } catch (error: Exception) { toast(error.message ?: "Не удалось сохранить контекст.") }
        }, ::addWord, ::track)
        val reader = ReaderScreen(context, library, document, dictionary, ::vocabulary, details, recall::add,
            { active -> sentencePractice.show(active.document, active.offset(), active::jumpTo, active::memorizeSentence) },
            ::track) { previous, back ->
            if (screen === previous) screen = null
            if (back && !closed) library()
        }
        screen = reader
        reader.show(actions, offset?.coerceIn(0, document.text.length), selection)
    }

    private fun showContexts(document: TextLibrary.Document) {
        val contexts = library.savedContextLinks(document.id)
            .distinctBy { Triple(it.revision, it.start, it.end) }
        val picker = MaterialAlertDialogBuilder(context).setTitle("Контексты текста")
        if (contexts.isEmpty()) picker.setMessage("Пока нет сохранённых контекстов.")
        else {
            val labels = contexts.map { entry ->
                val preview = if (entry.label.length <= 200) entry.label else entry.label.take(199) + "…"
                if (entry.revisionId == document.revisionId) preview else "Версия ${entry.revision} · $preview"
            }
            picker.setItems(labels.toTypedArray()) { _, index ->
                val selection = library.contextSelection(document.id, contexts[index])
                val reader = screen
                when {
                    selection == null -> toast("Исходный фрагмент больше недоступен.")
                    reader != null && reader.document == selection.document -> reader.jumpTo(selection)
                    else -> read(selection.document, selection = selection)
                }
            }
        }
        track(picker.setPositiveButton("Закрыть", null).show())
    }

    private fun addWord(selection: TextLibrary.Selection, prefill: WordDraft, finished: () -> Unit) {
        val packs = store.packNames
        val names = packs.map { (id, name) -> store.packLabel(id, name) }
        track(MaterialAlertDialogBuilder(context).setTitle("Выберите набор")
            .setItems(names.toTypedArray()) { _, index ->
                val id = packs[index].first
                val name = names[index]
                track(editCard(selection, prefill) { word, dismissEditor ->
                    check(!closed) { "Профиль изменился. Откройте текст снова." }
                    val duplicate = store.packWords(id).firstOrNull { existing ->
                        existing.hanzi == word.hanzi && duplicateSense(existing, word)
                    }
                    fun done(message: String) {
                        snapshot = null
                        dismissEditor()
                        finished()
                        screen?.refreshWords()
                        libraryChanged()
                        toast(message)
                    }
                    if (duplicate == null) {
                        store.addWordToPack(id, word, selection)
                        done("Слово добавлено · $name")
                    } else {
                        track(MaterialAlertDialogBuilder(context).setTitle("Карточка уже есть в наборе")
                            .setMessage("${duplicate.hanzi} · ${duplicate.pinyin}\n${duplicate.meanings.joinToString("; ")}\n$name")
                            .setPositiveButton("Сохранить контекст") { _, _ ->
                                try {
                                    attachContext(selection, id, duplicate)
                                    done("Контекст сохранён · $name")
                                } catch (error: Exception) { toast(error.message ?: "Не удалось сохранить контекст.") }
                            }.setNegativeButton("Отмена", null).show())
                    }
                })
            }.setNegativeButton("Отмена", null).show())
    }

    private fun attachContext(selection: TextLibrary.Selection, packId: String, word: Word) {
        snapshot = null
        val current = store.packWords(packId).firstOrNull { it.id == word.id }
        require(current != null && SharedWords.identity(current) == SharedWords.identity(word)) {
            "Карточка изменилась. Откройте слово снова."
        }
        library.annotate(selection, VocabularyReference(packId, word.id))
    }

    private fun duplicateSense(existing: Word, candidate: Word): Boolean {
        val stored = SharedWords.identity(existing)
        val entered = SharedWords.identity(candidate)
        return stored.pronunciation == entered.pronunciation && entered.meanings.all { it in stored.meanings }
    }
}
