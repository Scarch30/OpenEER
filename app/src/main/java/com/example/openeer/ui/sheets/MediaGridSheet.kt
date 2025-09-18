package com.example.openeer.ui.sheets

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.panel.media.MediaStripItem
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// --- Constantes & DIFF au top-level (évite un companion dans l'adapter) ---

private const val GRID_TYPE_IMAGE = 1
private const val GRID_TYPE_AUDIO = 2
private const val GRID_TYPE_TEXT  = 3

private val MEDIA_GRID_DIFF = object : DiffUtil.ItemCallback<MediaStripItem>() {
    override fun areItemsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
        oldItem.blockId == newItem.blockId

    override fun areContentsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
        oldItem == newItem
}

class MediaGridSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_CATEGORY = "arg_category"

        fun newInstance(noteId: Long, category: MediaCategory): MediaGridSheet =
            MediaGridSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_ID to noteId,
                    ARG_CATEGORY to category.name,
                )
            }

        fun show(fm: FragmentManager, noteId: Long, category: MediaCategory) {
            newInstance(noteId, category).show(fm, "media_grid_${category.name}")
        }
    }

    private val noteId: Long
        get() = requireArguments().getLong(ARG_NOTE_ID)

    private val category: MediaCategory
        get() {
            val stored = requireArguments().getString(ARG_CATEGORY)
                ?: throw IllegalStateException("Missing category")
            return MediaCategory.valueOf(stored)
        }

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(requireContext())
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private val mediaActions: MediaActions by lazy {
        val host = requireActivity() as AppCompatActivity
        MediaActions(host, blocksRepo)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.bottomsheet_media_grid, container, false)
        val title = view.findViewById<TextView>(R.id.mediaGridTitle)
        val recycler = view.findViewById<RecyclerView>(R.id.mediaGridRecycler)

        title.text = getString(
            R.string.media_grid_title_format,
            categoryTitle(category),
            0,
        )

        val adapter = MediaGridAdapter(
            onClick = { item -> mediaActions.handleClick(item) },
            onLongClick = { clickedView, item -> mediaActions.showMenu(clickedView, item) },
        )

        recycler.layoutManager = GridLayoutManager(requireContext(), computeSpanCount())
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.addItemDecoration(GridSpacingDecoration(dp(requireContext(), 8)))

        viewLifecycleOwner.lifecycleScope.launch {
            val blocks = blocksRepo.observeBlocks(noteId).first()
            val items = buildItems(blocks, category)
            adapter.submitList(items)
            title.text = getString(
                R.string.media_grid_title_format,
                categoryTitle(category),
                items.size,
            )
        }

        return view
    }

    // Forcer l’ouverture en plein écran (développé) et ignorer l’état replié
    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true
            peekHeight = 0
            expandedOffset = 0
        }
    }

    private fun computeSpanCount(): Int {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        return if (widthDp >= 480f) 3 else 2
    }

    private fun categoryTitle(category: MediaCategory): String =
        when (category) {
            MediaCategory.PHOTO -> getString(R.string.media_category_photo)
            MediaCategory.SKETCH -> getString(R.string.media_category_sketch)
            MediaCategory.AUDIO  -> getString(R.string.media_category_audio)
            MediaCategory.TEXT   -> getString(R.string.media_category_text)
        }

    private fun buildItems(blocks: List<BlockEntity>, category: MediaCategory): List<MediaStripItem> {
        val items = blocks.mapNotNull { block ->
            when (category) {
                MediaCategory.PHOTO -> block.takeIf { it.type == BlockType.PHOTO }
                    ?.mediaUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { uri ->
                        MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }

                MediaCategory.SKETCH -> block.takeIf { it.type == BlockType.SKETCH }
                    ?.mediaUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { uri ->
                        MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }

                MediaCategory.AUDIO -> block.takeIf { it.type == BlockType.AUDIO }
                    ?.mediaUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { uri ->
                        MediaStripItem.Audio(block.id, uri, block.mimeType, block.durationMs)
                    }

                MediaCategory.TEXT -> block.takeIf { it.type == BlockType.TEXT }
                    ?.let {
                        MediaStripItem.Text(
                            blockId = block.id,
                            noteId = block.noteId,
                            content = block.text.orEmpty(),
                        )
                    }
            }
        }
        return items.sortedByDescending { it.blockId }
    }

    // --- Adapter grille ---

    private inner class MediaGridAdapter(
        private val onClick: (MediaStripItem) -> Unit,
        private val onLongClick: (View, MediaStripItem) -> Unit,
    ) : ListAdapter<MediaStripItem, RecyclerView.ViewHolder>(MEDIA_GRID_DIFF) {

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is MediaStripItem.Image -> GRID_TYPE_IMAGE
            is MediaStripItem.Audio -> GRID_TYPE_AUDIO
            is MediaStripItem.Text  -> GRID_TYPE_TEXT
            is MediaStripItem.Pile  -> error("Pile items are not supported in the grid")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            return when (viewType) {
                GRID_TYPE_IMAGE -> {
                    val card = createCard(ctx)
                    val image = ImageView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    card.addView(image)
                    ImageHolder(card, image)
                }

                GRID_TYPE_AUDIO -> {
                    val card = createCard(ctx)
                    val container = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        val padding = dp(ctx, 16)
                        setPadding(padding, padding, padding, padding)
                    }
                    val icon = ImageView(ctx).apply {
                        setImageResource(android.R.drawable.ic_btn_speak_now)
                        layoutParams = LinearLayout.LayoutParams(
                            dp(ctx, 36),
                            dp(ctx, 36),
                        )
                    }
                    val text = TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(ctx, 12) }
                        textSize = 16f
                    }
                    container.addView(icon)
                    container.addView(text)
                    card.addView(container)
                    AudioHolder(card, text)
                }

                GRID_TYPE_TEXT -> {
                    val card = createCard(ctx)
                    val text = TextView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        val padding = dp(ctx, 16)
                        setPadding(padding, padding, padding, padding)
                        textSize = 16f
                        maxLines = 6
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    card.addView(text)
                    TextHolder(card, text)
                }

                else -> throw IllegalStateException("Unknown view type $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            when (holder) {
                is ImageHolder -> holder.bind(item as MediaStripItem.Image)
                is AudioHolder -> holder.bind(item as MediaStripItem.Audio)
                is TextHolder  -> holder.bind(item as MediaStripItem.Text)
            }
        }

        inner class ImageHolder(
            private val card: MaterialCardView,
            private val image: ImageView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Image) {
                Glide.with(image)
                    .load(item.mediaUri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(image)

                card.setOnClickListener { onClick(item) }
                card.setOnLongClickListener {
                    onLongClick(it, item)
                    true
                }
            }
        }

        inner class AudioHolder(
            private val card: MaterialCardView,
            private val duration: TextView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Audio) {
                duration.text = formatDuration(item.durationMs)

                card.setOnClickListener { onClick(item) }
                card.setOnLongClickListener {
                    onLongClick(it, item)
                    true
                }
            }
        }

        inner class TextHolder(
            private val card: MaterialCardView,
            private val preview: TextView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Text) {
                preview.text = item.preview.ifBlank { "…" }

                card.setOnClickListener { onClick(item) }
                card.setOnLongClickListener {
                    onLongClick(it, item)
                    true
                }
            }
        }
    }

    // --- UI utils ---

    private fun createCard(ctx: Context): MaterialCardView = MaterialCardView(ctx).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 140),
        )
        radius = dp(ctx, 16).toFloat()
        cardElevation = dp(ctx, 4).toFloat()
        isClickable = true
        isFocusable = true
    }

    private fun formatDuration(durationMs: Long?): String {
        val total = durationMs ?: return "--:--"
        val totalSeconds = total / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private class GridSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            outRect.set(spacing, spacing, spacing, spacing)
        }
    }
}
