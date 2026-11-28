package com.example.chinlern.texts

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/** Draws a word and its reading together while retaining the original text and offsets. */
internal class ReaderRubySpan(
    val reading: String,
    private val readingSize: Float,
    private val gap: Int,
    private val availableWidth: Int
) : ReplacementSpan() {
    private class Metrics {
        val paint = Paint()
        val word = Paint.FontMetricsInt()
        val reading = Paint.FontMetricsInt()
    }

    private companion object {
        val scratch = object : ThreadLocal<Metrics>() {
            override fun initialValue() = Metrics()
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            contentDescription = if (reading == "?") "Неоднозначное чтение" else reading
        }
    }

    private fun readingPaint(paint: Paint): Paint = checkNotNull(scratch.get()).paint.apply {
        set(paint)
        textSize = readingSize
        alpha = (paint.alpha * 0.8f).toInt()
    }

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        val annotation = readingPaint(paint)
        fm?.let {
            val metrics = checkNotNull(scratch.get())
            val original = metrics.word
            val ruby = metrics.reading
            paint.getFontMetricsInt(original)
            annotation.getFontMetricsInt(ruby)
            it.ascent = original.ascent - (ruby.descent - ruby.ascent) - gap
            it.top = minOf(original.top, it.ascent + ruby.top - ruby.ascent)
            it.descent = original.descent
            it.bottom = original.bottom
        }
        val width = maxOf(paint.measureText(text, start, end), annotation.measureText(reading))
        return ceil(width.toDouble()).toInt().coerceIn(1, availableWidth)
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int,
        y: Int, bottom: Int, paint: Paint
    ) {
        val width = getSize(paint, text, start, end, null).toFloat()
        val annotation = readingPaint(paint)
        val wordWidth = paint.measureText(text, start, end)
        val rubyWidth = annotation.measureText(reading)
        val rubyBaseline = y + paint.ascent() - gap - annotation.descent()
        canvas.save()
        canvas.translate(x + (width - minOf(wordWidth, width)) / 2, y.toFloat())
        if (wordWidth > width) canvas.scale(width / wordWidth, 1f)
        canvas.drawText(text, start, end, 0f, 0f, paint)
        canvas.restore()
        canvas.save()
        canvas.translate(x + (width - minOf(rubyWidth, width)) / 2, rubyBaseline)
        if (rubyWidth > width) canvas.scale(width / rubyWidth, 1f)
        canvas.drawText(reading, 0f, 0f, annotation)
        canvas.restore()
    }
}
