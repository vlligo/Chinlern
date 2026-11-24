package com.example.chinlern

internal object StudyLayout {
    fun expanded(widthDp: Int, heightDp: Int, smallestWidthDp: Int) =
        widthDp >= 600 && smallestWidthDp >= 600 && heightDp >= 480

    fun toneColumns(contentWidthDp: Int, fontScale: Float) =
        if (contentWidthDp >= 480 * fontScale) 5 else 3
}
