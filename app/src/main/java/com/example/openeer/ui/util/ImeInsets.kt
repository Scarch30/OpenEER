package com.example.openeer.ui.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

/**
 * Maintient [target] juste au-dessus du clavier (IME).
 * - Ajuste la marge basse du [target] pour suivre la hauteur du clavier.
 * - Bascule la visibilité du [target] selon l’état du clavier.
 *
 * @param root  La vue racine sur laquelle accrocher les callbacks d’insets.
 * @param target La barre d’outils à positionner au-dessus du clavier.
 * @param onVisible Callback optionnel appelé à chaque changement de visibilité de l’IME.
 * @param extraBottom Marge basse supplémentaire en px quand l’IME est visible.
 */
object ImeInsets {

    fun apply(
        root: View,
        target: View,
        onVisible: ((Boolean) -> Unit)? = null,
        extraBottom: Int = 0
    ) {

        fun update(insets: WindowInsetsCompat) {
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val visible = imeInsets.bottom > 0

            // Décale le target au-dessus de l’IME en ajustant la marge basse
            (target.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = if (visible) imeInsets.bottom + extraBottom else 0
                target.layoutParams = lp
            }

            target.isVisible = visible
            onVisible?.invoke(visible)
        }

        // Premier calcul + mise à jour à chaque application des insets
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            update(insets)
            insets
        }

        // Mise à jour fluide pendant l’animation d’apparition/disparition du clavier
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
