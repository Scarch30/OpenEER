package com.example.openeer.ui.util

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Aligne la hauteur de `this` sur celle de `target`.
 * Si `target` n'est pas encore mesurée, on retente au prochain frame.
 */
fun View.matchHeightTo(target: View) {
    val h = if (target.height > 0) target.height else target.measuredHeight
    if (h <= 0) {
        // cible pas encore prête : on retente juste après le layout
        target.post { matchHeightTo(target) }
        return
    }
    val lp = when (val cur = layoutParams) {
        is FrameLayout.LayoutParams -> cur.apply { height = h }
        is LinearLayout.LayoutParams -> cur.apply { height = h }
        is ViewGroup.MarginLayoutParams -> cur.apply { height = h }
        else -> ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }
    layoutParams = lp
    requestLayout()
}
