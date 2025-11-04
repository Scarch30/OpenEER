package com.example.openeer.ui.panel.media

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.openeer.R
import com.example.openeer.data.block.BlockType
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.TimeUnit

/**
 * Adapter pour la strip média horizontale.
 * Rendu UNIFORME : toutes les tuiles font 90dp x 90dp.
 *
 * ViewTypes :
 * - IMAGE (photo, sketch, vidéo)  -> vignette + badge pile + overlay ▶ pour VIDEO
 * - AUDIO                         -> icône + durée + badge pile
 * - TEXT                          -> post-it + badge pile
 * - PILE                          -> couverture + badge nombre + overlay ▶ si cover vidéo
 */
class MediaStripAdapter(
    private val onClick: (MediaStripItem) -> Unit,
    private val onPileClick: (MediaCategory) -> Unit,
    private val onLongPress: (View, MediaStripItem) -> Unit,
) : ListAdapter<MediaStripItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_AUDIO = 1
        private const val TYPE_TEXT = 2
        private const val TYPE_PILE = 3
        private const val TYPE_FILE = 4

        private val DIFF = object : DiffUtil.ItemCallback<MediaStripItem>() {
            override fun areItemsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem.blockId == newItem.blockId

            override fun areContentsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem == newItem
        }
    }

    inner class FileHolder(
        val card: MaterialCardView,
        private val preview: TextView,
        private val badge: TextView,
        private val childLabel: TextView,
    ) : RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem) {
            val display = when (item) {
                is MediaStripItem.File -> item
                is MediaStripItem.Pile -> item.cover as? MediaStripItem.File
                else -> null
            }

            if (display == null) {
                preview.text = ""
                bindBadge(badge, item)
                bindChildLabel(childLabel, item)
                return
            }

            preview.text = display.displayName.ifBlank { "…" }
            bindBadge(badge, item)
            bindChildLabel(childLabel, item)

            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).blockId

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is MediaStripItem.Image -> TYPE_IMAGE
        is MediaStripItem.Audio -> TYPE_AUDIO
        is MediaStripItem.Text -> TYPE_TEXT
        is MediaStripItem.File -> TYPE_FILE
        is MediaStripItem.Pile -> TYPE_PILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_AUDIO -> createAudioHolder(parent)
            TYPE_TEXT -> createTextHolder(parent)
            TYPE_FILE -> createFileHolder(parent)
            TYPE_PILE -> createPileHolder(parent)
            else -> createImageHolder(parent)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ImageHolder -> holder.bind(item)
            is AudioHolder -> holder.bind(item)
            is TextHolder -> holder.bind(item)
            is FileHolder -> holder.bind(item)
            is PileHolder -> holder.bind(item as MediaStripItem.Pile)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImageHolder) {
            Glide.with(holder.image).clear(holder.image)
        } else if (holder is PileHolder) {
            Glide.with(holder.image).clear(holder.image)
        }
        super.onViewRecycled(holder)
    }

    // --- Holders ---

    private fun createImageHolder(parent: ViewGroup): ImageHolder {
        val ctx = parent.context
        val card = squareCard(ctx)
        val container = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val image = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            contentDescription = "Image"
        }
        val badge = createBadge(ctx)
        val play = createPlayOverlay(ctx) // ▶ overlay pour VIDEO
        val label = createChildLabel(ctx)

        container.addView(image)
        container.addView(badge)
        container.addView(play)
        container.addView(label)
        card.addView(container)
        return ImageHolder(card, image, badge, play, label)
    }

    private fun createAudioHolder(parent: ViewGroup): AudioHolder {
        val ctx = parent.context
        val card = squareCard(ctx)
        val container = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
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

        val badge = createBadge(ctx)
        val label = createChildLabel(ctx)

        column.addView(icon)
        column.addView(duration)
        container.addView(column)
        container.addView(badge)
        container.addView(label)
        card.addView(container)

        return AudioHolder(card, duration, badge, label)
    }

    private fun createTextHolder(parent: ViewGroup): TextHolder {
        val ctx = parent.context
        val card = squareCard(ctx)
        val container = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Fond/hiérarchie visuelle "post-it"
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        }

        val badgeLabel = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = ctx.getString(R.string.media_text_badge_note)
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

        val pileBadge = createBadge(ctx)
        val childLabel = createChildLabel(ctx)

        val listBadge = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(ctx, 20),
                dp(ctx, 20),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(ctx, 6)
                leftMargin = dp(ctx, 6)
            }
            setImageResource(R.drawable.ic_checklist_badge)
            isVisible = false
        }

        column.addView(badgeLabel)
        column.addView(preview)
        container.addView(column)
        container.addView(pileBadge)
        container.addView(childLabel)
        container.addView(listBadge)
        card.addView(container)

        return TextHolder(card, preview, badgeLabel, pileBadge, listBadge, childLabel)
    }

    private fun createFileHolder(parent: ViewGroup): FileHolder {
        val ctx = parent.context
        val card = squareCard(ctx)
        val container = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        }

        val badgeLabel = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = ctx.getString(R.string.media_text_badge_import)
            textSize = 10f
            setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
            setBackgroundColor(0xFF9E9E9E.toInt())
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

        val pileBadge = createBadge(ctx)
        val childLabel = createChildLabel(ctx)

        column.addView(badgeLabel)
        column.addView(preview)
        container.addView(column)
        container.addView(pileBadge)
        container.addView(childLabel)
        card.addView(container)

        return FileHolder(card, preview, pileBadge, childLabel)
    }

    private fun createPileHolder(parent: ViewGroup): PileHolder {
        val ctx = parent.context
        val card = squareCard(ctx)
        val container = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val image = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            contentDescription = "Image"
            isVisible = false
        }

        val audioLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVisible = false
        }

        val audioIcon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(ctx, 28), dp(ctx, 28)
            )
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Audio"
        }

        val audioDuration = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 4) }
            textSize = 12f
        }
        audioLayout.addView(audioIcon)
        audioLayout.addView(audioDuration)

        val textLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            isVisible = false
        }

        val textLabel = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "POST-IT"
            textSize = 10f
            setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
            setBackgroundColor(0xFF9E9E9E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }

        val textPreview = TextView(ctx).apply {
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

        textLayout.addView(textLabel)
        textLayout.addView(textPreview)

        val badge = createBadge(ctx)
        val play = createPlayOverlay(ctx) // ▶ overlay si cover est vidéo
        val label = createChildLabel(ctx)

        container.addView(image)
        container.addView(audioLayout)
        container.addView(textLayout)
        container.addView(badge)
        container.addView(play)
        container.addView(label)
        card.addView(container)

        return PileHolder(card, image, audioLayout, audioDuration, textLayout, textPreview, badge, play, label)
    }

    // --- View holders ---

    inner class ImageHolder(
        val card: MaterialCardView,
        val image: ImageView,
        private val badge: TextView,
        private val play: ImageView,
        private val label: TextView,
    ) : RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem) {
            val display = when (item) {
                is MediaStripItem.Image -> item
                is MediaStripItem.Pile  -> item.cover as? MediaStripItem.Image
                else -> null
            } ?: return

            Glide.with(image)
                .load(display.mediaUri)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(image)

            bindBadge(badge, item)
            play.isVisible = display.type == BlockType.VIDEO
            bindChildLabel(label, item)

            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    inner class AudioHolder(
        val card: MaterialCardView,
        private val text: TextView,
        private val badge: TextView,
        private val label: TextView,
    ) : RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem) {
            val display = when (item) {
                is MediaStripItem.Audio -> item
                is MediaStripItem.Pile  -> item.cover as? MediaStripItem.Audio
                else -> null
            } ?: return

            text.text = formatDuration(display.durationMs)
            bindBadge(badge, item)
            bindChildLabel(label, item)

            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    inner class TextHolder(
        val card: MaterialCardView,
        private val preview: TextView,
        private val badgeLabel: TextView,
        private val badge: TextView,
        private val listBadge: ImageView,
        private val childLabel: TextView,
    ) : RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem) {
            val display = when (item) {
                is MediaStripItem.Text -> item
                is MediaStripItem.Pile -> item.cover as? MediaStripItem.Text
                else -> null
            }

            if (display == null) {
                preview.text = ""
                badgeLabel.text = card.context.getString(R.string.media_text_badge_note)
                listBadge.isVisible = false
                bindBadge(badge, item)
                bindChildLabel(childLabel, item)
                return
            }

            val ctx = card.context
            preview.text = display.preview.ifBlank { "…" }
            badgeLabel.text = if (display.isList) {
                ctx.getString(R.string.media_text_badge_list)
            } else {
                ctx.getString(R.string.media_text_badge_note)
            }

            listBadge.isVisible = display.isList
            bindBadge(badge, item)
            bindChildLabel(childLabel, item)

            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    inner class PileHolder(
        val card: MaterialCardView,
        val image: ImageView,
        private val audioLayout: LinearLayout,
        private val audioText: TextView,
        private val textLayout: LinearLayout,
        private val textPreview: TextView,
        private val badge: TextView,
        private val play: ImageView,
        private val label: TextView,
    ) : RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem.Pile) {
            badge.text = item.count.toString()
            badge.isVisible = item.count > 0

            Glide.with(image).clear(image)
            image.isVisible = false
            audioLayout.isVisible = false
            textLayout.isVisible = false
            play.isVisible = false

            when (val cover = item.cover) {
                is MediaStripItem.Image -> {
                    image.isVisible = true
                    Glide.with(image)
                        .load(cover.mediaUri)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop()
                        .into(image)
                    play.isVisible = cover.type == BlockType.VIDEO
                }
                is MediaStripItem.Audio -> {
                    audioLayout.isVisible = true
                    audioText.text = formatDuration(cover.durationMs)
                }
                is MediaStripItem.Text -> {
                    textLayout.isVisible = true
                    textPreview.text = cover.preview.ifBlank { "…" }
                }
                is MediaStripItem.File -> {
                    textLayout.isVisible = true
                    textPreview.text = cover.displayName.ifBlank { "…" }
                }
                is MediaStripItem.Pile -> Unit
            }

            bindChildLabel(label, item)

            card.setOnClickListener { onPileClick(item.category) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    // --- Utils UI ---

    private fun squareCard(ctx: Context): MaterialCardView {
        val size = ctx.resources.getDimensionPixelSize(R.dimen.media_strip_item_size)
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

    private fun createBadge(ctx: Context): TextView = TextView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).apply {
            topMargin = dp(ctx, 6)
            rightMargin = dp(ctx, 6)
        }
        setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
        textSize = 12f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0x66000000)
        typeface = Typeface.DEFAULT_BOLD
        isVisible = false
    }

    private fun createPlayOverlay(ctx: Context): ImageView = ImageView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            dp(ctx, 30), dp(ctx, 30),
            Gravity.CENTER
        )
        setImageResource(android.R.drawable.ic_media_play)
        setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        alpha = 0.85f
        isVisible = false
    }

    private fun createChildLabel(ctx: Context): TextView = TextView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.BOTTOM
        ).apply {
            bottomMargin = dp(ctx, 6)
            rightMargin = dp(ctx, 6)
        }
        setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        isVisible = false
    }

    private fun bindBadge(badge: TextView, item: MediaStripItem) {
        val pile = item as? MediaStripItem.Pile
        if (pile != null) {
            badge.text = pile.count.toString()
            badge.isVisible = true
        } else {
            badge.isVisible = false
        }
    }

    private fun bindChildLabel(label: TextView, item: MediaStripItem) {
        val resolved = resolveChildLabel(item)
        if (resolved.isNullOrEmpty()) {
            label.isVisible = false
        } else {
            label.text = resolved
            label.isVisible = true
        }
    }

    private fun resolveChildLabel(item: MediaStripItem): String? {
        val source = when (item) {
            is MediaStripItem.Pile -> item.cover
            else -> item
        }
        val name = source.childName?.trim()?.takeIf { it.isNotEmpty() }
        if (!name.isNullOrEmpty()) return name
        val ordinal = source.childOrdinal
        return ordinal?.takeIf { it > 0 }?.let { "#$it" }
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
