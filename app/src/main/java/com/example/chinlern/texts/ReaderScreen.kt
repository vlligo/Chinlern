package com.example.chinlern.texts

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.icu.text.BreakIterator
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.widget.NestedScrollView
import com.example.chinlern.R
import com.example.chinlern.StudyLayout
import com.example.chinlern.VocabularyLookup
import com.example.chinlern.WordDetailsSheet
import com.example.chinlern.WordCardUi
import com.example.chinlern.dictionary.OfflineDictionary
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

internal class ReaderScreen(
    private val context: Context,
    private val library: TextLibrary,
    val document: TextLibrary.Document,
    private val dictionary: OfflineDictionary,
    private val vocabulary: () -> VocabularyLookup,
    private val details: ReaderWordSheet,
    private val memorize: (TextLibrary.Selection) -> Unit,
    private val practiceSentences: (ReaderScreen) -> Unit,
    private val track: (Dialog) -> Unit,
    private val closed: (ReaderScreen, Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val dialog = Dialog(context, R.style.Theme_Chinlern)
    private val passage = PassageView(context)
    private val scroll = ScrollView(context)
    private val boundaries = BreakIterator.getCharacterInstance().run {
        setText(document.text)
        generateSequence(first()) { next().takeUnless { it == BreakIterator.DONE } }.toList().toIntArray()
    }
    private var annotations: List<ReaderWords.Annotation>? = null
    private val suppliedNotes = document.wordNotes.associateBy(TextLibrary.WordNote::span)
    private val suppliedAnnotations by lazy {
        document.wordNotes.map { ReaderWords.Annotation(it.span, AnnotatedText.display(it.pinyin)) }
    }
    private var choices = library.pinyinChoices(document)
    private var inlinePinyin = library.inlinePinyin
    private var style = library.readingStyle
    private var lastSavedOffset = library.readingPosition(document)
    private val deferredSave = Runnable { savePosition() }
    private var finished = false
    private val positionLabel = if (document.text.length >= 300) TextView(context) else null
    private val progress = positionLabel?.let {
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.chinlern_gold))
            progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.chinlern_reading_gold_wash))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    private var rubyWidth = 0
    private var preservedOffset: Int? = null
    private var restoringOffset = false
    private var wordRequest = 0
    private var pendingTap: Int? = null
    private var returnToLibrary = true

    fun show(actions: List<Pair<String, () -> Unit>>, initialOffset: Int?, selection: TextLibrary.Selection?) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_paper))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_reading_surface))
        }
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(2))
        }
        toolbar.addView(ReadingUi.icon(context, R.drawable.ic_stroke_back, "Назад", dialog::dismiss), dp(48), dp(48))
        toolbar.addView(ReadingUi.title(context, document.title, 22f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
        toolbar.addView(ReadingUi.icon(context, R.drawable.ic_list_more, "Действия с текстом") {
            val menu = listOf(
                "Учить фрагмент" to ::pickFragment,
                context.getString(R.string.reader_sentence_practice) to { practiceSentences(this) }
            ) + actions
            track(MaterialAlertDialogBuilder(context).setTitle("Действия с текстом")
                .setItems(menu.map { it.first }.toTypedArray()) { _, index -> menu[index].second() }
                .show())
        }, dp(48), dp(48))
        header.addView(StudyLayout.centered(toolbar, 680), MATCH_PARENT, WRAP_CONTENT)

        val controls = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }
        controls.addView(ReadingUi.button(context, "Пиньинь") {
            inlinePinyin = !inlinePinyin
            library.inlinePinyin = inlinePinyin
            renderPassage()
        }.apply {
            isCheckable = true
            isChecked = inlinePinyin
            textSize = 14f
            minHeight = dp(48)
        }, WRAP_CONTENT, WRAP_CONTENT)
        if (document.translation.isNotBlank()) {
            controls.addView(ReadingUi.button(context, "Перевод", action = ::showTranslation).apply {
                textSize = 14f
                minHeight = dp(48)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        controls.addView(ReadingUi.icon(context, R.drawable.ic_text_size, "Настройки чтения", ::showTypography),
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        positionLabel?.let { label ->
            label.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            label.textSize = 13f
            label.setSingleLine(true)
            label.setTextColor(WordCardUi.muted)
            label.visibility = View.INVISIBLE
            controls.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        header.addView(StudyLayout.centered(controls, 680), MATCH_PARENT, WRAP_CONTENT)
        page.addView(header, MATCH_PARENT, WRAP_CONTENT)
        if (progress == null) page.addView(ReadingUi.rule(context), MATCH_PARENT, dp(1))
        else page.addView(progress, MATCH_PARENT, dp(3))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(30))
        }
        if (document.source.isNotBlank()) content.addView(ReadingUi.text(context, document.source, 14f, muted = true).apply {
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(16))
        })
        passage.setTextColor(ContextCompat.getColor(context, R.color.chinlern_ink))
        passage.highlightColor = ContextCompat.getColor(context, R.color.chinlern_reading_gold_wash)
        passage.text = document.text
        applyStyle()
        WordDetailsSheet.enableWordLookup(passage, {}, ::select) { start, end -> memorize(selectionFor(start, end)) }
        passage.tap = { offset ->
            val span = wordAt(offset)
            if (span == null) passage.highlight = null else {
                select(span.start, span.end)
                if (annotations == null && span !in suppliedNotes) pendingTap = offset
            }
        }
        ViewCompat.addAccessibilityAction(passage, "Найти слово") { _, _ ->
            val first = annotations?.firstOrNull { it.span.end > offset() }?.span
                ?: wordAt(offset()) ?: ReaderWords.Span(0, boundaries[1])
            adjust(library.select(document, first.start, first.end), chosen = ::openSelection)
            true
        }
        content.addView(passage, MATCH_PARENT, WRAP_CONTENT)
        passage.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (inlinePinyin && right - left != oldRight - oldLeft &&
                right - left - passage.totalPaddingLeft - passage.totalPaddingRight != rubyWidth) {
                passage.post { if (dialog.isShowing) renderPassage() }
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.addView(StudyLayout.centered(content, 680), MATCH_PARENT, WRAP_CONTENT)
        scroll.setOnScrollChangeListener { _, _, y, _, previous ->
            if (!restoringOffset && y != previous) {
                preservedOffset = null
                positionChanged()
            }
        }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateIndicator() }
        page.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(page)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        dialog.setOnDismissListener {
            finishReading()
            closed(this, returnToLibrary)
        }
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        if (selection != null) jumpTo(selection)
        else (initialOffset ?: lastSavedOffset).takeIf { it > 0 }?.let(::restoreAfterLayout)
        refreshWords()
    }

    private fun showTranslation() {
        val translated = ReadingUi.text(context, document.translation).apply {
            setPadding(dp(24), dp(12), dp(24), dp(16))
            setTextIsSelectable(true)
        }
        track(MaterialAlertDialogBuilder(context).setTitle("Перевод")
            .setView(NestedScrollView(context).apply { addView(translated, MATCH_PARENT, WRAP_CONTENT) })
            .setPositiveButton("Закрыть", null).show())
    }

    private fun showTypography() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun controls(label: String, values: List<Int>, names: List<String>, selected: Int,
            direction: Int, choose: (Int) -> Unit) {
            content.addView(ReadingUi.title(context, label, 16f).apply {
                setPadding(0, dp(8), 0, dp(4))
            })
            val group = RadioGroup(context).apply { orientation = direction }
            values.forEachIndexed { index, value ->
                group.addView(RadioButton(context).apply {
                    id = View.generateViewId()
                    text = names[index]
                    minHeight = dp(48)
                })
                if (value == selected) group.check(group.getChildAt(index).id)
            }
            group.setOnCheckedChangeListener { _, checked ->
                val selectedIndex = group.indexOfChild(group.findViewById(checked))
                values.getOrNull(selectedIndex)?.let(choose)
            }
            content.addView(group)
        }
        val sizes = TextLibrary.ReadingStyle.sizes
        val config = context.resources.configuration
        val sizeControls = if (config.screenWidthDp < 360 || config.fontScale > 1.2f) LinearLayout.VERTICAL
            else LinearLayout.HORIZONTAL
        controls("Размер текста", sizes, sizes.map(Int::toString), style.size, sizeControls) {
            changeStyle(style.copy(size = it))
        }
        controls("Межстрочный интервал", TextLibrary.ReadingStyle.spacings,
            listOf("Плотный", "Обычный", "Широкий"), style.spacing, LinearLayout.VERTICAL) {
            changeStyle(style.copy(spacing = it))
        }
        track(MaterialAlertDialogBuilder(context).setTitle("Настройки чтения")
            .setView(NestedScrollView(context).apply { addView(content) })
            .setPositiveButton("Готово", null).show())
    }

    private fun changeStyle(updated: TextLibrary.ReadingStyle) {
        if (style == updated) return
        val anchor = offset()
        style = updated
        library.readingStyle = updated
        applyStyle()
        restoreAfterLayout(anchor)
        if (inlinePinyin) renderPassage()
    }

    private fun applyStyle() {
        passage.textSize = style.size.toFloat()
        passage.setLineSpacing(sp(style.spacing.toFloat()), 1f)
    }

    fun jumpTo(selection: TextLibrary.Selection) {
        pendingTap = null
        passage.highlight = ReaderWords.Span(selection.start, selection.end)
        if (!scroll.isLaidOut || scroll.isLayoutRequested || restoringOffset) restoreAfterLayout(selection.start)
        else {
            preservedOffset = selection.start
            placeAnchor()
        }
    }

    fun refreshWords() {
        if (!dialog.isShowing) return
        val snapshot = vocabulary()
        val request = ++wordRequest
        worker.execute {
            val proposed = ReaderAnnotations.load(document, boundaries, snapshot, dictionary)
            handler.post {
                if (dialog.isShowing && request == wordRequest) {
                    annotations = proposed
                    if (inlinePinyin) renderPassage()
                    pendingTap?.let { offset ->
                        pendingTap = null
                        val span = wordAt(offset) ?: return@let
                        if (details.selection != null) select(span.start, span.end)
                    }
                }
            }
        }
    }

    private fun wordAt(offset: Int): ReaderWords.Span? {
        if (offset !in document.text.indices) return null
        val proposed = annotations
        if (proposed != null) {
            val index = proposed.binarySearchBy(offset) { it.span.start }
            return proposed.getOrNull(if (index < 0) -index - 2 else index)?.span?.takeIf { offset < it.end }
        }
        val notes = document.wordNotes
        val supplied = notes.binarySearchBy(offset) { it.start }
        notes.getOrNull(if (supplied < 0) -supplied - 2 else supplied)?.let { note ->
            if (offset < note.end) return note.span
        }
        val index = boundaries.binarySearch(offset).let { if (it < 0) -it - 2 else it }
        val start = boundaries[index]
        return if (Character.UnicodeScript.of(document.text.codePointAt(start)) == Character.UnicodeScript.HAN)
            ReaderWords.Span(start, boundaries[index + 1]) else null
    }

    private fun selectionFor(start: Int, end: Int): TextLibrary.Selection {
        val left = boundaries.binarySearch(start).let { if (it < 0) boundaries[-it - 2] else start }
        val right = boundaries.binarySearch(end).let { if (it < 0) boundaries[-it - 1] else end }
        return library.select(document, left, right)
    }

    private fun select(start: Int, end: Int) {
        pendingTap = null
        openSelection(selectionFor(start, end))
    }

    private fun pickFragment() {
        val text = document.text
        val anchor = offset()
        val point = boundaries.firstOrNull { it >= anchor && it < text.length && Character.isLetterOrDigit(text.codePointAt(it)) }
            ?: boundaries.lastOrNull { it < anchor && Character.isLetterOrDigit(text.codePointAt(it)) }
        if (point == null) {
            android.widget.Toast.makeText(context, "Выберите фрагмент с буквами или цифрами.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val sentence = TextLibrary.sentenceRange(text, point, point + Character.charCount(text.codePointAt(point)))
        chooseFragment(sentence, point)
    }

    fun memorizeSentence(selection: TextLibrary.Selection) {
        if (selection.end - selection.start <= TextRecall.MAX_LENGTH) {
            memorize(selection)
            return
        }
        val point = (selection.start until selection.end).first {
            Character.isLetterOrDigit(document.text.codePointAt(it))
        }
        chooseFragment(ReaderWords.Span(selection.start, selection.end), point)
    }

    private fun chooseFragment(sentence: ReaderWords.Span, point: Int) {
        val start = if (point - sentence.start >= TextRecall.MAX_LENGTH) point else sentence.start
        val selectionStart = boundaries.first { it >= start }
        val selectionEnd = boundaries.last { it <= minOf(sentence.end, selectionStart + TextRecall.MAX_LENGTH) }
        if (selectionEnd <= selectionStart) {
            android.widget.Toast.makeText(context, "Выберите более короткий фрагмент.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        adjust(library.select(document, selectionStart, selectionEnd), "Фрагмент для запоминания",
            "Сохранить фрагмент", TextRecall.MAX_LENGTH, memorize)
    }

    private fun openSelection(selection: TextLibrary.Selection) {
        val span = ReaderWords.Span(selection.start, selection.end)
        passage.highlight = span
        val note = suppliedNotes[span]
        val reading = if (note != null || (annotations != null && wordAt(span.start) == span)) {
            ReaderWordSheet.OccurrenceReading({ choices[span] }) { override ->
                library.choosePinyin(selection, override)
                choices = library.pinyinChoices(document)
                if (inlinePinyin) renderPassage()
            }
        } else null
        details.open(selection, reading, note, { current ->
            pendingTap = null
            adjust(current) { changed -> openSelection(changed) }
        }) {
            pendingTap = null
            passage.highlight = null
        }
    }

    private fun adjust(
        selection: TextLibrary.Selection, title: String = "Изменить границы",
        confirm: String = "Найти слово", maximum: Int = Int.MAX_VALUE,
        chosen: (TextLibrary.Selection) -> Unit
    ) {
        var start = boundaries.binarySearch(selection.start)
        var end = boundaries.binarySearch(selection.end)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val preview = ReadingUi.text(context, "", 23f).apply {
            setPadding(0, dp(4), 0, dp(12))
            maxLines = if (maximum == Int.MAX_VALUE) 3 else 5
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(preview)
        val movements = listOf("Начало влево" to (-1 to 0), "Начало вправо" to (1 to 0),
            "Конец влево" to (0 to -1), "Конец вправо" to (0 to 1))
        fun allowed(left: Int, right: Int) = left >= 0 && right <= boundaries.lastIndex && left < right &&
            boundaries[right] - boundaries[left] <= maximum
        val buttons = mutableListOf<android.widget.Button>()
        fun refresh() {
            preview.text = document.text.substring(boundaries[start], boundaries[end])
            movements.forEachIndexed { index, (_, move) ->
                buttons[index].isEnabled = allowed(start + move.first, end + move.second)
            }
        }
        movements.chunked(2).forEach { row ->
            val controls = LinearLayout(context)
            row.forEachIndexed { index, (label, move) ->
                val button = ReadingUi.button(context, label) {
                    start += move.first
                    end += move.second
                    refresh()
                }
                buttons.add(button)
                controls.addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            column.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        refresh()
        track(MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(column).setNegativeButton("Отмена", null)
            .setPositiveButton(confirm) { _, _ ->
                chosen(library.select(document, boundaries[start], boundaries[end]))
            }.show())
    }

    private fun renderPassage() {
        val words = annotations ?: suppliedAnnotations
        val availableWidth = passage.width - passage.totalPaddingLeft - passage.totalPaddingRight
        if (inlinePinyin && (words.isEmpty() || availableWidth <= 0)) return
        val anchor = offset()
        if (inlinePinyin) {
            val readingSize = sp(style.size / 2f)
            rubyWidth = availableWidth
            passage.text = SpannableStringBuilder(document.text).apply {
                words.forEach { (span, automatic) ->
                    val reading = choices[span] ?: automatic
                    if (reading != null) setSpan(ReaderRubySpan(reading, readingSize, dp(2), availableWidth),
                        span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } else {
            rubyWidth = 0
            passage.text = document.text
        }
        restoreAfterLayout(anchor)
    }

    private fun restoreAfterLayout(anchor: Int) {
        preservedOffset = anchor
        if (restoringOffset) return
        restoringOffset = true
        scroll.doOnNextLayout { placeAnchor() }
    }

    private fun placeAnchor() {
        restoringOffset = true
        val target = preservedOffset ?: 0
        val layout = passage.layout
        val y = if (target == 0 || layout == null) 0
            else passage.top + passage.totalPaddingTop + layout.getLineTop(layout.getLineForOffset(target))
        scroll.scrollTo(0, y)
        restoringOffset = false
        positionChanged()
    }

    private fun positionChanged() {
        updateIndicator()
        handler.removeCallbacks(deferredSave)
        if (!finished && offset() != lastSavedOffset) handler.postDelayed(deferredSave, 500L)
    }

    private fun updateIndicator() {
        val label = positionLabel ?: return
        val scrollable = scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)
        label.visibility = if (scrollable) View.VISIBLE else View.INVISIBLE
        val value = when {
            !scroll.canScrollVertically(-1) -> 0
            !scroll.canScrollVertically(1) -> 100
            else -> (offset() * 100 / document.text.length).coerceIn(0, 99)
        }
        progress?.let {
            it.visibility = if (scrollable) View.VISIBLE else View.INVISIBLE
            it.progress = value
        }
        val display = "$value%"
        if (label.text.toString() != display) {
            label.text = display
            label.contentDescription = "Позиция в тексте $display"
        }
    }

    fun offset(): Int = preservedOffset ?: passage.layout?.let { layout ->
        val top = (scroll.scrollY - passage.top - passage.totalPaddingTop).coerceAtLeast(0)
        layout.getLineStart(layout.getLineForVertical(top))
    } ?: 0

    fun close() {
        returnToLibrary = false
        finishReading()
        dialog.dismiss()
    }

    fun savePosition() {
        handler.removeCallbacks(deferredSave)
        if (finished || (preservedOffset == null && passage.layout == null)) return
        val current = offset()
        if (current == lastSavedOffset) return
        library.saveReadingPosition(document, current)
        lastSavedOffset = current
    }

    private fun finishReading() {
        if (finished) return
        savePosition()
        finished = true
        details.close()
        worker.shutdownNow()
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private class PassageView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
        init {
            setSpannableFactory(object : Spannable.Factory() {
                override fun newSpannable(source: CharSequence): Spannable =
                    source as? SpannableStringBuilder ?: super.newSpannable(source)
            })
        }

        var tap: (Int) -> Unit = {}
        var highlight: ReaderWords.Span? = null
            set(value) { field = value; invalidate() }
        private val highlightPath = Path()
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.chinlern_reading_gold_wash)
        }
        private var hadSelection = false
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (hadSelection) return false
                val layout = layout ?: return false
                val x = event.x - totalPaddingLeft
                val y = (event.y - totalPaddingTop).toInt()
                if (y !in 0 until layout.height) return false
                val line = layout.getLineForVertical(y)
                if (x < layout.getLineLeft(line) || x >= layout.getLineRight(line)) return false
                val caret = layout.getOffsetForHorizontal(line, x)
                val annotated = text as? Spanned
                annotated?.getSpans(caret, caret, ReaderRubySpan::class.java)?.forEach { ruby ->
                    val start = annotated.getSpanStart(ruby)
                    val end = annotated.getSpanEnd(ruby)
                    if (layout.getLineForOffset(start) == line) {
                        val left = layout.getPrimaryHorizontal(start)
                        if (x >= left && x < left + ruby.getSize(paint, annotated, start, end, null)) {
                            tap(start)
                            return true
                        }
                    }
                }
                val start = layout.getLineStart(line)
                val leftHalf = x < layout.getPrimaryHorizontal(caret)
                val rtl = caret < text.length && layout.isRtlCharAt(caret)
                val hit = if (caret > start && (caret == text.length || leftHalf != rtl)) caret - 1 else caret
                tap(hit)
                return true
            }
        })

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) hadSelection = hasSelection()
            val handled = super.onTouchEvent(event)
            detector.onTouchEvent(event)
            return handled
        }

        override fun onDraw(canvas: Canvas) {
            highlight?.let { span ->
                highlightPath.reset()
                layout?.getSelectionPath(span.start, span.end, highlightPath)
                canvas.save()
                canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
                canvas.drawPath(highlightPath, highlightPaint)
                canvas.restore()
            }
            super.onDraw(canvas)
        }
    }
}
