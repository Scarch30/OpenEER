package com.example.openeer.ui.sheets

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
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
import com.example.openeer.Injection
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.ui.library.MapPreviewStorage
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.panel.media.MediaStripItem
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val GRID_TYPE_IMAGE = 1
private const val GRID_TYPE_AUDIO = 2
private const val GRID_TYPE_TEXT  = 3
private const val MENU_RENAME = 1
private const val MENU_OPEN_IN_MAPS = 2
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
        Injection.provideBlocksRepository(requireContext())
    }

    private val mediaActions: MediaActions by lazy {
        val host = requireActivity() as AppCompatActivity
        MediaActions(host, blocksRepo).apply {
            onChildLabelChanged = { reloadItems() }
        }
    }

    private var titleView: TextView? = null
    private var mediaGridAdapter: MediaGridAdapter? = null

    // Pour la pile Carte
    private val routeGson = Gson()
    private var mapBlockIds: Set<Long> = emptySet()

    /** Set des blockIds liés (Audio/Text ou Video/Text selon la pile). */
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
                // 🔎 Comportement spécial pour la pile Carte
                if (category == MediaCategory.LOCATION) {
                    MapSnapshotSheet.show(childFragmentManager, item.blockId)
                    return@MediaGridAdapter
                }


                when (item) {
                    is MediaStripItem.Audio -> {
                        val uriStr = item.mediaUri
                        if (!uriStr.isNullOrBlank()) {
                            AudioQuickPlayerDialog.show(
                                fm = childFragmentManager,
                                id = item.blockId,
                                src = uriStr
                            )
                        }
                    }
                    is MediaStripItem.Image -> {
                        if (item.type == BlockType.VIDEO) {
                            val intent = Intent(
                                requireContext(),
                                com.example.openeer.ui.viewer.VideoPlayerActivity::class.java
                            ).apply {
                                putExtra(com.example.openeer.ui.viewer.VideoPlayerActivity.EXTRA_URI, item.mediaUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } else {
                            mediaActions.handleClick(item)
                        }
                    }
                    else -> mediaActions.handleClick(item)
                }
            },
            onLongClick = { clickedView, item ->
                if (category == MediaCategory.LOCATION) {
                    // Long-press = Google Maps
                    showMapsMenuForMapItem(clickedView, item)
                } else {
                    mediaActions.showMenu(clickedView, item)
                }
            },
        )

        recycler.layoutManager = GridLayoutManager(requireContext(), computeSpanCount())
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.addItemDecoration(GridSpacingDecoration(dp(requireContext(), 8)))

        titleView = title
        mediaGridAdapter = adapter

        reloadItems()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mediaActions.onChildLabelChanged = null
        titleView = null
        mediaGridAdapter = null
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
            MediaCategory.LOCATION -> getString(R.string.pile_label_locations)
        }

    private fun reloadItems() {
        val adapter = mediaGridAdapter ?: return
        val title = titleView ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val blocks = withContext(Dispatchers.IO) { blocksRepo.observeBlocks(noteId).first() }
            val items = buildItems(blocks, category) // met à jour linkedBlockIds / pairIndex maps / mapBlockIds
            adapter.submitList(items)
            title.text = getString(
                R.string.media_grid_title_format,
                categoryTitle(category),
                items.size,
            )
        }
    }

    /**
     * Grille :
     *  - PHOTO = photos + vidéos + (TEXT liés à une vidéo)
     *  - AUDIO = audios + textes de transcription (TEXT partageant un groupId d’audio)
     *  - TEXT  = textes indépendants (pas liés à un audio **ni** à une vidéo)
     *  - LOCATION = lieux + itinéraires, affichés en **snapshots** (PNG) si dispo
     */
    private fun buildItems(blocks: List<BlockEntity>, category: MediaCategory): List<MediaStripItem> {
        val audioGroupIds = blocks.filter { it.type == BlockType.AUDIO }.mapNotNull { it.groupId }.toSet()
        val videoGroupIds = blocks.filter { it.type == BlockType.VIDEO }.mapNotNull { it.groupId }.toSet()
        val textGroupIds  = blocks.filter { it.type == BlockType.TEXT  }.mapNotNull { it.groupId }.toSet()

        // Prépare les appariements + couleurs en fonction de la pile
        when (category) {
            MediaCategory.AUDIO -> {
                val paired = audioGroupIds.intersect(textGroupIds)
                linkedBlockIds = blocks.asSequence()
                    .filter { (it.type == BlockType.AUDIO || it.type == BlockType.TEXT) && it.groupId != null && it.groupId in paired }
                    .map { it.id }
                    .toSet()
                val sortedPaired = paired.sorted()
                pairIndexByGroupId = sortedPaired.mapIndexed { idx, gid -> gid to (idx + 1) }.toMap()
                pairIndexByBlockId = blocks.asSequence()
                    .filter { (it.type == BlockType.AUDIO || it.type == BlockType.TEXT) && !it.groupId.isNullOrBlank() && it.groupId in paired }
                    .associate { it.id to (pairIndexByGroupId[it.groupId!!] ?: 0) }
            }
            MediaCategory.PHOTO -> {
                val paired = videoGroupIds.intersect(textGroupIds)
                linkedBlockIds = blocks.asSequence()
                    .filter { (it.type == BlockType.VIDEO || it.type == BlockType.TEXT) && it.groupId != null && it.groupId in paired }
                    .map { it.id }
                    .toSet()
                val sortedPaired = paired.sorted()
                pairIndexByGroupId = sortedPaired.mapIndexed { idx, gid -> gid to (idx + 1) }.toMap()
                pairIndexByBlockId = blocks.asSequence()
                    .filter { (it.type == BlockType.VIDEO || it.type == BlockType.TEXT) && !it.groupId.isNullOrBlank() && it.groupId in paired }
                    .associate { it.id to (pairIndexByGroupId[it.groupId!!] ?: 0) }
            }
            else -> {
                linkedBlockIds = emptySet()
                pairIndexByGroupId = emptyMap()
                pairIndexByBlockId = emptyMap()
            }
        }

        return when (category) {
            MediaCategory.LOCATION -> {
                // 👉 cartes: LOCATION + ROUTE avec preview PNG
                val ctx = requireContext()
                val candidates = blocks.filter { it.type == BlockType.LOCATION || it.type == BlockType.ROUTE }
                mapBlockIds = candidates.map { it.id }.toSet()

                val items = candidates.mapNotNull { block ->
                    val file = MapPreviewStorage.fileFor(ctx, block.id, block.type)
                    if (file.exists()) {
                        // On encode en "image" pour l'adapter (pas d'overlay ▶ car type != VIDEO)
                        MediaStripItem.Image(
                            blockId = block.id,
                            mediaUri = file.absolutePath,
                            mimeType = "image/png",
                            type = block.type,
                            childOrdinal = block.childOrdinal,
                            childName = block.childName,
                        )
                    } else {
                        null // pas de snapshot : on n’affiche pas (fallback recapture géré ailleurs)
                    }
                }
                items.sortedForGrid()
            }

            else -> {
                val items = blocks.mapNotNull { block ->
                    when (category) {
                        MediaCategory.PHOTO -> when (block.type) {
                            BlockType.PHOTO, BlockType.VIDEO ->
                                block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                                    MediaStripItem.Image(
                                        blockId = block.id,
                                        mediaUri = uri,
                                        mimeType = block.mimeType,
                                        type = block.type,
                                        childOrdinal = block.childOrdinal,
                                        childName = block.childName,
                                    )
                                }
                            BlockType.TEXT -> {
                                val linkedToVideo = block.groupId != null && block.groupId in videoGroupIds
                                if (linkedToVideo) MediaStripItem.Text(
                                    blockId = block.id,
                                    noteId = block.noteId,
                                    content = block.text.orEmpty(),
                                    isList = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST,
                                    childOrdinal = block.childOrdinal,
                                    childName = block.childName,
                                ) else null
                            }
                            else -> null
                        }

                        MediaCategory.SKETCH -> block.takeIf { it.type == BlockType.SKETCH }
                            ?.mediaUri?.takeIf { it.isNotBlank() }
                            ?.let { uri ->
                                MediaStripItem.Image(
                                    blockId = block.id,
                                    mediaUri = uri,
                                    mimeType = block.mimeType,
                                    type = block.type,
                                    childOrdinal = block.childOrdinal,
                                    childName = block.childName,
                                )
                            }

                        MediaCategory.AUDIO -> when (block.type) {
                            BlockType.AUDIO ->
                                block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                                    MediaStripItem.Audio(
                                        blockId = block.id,
                                        mediaUri = uri,
                                        mimeType = block.mimeType,
                                        durationMs = block.durationMs,
                                        childOrdinal = block.childOrdinal,
                                        childName = block.childName,
                                    )
                                }
                            BlockType.TEXT -> {
                                val linkedToAudio = block.groupId != null && block.groupId in audioGroupIds
                                if (linkedToAudio) MediaStripItem.Text(
                                    blockId = block.id,
                                    noteId = block.noteId,
                                    content = block.text.orEmpty(),
                                    isList = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST,
                                    childOrdinal = block.childOrdinal,
                                    childName = block.childName,
                                ) else null
                            }
                            else -> null
                        }

                        MediaCategory.TEXT -> {
                            if (block.type == BlockType.TEXT) {
                                val linkedToAudio = block.groupId != null && block.groupId in audioGroupIds
                                val linkedToVideo = block.groupId != null && block.groupId in videoGroupIds
                                if (!linkedToAudio && !linkedToVideo) {
                                    MediaStripItem.Text(
                                        blockId = block.id,
                                        noteId = block.noteId,
                                        content = block.text.orEmpty(),
                                        isList = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST,
                                        childOrdinal = block.childOrdinal,
                                        childName = block.childName,
                                    )
                                } else null
                            } else null
                        }

                        else -> null
                    }
                }
                items.sortedForGrid()
            }
        }
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
                    val root = FrameLayout(ctx)

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

                    // Overlays "liens" / bandes (utiles pour A/V & TEXT), pas pour LOCATION
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
                    val leftStrip = View(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
                        isVisible = false
                    }
                    val label = createChildLabel(ctx)

                    root.addView(image)
                    root.addView(play)
                    root.addView(badge)
                    root.addView(counter)
                    root.addView(leftStrip)
                    root.addView(label)
                    card.addView(root)
                    ImageHolder(card, image, play, badge, counter, leftStrip, label)
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

                    val leftStrip = View(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
                        isVisible = false
                    }
                    val label = createChildLabel(ctx)

                    root.addView(container)
                    root.addView(badge)
                    root.addView(counter)
                    root.addView(leftStrip)
                    root.addView(label)
                    card.addView(root)
                    AudioHolder(card, text, badge, counter, leftStrip, label)
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

                    val badge = ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_link_small)
                        layoutParams = FrameLayout.LayoutParams(
                            dp(ctx, 18),
                            dp(ctx, 18),
                            Gravity.TOP or Gravity.END
                        ).apply { setMargins(0, dp(ctx, 6), dp(ctx, 44), 0) }
                        alpha = 0.9f
                        isVisible = false
                    }

                    val counter = TextView(ctx).apply {
                        textSize = 12f
                        setPadding(dp(ctx, 2), dp(ctx, 1), dp(ctx, 2), dp(ctx, 1))
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END
                        ).apply { setMargins(0, dp(ctx, 6), dp(ctx, 62), 0) }
                        isVisible = false
                    }

                    val rightStrip = View(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
                        isVisible = false
                    }

                    val listBadge = ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_checklist_badge)
                        layoutParams = FrameLayout.LayoutParams(
                            dp(ctx, 20),
                            dp(ctx, 20),
                            Gravity.TOP or Gravity.START
                        ).apply {
                            topMargin = dp(ctx, 6)
                            leftMargin = dp(ctx, 6)
                        }
                        isVisible = false
                    }
                    val label = createChildLabel(ctx)

                    root.addView(text)
                    root.addView(badge)
                    root.addView(counter)
                    root.addView(rightStrip)
                    root.addView(listBadge)
                    root.addView(label)
                    card.addView(root)
                    TextHolder(card, text, badge, counter, rightStrip, listBadge, label)
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

        private fun createChildLabel(ctx: Context): TextView = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
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
            ellipsize = TextUtils.TruncateAt.END
            isVisible = false
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
            val name = item.childName?.trim()?.takeIf { it.isNotEmpty() }
            if (!name.isNullOrEmpty()) return name
            return item.childOrdinal?.takeIf { it > 0 }?.let { "#$it" }
        }

        inner class ImageHolder(
            private val card: MaterialCardView,
            private val image: ImageView,
            private val play: ImageView,
            private val badge: ImageView,
            private val counter: TextView,
            private val leftStrip: View,
            private val label: TextView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Image) {
                Glide.with(image)
                    .load(item.mediaUri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(image)

                // Snapshots carte => jamais "▶"
                play.isVisible = false
                badge.isVisible = false
                counter.isVisible = false
                leftStrip.isVisible = false
                bindChildLabel(label, item)

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
            private val label: TextView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Audio) {
                duration.text = formatDuration(item.durationMs)

                val isLinked = linkedBlockIds.contains(item.blockId)
                badge.isVisible = isLinked

                val idx = pairIndexByBlockId[item.blockId]
                if (isLinked && idx != null && idx > 0) {
                    counter.isVisible = true
                    counter.text = formatPairIndex(idx)
                    leftStrip.isVisible = true
                    leftStrip.setBackgroundColor(getPairColorForIndex(idx))
                } else {
                    counter.isVisible = false
                    leftStrip.isVisible = false
                }

                bindChildLabel(label, item)

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
            private val listBadge: ImageView,
            private val label: TextView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(item: MediaStripItem.Text) {
                preview.text = item.preview.ifBlank { "…" }

                val isLinked = linkedBlockIds.contains(item.blockId)
                badge.isVisible = isLinked

                val idx = pairIndexByBlockId[item.blockId]
                if (isLinked && idx != null && idx > 0) {
                    counter.isVisible = true
                    counter.text = formatPairIndex(idx)
                    rightStrip.isVisible = true
                    rightStrip.setBackgroundColor(getPairColorForIndex(idx))
                } else {
                    counter.isVisible = false
                    rightStrip.isVisible = false
                }

                listBadge.isVisible = item.isList
                bindChildLabel(label, item)

                card.setOnClickListener { onClick(item) }
                card.setOnLongClickListener {
                    onLongClick(it, item)
                    true
                }
            }
        }
    }

    private fun promptRename(item: MediaStripItem) {
        val target = if (item is MediaStripItem.Pile) item.cover else item
        ChildNameDialog.show(
            context = requireContext(),
            initialValue = target.childName,
            onSave = { newName ->
                viewLifecycleOwner.lifecycleScope.launch {
                    blocksRepo.setChildNameForBlock(target.blockId, newName)
                    reloadItems()
                }
            },
            onReset = {
                viewLifecycleOwner.lifecycleScope.launch {
                    blocksRepo.setChildNameForBlock(target.blockId, null)
                    reloadItems()
                }
            },
        )
    }

    // --- Long-press “Google Maps” pour la pile Carte ---
    private fun showMapsMenuForMapItem(anchor: View, item: MediaStripItem) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_RENAME, 0, getString(R.string.media_action_rename))
        popup.menu.add(0, MENU_OPEN_IN_MAPS, 1, getString(R.string.block_open_in_google_maps))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_RENAME -> {
                    promptRename(item)
                    true
                }
                MENU_OPEN_IN_MAPS -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val block = blocksRepo.getBlock(item.blockId)
                        if (block == null) {
                            Toast.makeText(requireContext(), R.string.media_missing_file, Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        openBlockInGoogleMaps(block)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openBlockInGoogleMaps(block: BlockEntity) {
        val ctx = requireContext()
        when (block.type) {
            BlockType.LOCATION -> {
                val lat = block.lat
                val lon = block.lon
                if (lat == null || lon == null) {
                    Toast.makeText(ctx, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
                    return
                }
                val label = block.placeName?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(R.string.block_location_coordinates, lat, lon)
                val encoded = Uri.encode(label)
                val geo = Uri.parse("geo:0,0?q=$lat,$lon($encoded)")
                if (!launchMapsIntent(geo)) {
                    launchWebMaps(lat, lon) || toastMapsUnavailable()
                }
            }
            BlockType.ROUTE -> {
                val payload = block.routeJson?.let {
                    runCatching { routeGson.fromJson(it, RoutePayload::class.java) }.getOrNull()
                }
                val first = payload?.firstPoint()
                val lat = first?.lat ?: block.lat
                val lon = first?.lon ?: block.lon
                if (lat == null || lon == null) {
                    Toast.makeText(ctx, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
                    return
                }
                val label = if (payload != null && payload.pointCount > 0) {
                    ctx.getString(R.string.block_route_points, payload.pointCount)
                } else {
                    ctx.getString(R.string.block_location_coordinates, lat, lon)
                }
                val encoded = Uri.encode(label)
                val geo = Uri.parse("geo:0,0?q=$lat,$lon($encoded)")
                if (!launchMapsIntent(geo)) {
                    launchWebMaps(lat, lon) || toastMapsUnavailable()
                }
            }
            else -> {
                Toast.makeText(ctx, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchMapsIntent(uri: Uri): Boolean {
        val pm = requireContext().packageManager
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return if (intent.resolveActivity(pm) != null) {
            runCatching { startActivity(intent) }.isSuccess
        } else false
    }

    private fun launchWebMaps(lat: Double, lon: Double): Boolean {
        val url = String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lon)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pm = requireContext().packageManager
        return if (intent.resolveActivity(pm) != null) {
            runCatching { startActivity(intent) }.isSuccess
        } else false
    }

    private fun toastMapsUnavailable(): Boolean {
        Toast.makeText(requireContext(), R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
        return false
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

private fun <T : MediaStripItem> List<T>.sortedForGrid(): List<T> =
    this.sortedWith(
        compareBy<T> { it.childOrdinal == null }
            .thenBy { it.childOrdinal ?: Int.MAX_VALUE }
            .thenBy {
                when (it) {
                    is MediaStripItem.Image -> it.blockId
                    is MediaStripItem.Audio -> it.blockId
                    is MediaStripItem.Text  -> it.blockId
                    else -> Long.MAX_VALUE
                }
            }
    )
