package com.example.chinlern

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

internal object WordCardUi {
    val muted = Color.rgb(105, 109, 99)
    val line = Color.rgb(231, 224, 209)

    fun surface(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = context.dp(18).toFloat()
            setStroke(context.dp(1), line)
        }
        val padding = context.dp(22)
        setPadding(padding, padding, padding, padding)
    }

    fun title(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 25f
        setTextColor(ContextCompat.getColor(context, R.color.chinlern_jade))
        setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(context.dp(3).toFloat(), 1f)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    fun titleRow(title: View, pronunciation: View) = LinearLayout(title.context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val target = context.dp(48)
        addView(pronunciation, LinearLayout.LayoutParams(target, target).apply {
            gravity = Gravity.TOP
            marginStart = context.dp(4)
        })
    }

    fun LinearLayout.append(view: View, gap: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(gap) })
    }

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
