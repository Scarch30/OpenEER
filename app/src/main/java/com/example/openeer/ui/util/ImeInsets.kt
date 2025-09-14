package com.example.openeer.ui.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

import androidx.core.view.updateLayoutParams

/**
 * Place une vue *au-dessus* de l’IME :
 * - applique une marge basse = hauteur IME
 * - bascule la visibilité selon l’IME
 */
object ImeInsets {
    fun apply(root: View, target: View, onVisible: ((Boolean) -> Unit)? = null) {
        fun update(insets: WindowInsetsCompat) {
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

            target.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = imeHeight
              
            // Marge basse = hauteur IME pour survoler le clavier
            (target.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = imeHeight
                target.layoutParams = lp
            }
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
