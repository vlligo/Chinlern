package com.example.chinlern.texts

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.chinlern.DetailUi
import com.example.chinlern.R
import com.example.chinlern.WordCardUi
import com.example.chinlern.WordCardUi.dp

internal object ReadingUi {
    fun title(context: Context, value: String, size: Float = 25f) = WordCardUi.title(context, value).apply {
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.chinlern_ink))
    }

    fun text(context: Context, value: String, size: Float = 16f, muted: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (muted) WordCardUi.muted else ContextCompat.getColor(context, R.color.chinlern_ink))
        setLineSpacing(context.dp(3).toFloat(), 1f)
    }

    fun button(context: Context, title: String, primary: Boolean = false, action: () -> Unit) =
        DetailUi.button(context, title, action).apply {
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf())
            val red = ContextCompat.getColor(context, R.color.chinlern_red)
            val jade = ContextCompat.getColor(context, R.color.chinlern_jade)
            val pale = ContextCompat.getColor(context, R.color.chinlern_pale_jade)
            backgroundTintList = ColorStateList(states, intArrayOf(WordCardUi.line, jade, if (primary) red else pale))
            setTextColor(ColorStateList(states, intArrayOf(WordCardUi.muted, Color.WHITE, if (primary) Color.WHITE else jade)))
            insetTop = 0
            insetBottom = 0
        }

    fun icon(context: Context, drawable: Int, description: String, action: () -> Unit) =
        DetailUi.icon(context, drawable, description, action = action)

    fun rule(context: Context) = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.chinlern_gold))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun sheetSurface(context: Context) = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.chinlern_paper))
        val radius = context.dp(24).toFloat()
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }
}
