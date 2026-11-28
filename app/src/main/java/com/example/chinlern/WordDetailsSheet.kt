package com.example.chinlern

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.example.chinlern.WordCardUi.dp
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Shared read-only presentation for vocabulary, practice and future reader entry points. */
object WordDetailsSheet {
    /** Native selection handles let the reader correct Chinese word boundaries. */
    fun enableWordLookup(
        view: TextView, lookup: (String) -> Unit, selection: ((Int, Int) -> Unit)? = null,
        memorize: ((Int, Int) -> Unit)? = null
    ) {
        view.setTextIsSelectable(true)
        view.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, android.view.Menu.FIRST + 42, 0, "Найти слово")
                if (memorize != null) menu.add(0, android.view.Menu.FIRST + 43, 1, "Учить фрагмент")
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val action = when (item.itemId) {
                    android.view.Menu.FIRST + 42 -> selection
                    android.view.Menu.FIRST + 43 -> memorize ?: return false
                    else -> return false
                }
                val start = minOf(view.selectionStart, view.selectionEnd)
                val end = maxOf(view.selectionStart, view.selectionEnd)
                if (start >= 0 && end > start) {
                    val selected = if (action == null) view.text.subSequence(start, end).toString() else null
                    mode.finish()
                    if (selected != null) lookup(selected) else action?.invoke(start, end)
                }
                return true
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
        }
    }

    fun show(context: Context, word: Word, packs: List<String>, play: (Word) -> Unit,
        external: (Word) -> Unit, lookup: (String) -> Unit): BottomSheetDialog {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun textView(value: String, size: Float, searchable: Boolean = false) = TextView(context).apply {
            text = value; textSize = size; setTextIsSelectable(true)
            if (searchable) enableWordLookup(this, lookup)
            setPadding(0, padding / 3, 0, padding / 3)
        }
        fun text(value: String, size: Float = 18f, searchable: Boolean = false) {
            if (value.isNotBlank()) body.addView(textView(value, size, searchable))
        }
        fun action(title: String, action: () -> Unit) {
            body.addView(com.example.chinlern.DetailUi.button(context, title, action))
        }
        val pronounce = DetailUi.icon(context, R.drawable.ic_speaker, "Слушать") { play(word) }
        body.addView(WordCardUi.titleRow(textView(word.writtenForms.joinToString(" / "), 28f), pronounce))
        text(word.pinyin.ifBlank { word.numbered })
        text(word.meanings.joinToString("; "))
        text(word.hint)
        text(if (packs.isEmpty()) "Личная карточка" else "Наборы: ${packs.joinToString(", ")}", 14f)
        action("Открыть внешний словарь") { external(word) }
        if (word.example.isNotBlank()) {
            text("Пример", 16f)
            text(word.example, 24f, searchable = true)
            text(word.examplePinyin)
            text(word.exampleTranslation)
            action("Найти слово из примера") { lookup(word.example) }
        }
        return present(context, body)
    }

    internal fun present(context: Context, body: LinearLayout): BottomSheetDialog {
        val sheet = BottomSheetDialog(context)
        val scroller = NestedScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(body)
                addView(DetailUi.icon(context, R.drawable.ic_close, "Закрыть", action = sheet::dismiss),
                    LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                        gravity = Gravity.END
                        marginEnd = context.dp(20)
                        bottomMargin = context.dp(20)
                    })
            })
        }
        sheet.setContentView(scroller)
        sheet.behavior.maxHeight = context.resources.displayMetrics.heightPixels * 4 / 5
        sheet.show()
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        return sheet
    }
}
