package com.example.chinlern

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import androidx.viewpager.widget.ViewPager

/** A native pager keeps both live pages laid out throughout dragging and settling. */
internal class SectionSwipeLayout(context: Context) : ViewPager(context) {
    var pagingEnabled = true
    private var blocked = false
    private var captured = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            blocked = !pagingEnabled || horizontalControlAt(this, event.x, event.y)
            captured = false
        }
        // Leave held touches to text selection and library drag-and-drop.
        if (!captured && event.actionMasked == MotionEvent.ACTION_MOVE &&
            event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) blocked = true
        if (blocked || !pagingEnabled) return false
        return super.onInterceptTouchEvent(event).also { if (it) captured = true }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        !blocked && pagingEnabled && super.onTouchEvent(event)

    private fun horizontalControlAt(view: View, x: Float, y: Float): Boolean {
        if (view !== this && (view is EditText || view is SeekBar ||
                view.canScrollHorizontally(-1) || view.canScrollHorizontally(1))) return true
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            val childX = x + view.scrollX - child.left - child.translationX
            val childY = y + view.scrollY - child.top - child.translationY
            if (child.visibility == View.VISIBLE && childX >= 0 && childY >= 0 &&
                childX < child.width && childY < child.height && horizontalControlAt(child, childX, childY)) return true
        }
        return false
    }
}
