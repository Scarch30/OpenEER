package com.example.openeer.ui.panel.blocks

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.openeer.data.block.BlockEntity
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.LifecycleOwner

object BlockRenderers {
    fun createUnsupportedBlockView(context: Context, block: BlockEntity, margin: Int): View {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = block.type.name
                setPadding(padding, padding, padding, padding)
            })
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun createTextBlockView(
        context: Context,
        block: BlockEntity,
        margin: Int,
        lifecycleOwner: LifecycleOwner,
    ): View {
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()

        val textView = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 16f
            text = block.text.orEmpty()
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(padding, padding, padding, padding)
            addView(textView)
        }

        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(inner)
        }
    }
}
