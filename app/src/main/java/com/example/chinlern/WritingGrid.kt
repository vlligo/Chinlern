package com.example.chinlern

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Independent normalized strokes per character, retained when the grid reflows. */
class WritingGrid(context: Context, count: Int, initial: String, private val changed: (String) -> Unit) : LinearLayout(context) {
    private val cells = MutableList(count.coerceAtLeast(1)) { "" }
    private var layoutWidth = -1
    private val pads = mutableListOf<WritingPad>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        try {
            if (initial.startsWith("[")) {
                val saved = JSONArray(initial)
                cells.indices.forEach { cells[it] = saved.optString(it, "") }
            } else cells[0] = initial // Preserve the previous single-pad drawing.
        } catch (_: Exception) { cells.indices.forEach { cells[it] = "" } }
    }
    private fun save() = changed(JSONArray(cells).toString())
    fun clear() { pads.forEach { it.clear() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = View.MeasureSpec.getSize(widthMeasureSpec)
        if (available > 0 && available != layoutWidth) {
            layoutWidth = available
            removeAllViews(); pads.clear()
            val gap = dp(12)
            val columns = ((available + gap) / (dp(160) + gap)).coerceIn(1, cells.size)
            val side = ((available - gap * (columns - 1)) / columns).coerceAtMost(dp(260))
            cells.indices.chunked(columns).forEach { indices ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
                indices.forEachIndexed { position, index ->
                    val cell = LinearLayout(context).apply { orientation = VERTICAL }
                    cell.addView(TextView(context).apply { text = "Знак ${index + 1} из ${cells.size}"; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) })
                    val pad = WritingPad(context, cells[index]) { cells[index] = it; save() }
                    pad.contentDescription = "Поле для знака ${index + 1} из ${cells.size}"
                    pads.add(pad)
                    cell.addView(pad, LayoutParams(side, side))
                    row.addView(cell, LayoutParams(side, LayoutParams.WRAP_CONTENT).apply { if (position > 0) marginStart = gap })
                }
                addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
