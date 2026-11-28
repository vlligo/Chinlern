package com.example.chinlern.texts

import android.app.Dialog
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.example.chinlern.R
import com.example.chinlern.StudyLayout
import com.example.chinlern.WordCardUi.append
import com.example.chinlern.WordCardUi.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class SentencePractice(
    private val context: Context,
    private val library: TextLibrary,
    private val play: (String) -> Unit,
    private val stopAudio: () -> Unit,
    private val track: (Dialog, () -> Unit) -> Unit
) {
    fun show(
        document: TextLibrary.Document,
        offset: Int,
        jumpTo: (TextLibrary.Selection) -> Unit,
        memorize: (TextLibrary.Selection) -> Unit
    ) {
        val title = R.string.reader_sentence_practice
        val spans = ReaderSentences.spans(document.text)
        if (spans.isEmpty()) {
            track(MaterialAlertDialogBuilder(context).setTitle(title)
                .setMessage("В тексте нет предложений для чтения.")
                .setPositiveButton("Закрыть", null).show()) {}
            return
        }

        var index = spans.indexOfFirst { it.end > offset }.takeIf { it >= 0 } ?: spans.lastIndex
        fun selection() = spans[index].let { library.select(document, it.start, it.end) }

        val style = library.readingStyle
        val position = ReadingUi.text(context, "", 14f, muted = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val passage = ReadingUi.text(context, "", style.size.toFloat()).apply {
            setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                style.spacing.toFloat(), resources.displayMetrics), 1f)
        }
        val reveal = ReadingUi.button(context, "Скрыть текст") {}.apply {
            isCheckable = true
            isChecked = true
            addOnCheckedChangeListener { button, shown ->
                passage.visibility = if (shown) View.VISIBLE else View.GONE
                button.text = if (shown) "Скрыть текст" else "Показать текст"
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(20))
        }
        val scroll = NestedScrollView(context).apply { addView(StudyLayout.centered(content, 680)) }
        val dialog = Dialog(context, R.style.Theme_Chinlern)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_paper))
        }
        val barColor = ContextCompat.getColor(context, R.color.chinlern_reading_surface)
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(6), context.dp(18), context.dp(6))
            addView(ReadingUi.icon(context, R.drawable.ic_stroke_back, "Назад", dialog::dismiss),
                context.dp(48), context.dp(48))
            addView(ReadingUi.title(context, context.getString(title), 22f),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(8) })
        }
        page.addView(StudyLayout.centered(header, 680).apply { setBackgroundColor(barColor) }, MATCH_PARENT, WRAP_CONTENT)
        page.addView(ReadingUi.rule(context), MATCH_PARENT, context.dp(1))
        page.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        page.addView(ReadingUi.rule(context), MATCH_PARENT, context.dp(1))

        content.addView(ReadingUi.icon(context, R.drawable.ic_speaker, "Прослушать") { play(selection().quote) }, context.dp(48), context.dp(48))
        content.append(reveal, 8)
        content.append(passage, 12)
        content.append(ReadingUi.button(context, "Запомнить предложение", primary = true) {
            stopAudio()
            memorize(selection())
        }, 12)
        content.append(ReadingUi.button(context, "Открыть в тексте") {
            dialog.dismiss()
            jumpTo(selection())
        }, 8)

        val previous = ReadingUi.icon(context, R.drawable.ic_stroke_back, "Предыдущее предложение") {}
        val next = ReadingUi.icon(context, R.drawable.ic_reader_next, "Следующее предложение") {}
        val navigation = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(4))
            val targetSize = context.dp(48)
            addView(previous, targetSize, targetSize)
            addView(position, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(next, targetSize, targetSize)
        }
        page.addView(StudyLayout.centered(navigation, 680).apply { setBackgroundColor(barColor) }, MATCH_PARENT, WRAP_CONTENT)
        fun render() {
            position.text = context.getString(R.string.reader_sentence_position, index + 1, spans.size)
            passage.text = selection().quote
            previous.isEnabled = index > 0
            next.isEnabled = index < spans.lastIndex
            previous.alpha = if (previous.isEnabled) 1f else 0.4f
            next.alpha = if (next.isEnabled) 1f else 0.4f
            scroll.scrollTo(0, 0)
        }
        previous.setOnClickListener {
            stopAudio()
            index--
            render()
        }
        next.setOnClickListener {
            stopAudio()
            index++
            render()
        }
        render()
        dialog.setContentView(page)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        track(dialog, stopAudio)
    }
}
