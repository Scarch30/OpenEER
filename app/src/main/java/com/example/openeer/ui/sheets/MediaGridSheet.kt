package com.example.openeer.ui.sheets

import android.app.Dialog
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
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
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao  = db.noteDao(),
            linkDao  = db.blockLinkDao()  // injection inchangée
        )
    }

    private val mediaActions: MediaActions by lazy {
        val host = requireActivity() as AppCompatActivity
        MediaActions(host, blocksRepo)
    }

    /** Set des blockIds liés (Audio/Text partageant un groupId présent dans les deux). */
    private var linkedBlockIds: Set<Long> = emptySet()

    /** blockId -> index de paire (01, 02, …) */
    private var pairIndexByBlockId: Map<Long, Int> = emptyMap()

    /** groupId apparié -> index de paire (pour retrouver la couleur) */
    private var pairIndexByGroupId: Map<String, Int> = emptyMap()

    /** Palette de couleurs pour les bandes (ARGB). */
    private val pairColors: IntArray by lazy {
        intArrayOf(
            0xFF7C4DFF.toInt(), // violet
            0xFF26A69A.toInt(), // teal
            0xFFFF7043.toInt(), // deep orange
            0xFF42A5F5.toInt(), // blue
            0xFFAB47BC.toInt(), // purple
            0xFF66BB6A.toInt(), // green
            0xFFFFCA28.toInt(), // amber
            0xFFEC407A.toInt(), // pink
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val bottomSheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener

            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            bottomSheet.requestLayout()

            val behavior = BottomSheetBehavior.from(bottomSheet)
            val screenH = resources.displayMetrics.heightPixels
            behavior.expandedOffset = (screenH * 0.5f).toInt()
            behavior.skipCollapsed = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.peekHeight = 0
        }
        return dialog
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
            onClick = { item ->
                if (item is MediaStripItem.Audio) {
                    val uriStr = item.mediaUri
                    if (!uriStr.isNullOrBlank()) {
                        AudioQuickPlayerDialog.show(
                            fm = childFragmentManager,
                            id = item.blockId,
                            src = uriStr
                        )
                    }
                } else {
                    mediaActions.handleClick(item)
                }
            },
            onLongClick = { clickedView, item -> mediaActions.showMenu(clickedView, item) },
        )

        recycler.layoutManager = GridLayoutManager(requireContext(), computeSpanCount())
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.addItemDecoration(GridSpacingDecoration(dp(requireContext(), 8)))

        viewLifecycleOwner.lifecycleScope.launch {
            val blocks = blocksRepo.observeBlocks(noteId).first()
            val items = buildItems(blocks, category) // met à jour linkedBlockIds + pairIndex maps
            adapter.submitList(items)
            title.text = getString(
                R.string.media_grid_title_format,
                categoryTitle(category),
                items.size,
            )
        }

        return view
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

    /**
     * Grille :
     *  - PHOTO = photos + vidéos
     *  - AUDIO = audios + textes de transcription (TEXT partageant un groupId d’audio)
     *  - TEXT  = textes indépendants (pas liés à un audio)
     */
    private fun buildItems(blocks: List<BlockEntity>, category: MediaCategory): List<MediaStripItem> {
        val audioGroupIds = blocks.filter { it.type == BlockType.AUDIO }.mapNotNull { it.groupId }.toSet()
        val textGroupIds  = blocks.filter { it.type == BlockType.TEXT  }.mapNotNull { it.groupId }.toSet()

        // groupId appariés (au moins un audio + un texte)
        val pairedGroupIds = audioGroupIds.intersect(textGroupIds)

        linkedBlockIds = blocks.asSequence()
            .filter { (it.type == BlockType.AUDIO || it.type == BlockType.TEXT) && it.groupId != null && it.groupId in pairedGroupIds }
            .map { it.id }
            .toSet()

        // Index de paire stable par groupId
        pairIndexByGroupId = pairedGroupIds.sorted().mapIndexed { idx, gid -> gid to (idx + 1) }.toMap()

        pairIndexByBlockId = blocks.asSequence()
            .filter { (it.type == BlockType.AUDIO || it.type == BlockType.TEXT) && !it.groupId.isNullOrBlank() && it.groupId in pairedGroupIds }
            .associate { it.id to (pairIndexByGroupId[it.groupId!!] ?: 0) }

        val items = blocks.mapNotNull { block ->
            when (category) {
                MediaCategory.PHOTO -> when (block.type) {
                    BlockType.PHOTO, BlockType.VIDEO ->
                        block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                            MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                        }
                    else -> null
                }

                MediaCategory.SKETCH -> block.takeIf { it.type == BlockType.SKETCH }
                    ?.mediaUri?.takeIf { it.isNotBlank() }
                    ?.let { uri -> MediaStripItem.Image(block.id, uri, block.mimeType, block.type) }

                MediaCategory.AUDIO -> {
                    when (block.type) {
                        BlockType.AUDIO ->
                            block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                                MediaStripItem.Audio(block.id, uri, block.mimeType, block.durationMs)
                            }

                        BlockType.TEXT -> {
                            val linkedToAudio = block.groupId != null && block.groupId in audioGroupIds
                            if (linkedToAudio) {
                                MediaStripItem.Text(block.id, block.noteId, block.text.orEmpty())
                            } else null
                        }

                        else -> null
                    }
                }

                MediaCategory.TEXT -> {
                    if (block.type == BlockType.TEXT) {
                        val linkedToAudio = block.groupId != null && block.groupId in audioGroupIds
                        if (!linkedToAudio) {
                            MediaStripItem.Text(block.id, block.noteId, block.text.orEmpty())
                        } else null
                    } else null
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
                    val container = FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                    val image = ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    val play = ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            dp(ctx, 36), dp(ctx, 36),
                            Gravity.CENTER
                        )
                        setImageResource(android.R.drawable.ic_media_play)
                        setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                        alpha = 0.85f
                        isVisible = false
                    }
                    container.addView(image)
                    container.addView(play)
                    card.addView(container)
                    ImageHolder(card, image, play)
                }

                GRID_TYPE_AUDIO -> {
                    val card = createCard(ctx)
                    val root = FrameLayout(ctx)

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

                    // badge 🔗
                    val badge = ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_link_small)
                        layoutParams = FrameLayout.LayoutParams(
                            dp(ctx, 18),
                            dp(ctx, 18),
                            Gravity.TOP or Gravity.END
                        ).apply { setMargins(0, dp(ctx, 6), dp(ctx, 6), 0) }
                        alpha = 0.9f
                        isVisible = false
                    }

                    // compteur "01"
                    val counter = TextView(ctx).apply {
                        textSize = 12f
                        setPadding(dp(ctx, 2), dp(ctx, 1), dp(ctx, 2), dp(ctx, 1))
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END
                        ).apply { setMargins(0, dp(ctx, 6), dp(ctx, 28), 0) }
                        isVisible = false
                    }

                    // bande colorée à GAUCHE pour AUDIO
                    val leftStrip = View(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
                        isVisible = false
                    }

                    root.addView(container)
                    root.addView(badge)
                    root.addView(counter)
                    root.addView(leftStrip)
                    card.addView(root)
                    AudioHolder(card, text, badge, counter, leftStrip)
                }

                GRID_TYPE_TEXT -> {
                    val card = createCard(ctx)
                    val root = FrameLayout(ctx)

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

                    // badge 🔗
                    val badge = ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_link_small)
                        layoutParams = FrameLayout.LayoutParams(
                            dp(ctx, 18),
                            dp(ctx, 18),
                            Gravity.TOP or Gravity.END
                        ).apply { setMargins(0, dp(ctx, 6), dp(ctx, 6), 0) }
                        alpha = 0.9f
                        isVisible = false
                    }

                    // compteur "01"
                    val counter = TextView(ctx).apply {
                        textSize = 12f
                        setPadding(dp(ctx, 2), dp(ctx, 1), dp(ctx, 2), dp(ctx, 1))
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END
                        ).apply { setMargins(0, dp(ctx, 6), dp(ctx, 28), 0) }
                        isVisible = false
                    }

                    // bande colorée à DROITE pour TEXTE
                    val rightStrip = View(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
                        isVisible = false
                    }

                    root.addView(text)
                    root.addView(badge)
                    root.addView(counter)
                    root.addView(rightStrip)
                    card.addView(root)
                    TextHolder(card, text, badge, counter, rightStrip)
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
            private val play: ImageView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Image) {
                Glide.with(image)
                    .load(item.mediaUri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(image)

                play.isVisible = item.type == BlockType.VIDEO

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
            private val badge: ImageView,
            private val counter: TextView,
            private val leftStrip: View,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Audio) {
                duration.text = formatDuration(item.durationMs)

                val isLinked = linkedBlockIds.contains(item.blockId)
                badge.isVisible = isLinked

                val idx = pairIndexByBlockId[item.blockId]
                if (isLinked && idx != null && idx > 0) {
                    counter.isVisible = true
                    counter.text = formatPairIndex(idx)
                    // couleur de paire
                    leftStrip.isVisible = true
                    leftStrip.setBackgroundColor(getPairColorForIndex(idx))
                } else {
                    counter.isVisible = false
                    leftStrip.isVisible = false
                }

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
            private val badge: ImageView,
            private val counter: TextView,
            private val rightStrip: View,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Text) {
                preview.text = item.preview.ifBlank { "…" }

                val isLinked = linkedBlockIds.contains(item.blockId)
                badge.isVisible = isLinked

                val idx = pairIndexByBlockId[item.blockId]
                if (isLinked && idx != null && idx > 0) {
                    counter.isVisible = true
                    counter.text = formatPairIndex(idx)
                    // couleur de paire
                    rightStrip.isVisible = true
                    rightStrip.setBackgroundColor(getPairColorForIndex(idx))
                } else {
                    counter.isVisible = false
                    rightStrip.isVisible = false
                }

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

    private fun formatPairIndex(i: Int): String = i.toString().padStart(2, '0')

    private fun getPairColorForIndex(i: Int): Int =
        pairColors[(i - 1).coerceAtLeast(0) % pairColors.size]

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
