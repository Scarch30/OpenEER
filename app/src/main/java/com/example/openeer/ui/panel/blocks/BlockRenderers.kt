package com.example.openeer.ui.panel.blocks

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.example.openeer.data.block.BlockEntity
import com.google.android.material.card.MaterialCardView

object BlockRenderers {
    fun createImageBlockView(context: Context, block: BlockEntity, margin: Int): View {
        val image = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = block.type.name
        }
        val uri = block.mediaUri
        if (!uri.isNullOrBlank()) {
            Glide.with(image).load(uri).into(image)
        } else {
            image.setImageResource(android.R.drawable.ic_menu_report_image)
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
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
            addView(image)
        }
    }

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
}
