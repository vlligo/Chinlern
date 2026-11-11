package com.example.chinlern

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class WritingPadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ink = Color.rgb(24, 83, 68)

    private fun size(pad: WritingPad, side: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
        pad.measure(spec, spec)
        pad.layout(0, 0, side, side)
    }

    private fun touch(pad: WritingPad, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 10, action, x, y, 0).let { pad.onTouchEvent(it); it.recycle() }
    }

    private fun pixel(pad: WritingPad, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888)
        pad.draw(Canvas(bitmap))
        return bitmap.getPixel(x, y).also { bitmap.recycle() }
    }

    @Test fun batchedCurveSamplesSurviveSavingRestorationAndResize() {
        instrumentation.runOnMainSync {
            var saved = ""
            var saves = 0
            val pad = WritingPad(instrumentation.targetContext, "") { saved = it; saves++ }
            size(pad, 200)
            touch(pad, MotionEvent.ACTION_DOWN, 20f, 20f)
            val move = MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 60f, 100f, 0)
            move.addBatch(30, 100f, 20f, 1f, 1f, 0)
            move.addBatch(40, 140f, 100f, 1f, 1f, 0)
            pad.onTouchEvent(move); move.recycle()
            assertEquals("Moves must not serialize the drawing", 0, saves)
            touch(pad, MotionEvent.ACTION_UP, 180f, 20f)
            assertEquals(1, saves)
            assertEquals("0.1,0.1|0.3,0.5|0.5,0.1|0.7,0.5|0.9,0.1", saved)
            assertEquals(ink, pixel(pad, 60, 100))
            val restored = WritingPad(instrumentation.targetContext, saved) {}
            size(restored, 400)
            assertEquals(ink, pixel(restored, 120, 200))
            size(restored, 200)
            assertEquals(ink, pixel(restored, 60, 100))
            restored.clear()
            assertNotEquals(ink, pixel(restored, 60, 100))
        }
    }

    @Test fun tapsAndLegacyDrawingsRemainVisibleAndCancelDoesNotClick() {
        instrumentation.runOnMainSync {
            var saved = ""
            var clicks = 0
            val pad = WritingPad(instrumentation.targetContext, "") { saved = it }
            pad.setOnClickListener { clicks++ }
            size(pad, 200)
            touch(pad, MotionEvent.ACTION_DOWN, 40f, 60f)
            touch(pad, MotionEvent.ACTION_UP, 40f, 60f)
            assertEquals(1, clicks)
            assertEquals(ink, pixel(pad, 40, 60))
            val legacy = WritingPad(instrumentation.targetContext, "0.2,0.3|0.2,0.3") {}
            size(legacy, 200)
            assertEquals(ink, pixel(legacy, 40, 60))
            touch(pad, MotionEvent.ACTION_DOWN, 100f, 140f)
            touch(pad, MotionEvent.ACTION_CANCEL, 120f, 160f)
            assertEquals(1, clicks)
            assertEquals(2, saved.split(';').size)
            pad.clear()
            assertEquals("", saved)
        }
    }
}
