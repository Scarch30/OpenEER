package com.example.openeer.ui.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding

/**
 * Helper to keep a view above the IME and report visibility changes.
 */
object ImeInsets {
    fun apply(root: View, target: View, onVisible: ((Boolean) -> Unit)? = null) {
        val update: (WindowInsetsCompat) -> Unit = { insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            target.updatePadding(bottom = imeHeight)
            val visible = imeHeight > 0
            target.isVisible = visible
            onVisible?.invoke(visible)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            update(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    update(insets)
                    return insets
                }
            }
        )
    }
}
