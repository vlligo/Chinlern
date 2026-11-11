package com.example.chinlern

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

class WritingPad(context: Context, initial: String, private val changed: (String) -> Unit) : View(context) {
    constructor(context: Context) : this(context, "", {})
    private val strokes = mutableListOf<MutableList<Pair<Float, Float>>>()
    private val paths = mutableListOf<Path>()
    private var drawing = false
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 83, 68); strokeWidth = 5 * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(209, 219, 209); strokeWidth = resources.displayMetrics.density }
    init {
        contentDescription = "Поле для написания пальцем или стилусом. Можно также писать на бумаге."
        isFocusable = true
        try { initial.split(';').filter { it.isNotEmpty() }.forEach { line -> strokes.add(line.split('|').map { p -> p.split(',').let { it[0].toFloat() to it[1].toFloat() } }.toMutableList()) } } catch (_: Exception) { strokes.clear() }
    }
    private fun persist() = changed(strokes.joinToString(";") { line -> line.joinToString("|") { "${it.first},${it.second}" } })
    fun clear() { drawing = false; strokes.clear(); paths.clear(); persist(); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Keep saved coordinates normalized; rebuild pixel paths only when the pad resizes.
        paths.clear()
        strokes.forEach { line ->
            paths.add(Path().apply {
                line.forEachIndexed { index, (x, y) ->
                    if (index == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h)
                }
            })
        }
    }

    private fun appendPoint(x: Float, y: Float) {
        val line = strokes.lastOrNull() ?: return
        val point = (x / width).coerceIn(0f, 1f) to (y / height).coerceIn(0f, 1f)
        if (line.lastOrNull() == point) return
        if (line.isEmpty()) paths.last().moveTo(point.first * width, point.second * height)
        else paths.last().lineTo(point.first * width, point.second * height)
        line.add(point)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(249, 249, 240))
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), grid)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, grid)
        canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), grid)
        canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), grid)
        strokes.forEachIndexed { index, line ->
            if (line.size == 1) canvas.drawPoint(line[0].first * width, line[0].second * height, ink)
            else canvas.drawPath(paths[index], ink)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drawing = true
                strokes.add(mutableListOf()); paths.add(Path())
                appendPoint(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!drawing) return false
                // Android batches samples between frames. Retain them so fast curves
                // do not become straight shortcuts between delivered events.
                for (index in 0 until event.historySize) appendPoint(event.getHistoricalX(index), event.getHistoricalY(index))
                appendPoint(event.x, event.y)
                if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                    drawing = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    persist()
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                }
            }
        }
        postInvalidateOnAnimation()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
