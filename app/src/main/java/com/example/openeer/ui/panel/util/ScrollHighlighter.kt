package com.example.openeer.ui.panel.util

import android.view.View
import android.widget.ScrollView

class ScrollHighlighter(private val scrollView: ScrollView) {

    fun scrollToAndFlash(view: View) {
        scrollView.post {
            val density = view.resources.displayMetrics.density
            val offset = (16 * density).toInt()
            val targetY = (view.top - offset).coerceAtLeast(0)
            scrollView.smoothScrollTo(0, targetY)
            flashView(view)
        }
    }

    private fun flashView(view: View) {
        view.animate().cancel()
        view.alpha = 0.5f
        view.animate().alpha(1f).setDuration(350L).start()
    }
}
