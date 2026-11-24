package com.example.chinlern

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.BackEventCompat

/** Property-only motion: no screenshots, extra layouts, or delayed navigation. */
internal class BackNavigationMotion(
    private val enabled: () -> Boolean = {
        Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    }
) {
    private var preview: View? = null
    private var entering: View? = null
    var inProgress = false
        private set

    fun start(view: View) {
        reset()
        inProgress = true
        preview = view
    }

    fun progress(event: BackEventCompat) {
        val view = preview ?: return
        if (!enabled()) return
        val progress = event.progress.coerceIn(0f, 1f)
        val direction = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1
        view.translationX = direction * 24f * view.resources.displayMetrics.density * progress
        view.scaleX = 1f - .045f * progress
        view.scaleY = view.scaleX
        view.alpha = 1f - .08f * progress
    }

    fun cancel() {
        inProgress = false
        preview?.let { view ->
            if (enabled()) view.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
            else restore(view)
        }
    }

    fun enter(view: View) {
        reset()
        if (!enabled()) return
        entering = view
        view.translationX = -24f * view.resources.displayMetrics.density
        view.scaleX = .985f
        view.scaleY = .985f
        view.alpha = .85f
        view.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    fun reset() {
        preview?.let(::restore)
        entering?.let(::restore)
        preview = null
        entering = null
        inProgress = false
    }

    private fun restore(view: View) {
        view.animate().cancel()
        view.translationX = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
    }
}
