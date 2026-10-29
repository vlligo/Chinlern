package com.example.chinlern

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WritingGridTest {
    private fun pads(view: View): List<WritingPad> = if (view is WritingPad) listOf(view) else if (view is ViewGroup) (0 until view.childCount).flatMap { pads(view.getChildAt(it)) } else emptyList()
    private fun size(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }
    private fun draw(pad: WritingPad, fraction: Float) {
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(0, 1, action, pad.width * fraction, pad.height * fraction, 0)
            pad.onTouchEvent(event); event.recycle()
        }
    }
    @Test fun separateDrawingsSurviveReflowAndRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var saved = ""
            val grid = WritingGrid(instrumentation.targetContext, 2, "") { saved = it }
            size(grid, 1600)
            assertEquals(2, pads(grid).size)
            pads(grid).forEach { assertEquals(it.width, it.height) }
            draw(pads(grid)[0], .25f); draw(pads(grid)[1], .75f)
            val snapshot = saved
            assertNotEquals(JSONArray(saved).getString(0), JSONArray(saved).getString(1))
            size(grid, 400)
            assertEquals(snapshot, saved)
            assertEquals(2, pads(grid).size)
            val restored = WritingGrid(instrumentation.targetContext, 2, saved) { saved = it }
            size(restored, 1600)
            draw(pads(restored)[1], .5f)
            assertEquals(JSONArray(snapshot).getString(0), JSONArray(saved).getString(0))
            assertTrue(JSONArray(saved).getString(1).startsWith(JSONArray(snapshot).getString(1)))
            restored.clear()
            assertEquals("", JSONArray(saved).getString(0)); assertEquals("", JSONArray(saved).getString(1))
        }
    }
}
