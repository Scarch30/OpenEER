package com.example.openeer.ui.panel.media

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.TimeUnit

/**
 * Adapter pour la strip média horizontale.
 * Rendu UNIFORME : toutes les tuiles font 90dp x 90dp.
 *
 * ViewTypes :
 * - IMAGE (photo, sketch)
 * - AUDIO
 * - TEXT (post-it)
 *
 * Callbacks :
 * - onClick(item)
 * - onLongPress(view, item)
 */
class MediaStripAdapter(
    private val onClick: (MediaStripItem) -> Unit,
    private val onLongPress: (View, MediaStripItem) -> Unit,
) : ListAdapter<MediaStripItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_AUDIO = 1
        private const val TYPE_TEXT  = 2

        private val DIFF = object : DiffUtil.ItemCallback<MediaStripItem>() {
            override fun areItemsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem.blockId == newItem.blockId

            override fun areContentsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem == newItem
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).blockId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is MediaStripItem.Image -> TYPE_IMAGE
        is MediaStripItem.Audio -> TYPE_AUDIO
        is MediaStripItem.Text  -> TYPE_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_AUDIO -> createAudioHolder(parent)
            TYPE_TEXT  -> createTextHolder(parent)
            else       -> createImageHolder(parent)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ImageHolder -> holder.bind(getItem(position) as MediaStripItem.Image)
            is AudioHolder -> holder.bind(getItem(position) as MediaStripItem.Audio)
            is TextHolder  -> holder.bind(getItem(position) as MediaStripItem.Text)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImageHolder) {
            Glide.with(holder.image).clear(holder.image)
        }
        super.onViewRecycled(holder)
    }

    // --- Holders ---

    private fun createImageHolder(parent: ViewGroup): ImageHolder {
        val ctx = parent.context
        val card = squareCard(ctx)
        val image = ImageView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            contentDescription = "Image"
        }
        card.addView(image)
        return ImageHolder(card, image)
    }

    private fun createAudioHolder(parent: ViewGroup): AudioHolder {
        val ctx = parent.context
        val card = squareCard(ctx)

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(ctx, 28), dp(ctx, 28)
            )
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Audio"
        }

        val duration = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 4) }
            textSize = 12f
        }

        column.addView(icon)
        column.addView(duration)
        card.addView(column)

        return AudioHolder(card, duration)
    }

    private fun createTextHolder(parent: ViewGroup): TextHolder {
        val ctx = parent.context
        val card = squareCard(ctx)

        // Fond/hiérarchie visuelle "post-it"
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        }

        val badge = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "POST-IT"
            textSize = 10f
            setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
            setBackgroundColor(0xFF9E9E9E.toInt()) // gris moyen
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }

        val preview = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(ctx, 6) }
            textSize = 13f
            maxLines = 3
            isSingleLine = false
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        container.addView(badge)
        container.addView(preview)
        card.addView(container)

        return TextHolder(card, preview)
    }

    // --- View holders ---

    inner class ImageHolder(val card: MaterialCardView, val image: ImageView) :
        RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem.Image) {
            Glide.with(image)
                .load(item.mediaUri)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(image)

            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    inner class AudioHolder(val card: MaterialCardView, private val text: TextView) :
        RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem.Audio) {
            text.text = formatDuration(item.durationMs)
            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    inner class TextHolder(val card: MaterialCardView, private val preview: TextView) :
        RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem.Text) {
            preview.text = item.preview.ifBlank { "…" }
            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    // --- Utils UI ---

    private fun squareCard(ctx: Context): MaterialCardView {
        val size = dp(ctx, 90)
        val margin = dp(ctx, 8)
        return MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                rightMargin = margin
            }
            radius = dp(ctx, 16).toFloat()
            cardElevation = dp(ctx, 4).toFloat()
            isClickable = true
            isFocusable = true
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun formatDuration(durationMs: Long?): String {
        val d = durationMs ?: return "--:--"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(d)
        val minutes = totalSeconds / 60
        val seconds = (totalSeconds % 60)
        return String.format("%02d:%02d", minutes, seconds)
    }
}
