package com.example.chinlern.texts

import android.app.Dialog
import android.content.Context
import android.icu.text.BreakIterator
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.example.chinlern.Fsrs
import com.example.chinlern.R
import com.example.chinlern.StudyLayout
import com.example.chinlern.WordCardUi
import com.example.chinlern.WordCardUi.append
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.UUID

internal class TextRecallDialogs(
    private val context: Context,
    private val library: TextLibrary,
    private val recall: TextRecall,
    private val track: (Dialog) -> Unit
) {
    fun add(selection: TextLibrary.Selection) {
        safely { practice(listOf(recall.add(selection)), previewFirst = true) }
    }

    fun learn(document: TextLibrary.Document) = safely {
        val fragments = ReaderSentences.forRecall(document.text)
        if (fragments.isEmpty()) {
            track(MaterialAlertDialogBuilder(context).setTitle(R.string.reader_learn_text)
                .setMessage("В тексте нет фрагментов для обучения.")
                .setPositiveButton("Закрыть", null).show())
            return@safely
        }
        val saved = recall.queue().targets.filter { target ->
            target.documentId == document.id && target.revision == document.revision &&
                target.end - target.start == target.quote.length &&
                document.text.startsWith(target.quote, target.start)
        }.associateBy { ReaderWords.Span(it.start, it.end) }
        val pending = fragments.indices.filter { (saved[fragments[it]]?.reviews ?: 0) == 0 }
        fun start(indices: List<Int>) {
            practice(indices.size, total = fragments.size, learning = true) { index ->
                val originalIndex = indices[index]
                val span = fragments[originalIndex]
                Round(originalIndex + 1, library.select(document, span.start, span.end), saved[span])
            }
        }
        if (pending.isNotEmpty()) start(pending)
        else track(MaterialAlertDialogBuilder(context).setTitle(document.title)
            .setMessage("Все фрагменты пройдены.")
            .setPositiveButton("Пройти ещё раз") { _, _ -> start(fragments.indices.toList()) }
            .setNegativeButton("Закрыть", null).show())
    }

    fun dashboard() {
        val queue = recall.queue()
        val prompt = MaterialAlertDialogBuilder(context).setTitle("Повторение текстов").setNegativeButton("Закрыть", null)
        if (queue.targets.isEmpty()) {
            prompt.setMessage("Откройте текст и выберите «Учить фрагмент».")
        } else {
            prompt.setMessage("К повторению · ${queue.due.size}\nВсего фрагментов · ${queue.targets.size}")
            if (queue.due.isNotEmpty()) prompt.setPositiveButton("Начать повторение") { _, _ -> practice(queue.due) }
            prompt.setNeutralButton("Все фрагменты") { _, _ -> showTargets() }
        }
        track(prompt.show())
    }

    private fun showTargets() {
        val targets = recall.queue().targets
        val labels = targets.map { target ->
            val preview = if (target.quote.length <= 36) target.quote else target.quote.take(35) + "…"
            val schedule = if (target.reviews == 0) "Новый фрагмент" else dueLabel(target.due)
            "${target.title}\n$preview\n$schedule"
        }
        track(MaterialAlertDialogBuilder(context).setTitle("Все фрагменты")
            .setItems(labels.toTypedArray()) { _, index -> manageTarget(targets[index]) }
            .setNegativeButton("Закрыть", null).show())
    }

    private fun manageTarget(target: TextRecall.Target) {
        val message = if (target.reviews == 0) target.quote
            else "${target.quote}\n\nПовторений · ${target.reviews}\nС подсказкой · ${target.assistedReviews}\n${dueLabel(target.due)}"
        track(MaterialAlertDialogBuilder(context).setTitle(target.title).setMessage(message)
            .setPositiveButton("Тренировать фрагмент") { _, _ -> practice(listOf(target), previewFirst = true) }
            .setNeutralButton("Удалить") { _, _ ->
                track(MaterialAlertDialogBuilder(context).setTitle("Удалить фрагмент и его расписание?")
                    .setPositiveButton("Удалить") { _, _ ->
                        safely { recall.remove(target); dashboard() }
                    }.setNegativeButton("Отмена", null).show())
            }.setNegativeButton("Закрыть", null).show())
    }

    private data class Round(val number: Int, val selection: TextLibrary.Selection?, val target: TextRecall.Target?)

    private fun practice(targets: List<TextRecall.Target>, previewFirst: Boolean = false) =
        practice(targets.size, previewFirst = previewFirst) { index ->
            val target = targets[index]
            Round(index + 1, recall.selection(target), target)
        }

    private fun practice(
        count: Int,
        total: Int = count,
        learning: Boolean = false,
        previewFirst: Boolean = false,
        load: (Int) -> Round
    ) {
        val dialog = Dialog(context, R.style.Theme_Chinlern)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val page = column().apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_paper))
        }
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(18), dp(6))
        }
        toolbar.addView(ReadingUi.icon(context, R.drawable.ic_stroke_back, "Назад", dialog::dismiss), dp(48), dp(48))
        val heading = if (learning) context.getString(R.string.reader_learn_text) else "Запоминание текста"
        toolbar.addView(ReadingUi.title(context, heading, 22f),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        page.addView(StudyLayout.centered(toolbar, 680).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_reading_surface))
        }, -1, -2)
        page.addView(ReadingUi.rule(context), -1, dp(1))
        val body = column().apply { setPadding(dp(20), dp(18), dp(20), dp(28)) }
        val scroll = NestedScrollView(context).apply { addView(StudyLayout.centered(body, 680)) }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(page)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val style = library.readingStyle
        var index = 0

        fun showRound() {
            val (number, selection, target) = load(index)
            if (selection == null) {
                body.removeAllViews()
                body.label("Исходный фрагмент больше недоступен.")
                if (index + 1 < count) body.action("Следующий фрагмент", primary = true) { index++; showRound() }
                else body.action("Готово", primary = true, run = dialog::dismiss)
                return
            }
            val quote = selection.quote
            val cue = surroundingText(selection)
            val boundaries = BreakIterator.getCharacterInstance().run {
                setText(quote)
                generateSequence(first()) { next().takeUnless { it == BreakIterator.DONE } }.toList()
            }
            val attempt = UUID.randomUUID().toString()
            var preview = learning || previewFirst && index == 0
            var reveal = false
            var hint = 0
            var saved: TextRecall.Target? = null
            fun render() {
                body.removeAllViews()
                scroll.scrollTo(0, 0)
                body.append(ReadingUi.title(context, target?.title ?: selection.document.title, 21f))
                if (learning || total > 1) {
                    body.label(context.getString(R.string.reader_fragment_position, number, total), 14f, muted = true)
                }
                val result = saved
                when {
                    result != null -> {
                        body.append(WordCardUi.surface(context).apply {
                            if (hint > 0) append(ReadingUi.text(context, "Повторено с подсказкой", 14f, muted = true))
                            append(ReadingUi.text(context, dueLabel(result.due)), if (hint > 0) 6 else 0)
                        }, 12)
                        if (index + 1 < count) body.action("Следующий фрагмент", primary = true) { index++; showRound() }
                        else body.action("Готово", primary = true, run = dialog::dismiss)
                    }
                    preview -> {
                        body.passage(quote, style)
                        body.action("Скрыть текст", primary = true) { preview = false; render() }
                    }
                    reveal -> {
                        body.passage(quote, style)
                        body.label("Оцените, что удалось вспомнить до показа ответа.", muted = true)
                        fun rate(label: String, rating: Fsrs.Rating) = body.action(label) {
                            if (saved == null) safely {
                                saved = recall.record(target ?: recall.add(selection), attempt, rating, hint > 0)
                                render()
                            }
                        }
                        rate("Не вспомнил", Fsrs.Rating.AGAIN)
                        if (hint > 0) rate("С подсказкой", Fsrs.Rating.HARD)
                        else {
                            rate("С трудом", Fsrs.Rating.HARD)
                            rate("Вспомнил", Fsrs.Rating.GOOD)
                            rate("Легко", Fsrs.Rating.EASY)
                        }
                    }
                    else -> {
                        body.label("Вспомните фрагмент вслух или про себя.")
                        if (cue.isNotEmpty()) body.label(cue, muted = true)
                        if (hint > 0) body.passage(quote.substring(0, boundaries[hint]) + "…", style)
                        val limit = boundaries.lastIndex - 1
                        if (hint < limit) body.action(if (hint == 0) "Показать начало" else "Ещё подсказка") {
                            hint = minOf(hint + 3, limit)
                            render()
                        }
                        body.action("Показать ответ", primary = true) { reveal = true; render() }
                    }
                }
            }
            render()
        }

        showRound()
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        track(dialog)
    }

    private fun surroundingText(selection: TextLibrary.Selection): String {
        val text = selection.document.text
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
        val left = boundaries.preceding((selection.start - 24).coerceAtLeast(0)).coerceAtLeast(0)
        val right = boundaries.following((selection.end + 24).coerceAtMost(text.length))
            .let { if (it == BreakIterator.DONE) text.length else it }
        val quote = selection.quote.trim()
        fun excerpt(start: Int, end: Int) = text.substring(start, end).trim()
            .takeUnless { quote in it }.orEmpty()
        val before = excerpt(left, selection.start)
        val after = excerpt(selection.end, right)
        return if (before.isEmpty() && after.isEmpty()) "" else "$before［…］$after"
    }

    private fun dueLabel(due: Long) =
        "Следующее повторение · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(due))

    private fun safely(run: () -> Unit) {
        try { run() } catch (error: Exception) {
            Toast.makeText(context, error.message ?: "Не удалось сохранить фрагмент.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.action(name: String, primary: Boolean = false, run: () -> Unit) {
        append(ReadingUi.button(context, name, primary, run), 8)
    }
    private fun LinearLayout.label(value: String, size: Float = 16f, muted: Boolean = false) {
        append(ReadingUi.text(context, value, size, muted), 8)
    }
    private fun LinearLayout.passage(value: String, style: TextLibrary.ReadingStyle) {
        append(WordCardUi.surface(context).apply {
            addView(ReadingUi.rule(context), dp(32), dp(2))
            append(ReadingUi.text(context, value, style.size.toFloat()).apply {
                setLineSpacing(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                    style.spacing.toFloat(), resources.displayMetrics), 1f)
            }, 10)
        }, 12)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
