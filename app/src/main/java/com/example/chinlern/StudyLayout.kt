package com.example.chinlern

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

internal object StudyLayout {
    fun expanded(widthDp: Int, heightDp: Int, smallestWidthDp: Int) =
        widthDp >= 600 && smallestWidthDp >= 600 && heightDp >= 480

    fun toneColumns(contentWidthDp: Int, fontScale: Float) =
        if (contentWidthDp >= 480 * fontScale) 5 else 3

    fun centered(content: View, maxWidthDp: Int, height: Int = FrameLayout.LayoutParams.WRAP_CONTENT) =
        object : FrameLayout(content.context) {
            private val maxWidth = (maxWidthDp * resources.displayMetrics.density).roundToInt()
            private val centered = LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.CENTER_HORIZONTAL)

            init { addView(content, centered) }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                centered.width = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    maxWidth else available.coerceIn(0, maxWidth)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
}
