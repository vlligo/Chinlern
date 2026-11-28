package com.example.chinlern

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.example.chinlern.WordCardUi.dp
import com.google.android.material.button.MaterialButton

/** Shared actions for reader and word sheets, matching the vocabulary screen. */
object DetailUi {
    fun button(context: Context, title: String, action: () -> Unit) = MaterialButton(context).apply {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        text = title; isAllCaps = false; setSingleLine(false); maxLines = Int.MAX_VALUE
        minWidth = 0; minHeight = dp(52); textSize = 16f; cornerRadius = dp(14)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.chinlern_pale_jade))
        setTextColor(ContextCompat.getColor(context, R.color.chinlern_jade))
        setOnClickListener { action() }
    }

    fun icon(
        context: Context,
        @DrawableRes drawable: Int,
        description: String,
        @ColorRes tint: Int = R.color.chinlern_jade,
        action: () -> Unit
    ) = AppCompatImageButton(context).apply {
        val color = ContextCompat.getColor(context, tint)
        setImageResource(drawable)
        imageTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf((color and 0x00FFFFFF) or 0x73000000, color)
        )
        minimumWidth = context.dp(48)
        minimumHeight = context.dp(48)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            setBackgroundResource(ripple.resourceId)
        }
        contentDescription = description
        TooltipCompat.setTooltipText(this, description)
        isFocusable = true
        setOnClickListener { action() }
    }
}
