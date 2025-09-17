package com.example.openeer.ui.panel.media

import android.content.Context
import android.media.MediaMetadataRetriever
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
import com.example.openeer.data.block.BlockType
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.TimeUnit

sealed class MediaStripItem {
    abstract val blockId: Long
    abstract val mediaUri: String
    abstract val mimeType: String?

    data class Image(
        override val blockId: Long,
        override val mediaUri: String,
        override val mimeType: String?,
        val type: BlockType,
    ) : MediaStripItem()

    data class Audio(
        override val blockId: Long,
        override val mediaUri: String,
        override val mimeType: String?,
        val durationMs: Long?,
    ) : MediaStripItem()
}

class MediaStripAdapter(
    private val onClick: (MediaStripItem) -> Unit,
    private val onLongPress: (View, MediaStripItem) -> Unit,
) : ListAdapter<MediaStripItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_AUDIO = 1

        private val DIFF = object : DiffUtil.ItemCallback<MediaStripItem>() {
            override fun areItemsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem.blockId == newItem.blockId

            override fun areContentsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem == newItem
        }
    }

    private val durationCache = mutableMapOf<Long, String>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).blockId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is MediaStripItem.Audio -> TYPE_AUDIO
        is MediaStripItem.Image -> TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_AUDIO -> createAudioHolder(parent)
            else -> createImageHolder(parent)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ImageHolder -> holder.bind(getItem(position) as MediaStripItem.Image)
            is AudioHolder -> holder.bind(getItem(position) as MediaStripItem.Audio)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImageHolder) {
            Glide.with(holder.image).clear(holder.image)
        }
        super.onViewRecycled(holder)
    }

    override fun submitList(list: List<MediaStripItem>?) {
        if (list == null) {
            durationCache.clear()
        } else {
            val ids = list.map { it.blockId }.toSet()
            durationCache.keys.retainAll(ids)
        }
        super.submitList(list?.let { ArrayList(it) })
    }

    private fun createImageHolder(parent: ViewGroup): ImageHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val size = (160 * density).toInt()
        val margin = (8 * density).toInt()
        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                marginEnd = margin
            }
            radius = 20f
            cardElevation = 6f
            isClickable = true
            isFocusable = true
        }
        val image = ImageView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
        card.addView(image)
        return ImageHolder(card, image)
    }

    private fun createAudioHolder(parent: ViewGroup): AudioHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val height = (48 * density).toInt()
        val horizontalPadding = (16 * density).toInt()
        val verticalPadding = (8 * density).toInt()
        val margin = (8 * density).toInt()

        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                height
            ).apply {
                marginEnd = margin
            }
            radius = 40f
            cardElevation = 4f
            isClickable = true
            isFocusable = true
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
            setImageResource(android.R.drawable.ic_btn_speak_now)
        }

        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (8 * density).toInt()
            }
            textSize = 14f
        }

        row.addView(icon)
        row.addView(text)
        card.addView(row)

        return AudioHolder(card, text)
    }

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
            text.text = resolveDuration(card.context, item)
            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    private fun resolveDuration(context: Context, item: MediaStripItem.Audio): String {
        durationCache[item.blockId]?.let { return it }

        val label = item.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) }
            ?: extractDuration(context, item.mediaUri)
            ?: "--:--"

        durationCache[item.blockId] = label
        return label
    }

    private fun extractDuration(context: Context, rawUri: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(rawUri)
            when {
                uri.scheme.isNullOrEmpty() -> retriever.setDataSource(rawUri)
                uri.scheme.equals("content", ignoreCase = true) ||
                        uri.scheme.equals("file", ignoreCase = true) -> retriever.setDataSource(context, uri)
                else -> retriever.setDataSource(rawUri)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            duration?.let { formatDuration(it) }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
