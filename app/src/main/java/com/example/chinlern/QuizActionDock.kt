package com.example.chinlern

import android.content.Context
import android.view.View
import android.widget.ScrollView

/** Bottom controls keep their own scroll range without pushing the question off small windows. */
internal class QuizActionDock(context: Context) : ScrollView(context) {
    init { isFillViewport = false; clipToPadding = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = View.MeasureSpec.getSize(heightMeasureSpec)
        val bounded = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
            heightMeasureSpec
        else View.MeasureSpec.makeMeasureSpec(available * 2 / 3, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, bounded)
    }
}
