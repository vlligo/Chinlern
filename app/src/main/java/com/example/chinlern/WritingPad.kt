package com.example.chinlern

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

class WritingPad(context: Context, initial: String, private val changed: (String) -> Unit) : View(context) {
    constructor(context: Context) : this(context, "", {})
    private val strokes = mutableListOf<MutableList<Pair<Float, Float>>>()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 83, 68); strokeWidth = 5 * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(209, 219, 209); strokeWidth = resources.displayMetrics.density }
    init {
        contentDescription = "Поле для написания пальцем или стилусом. Можно также писать на бумаге."
        isFocusable = true
        try { initial.split(';').filter { it.isNotEmpty() }.forEach { line -> strokes.add(line.split('|').map { p -> p.split(',').let { it[0].toFloat() to it[1].toFloat() } }.toMutableList()) } } catch (_: Exception) { strokes.clear() }
    }
    private fun persist() = changed(strokes.joinToString(";") { line -> line.joinToString("|") { "${it.first},${it.second}" } })
    fun clear() { strokes.clear(); persist(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(249, 249, 240))
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), grid)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, grid)
        canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), grid)
        canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), grid)
        for (line in strokes) {
            if (line.size == 1) canvas.drawPoint(line[0].first * width, line[0].second * height, ink)
            for (i in 1 until line.size) canvas.drawLine(line[i - 1].first * width, line[i - 1].second * height, line[i].first * width, line[i].second * height, ink)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val p = (event.x / width).coerceIn(0f, 1f) to (event.y / height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); strokes.add(mutableListOf(p)) }
            MotionEvent.ACTION_MOVE -> strokes.lastOrNull()?.add(p)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { strokes.lastOrNull()?.add(p); parent.requestDisallowInterceptTouchEvent(false); persist(); performClick() }
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
