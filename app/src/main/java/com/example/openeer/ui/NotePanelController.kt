// app/src/main/java/com/example/openeer/ui/NotePanelController.kt
package com.example.openeer.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.SimplePlayer
import com.example.openeer.ui.formatMeta
import com.example.openeer.ui.panel.blocks.BlockRenderers
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.panel.media.MediaStripAdapter
import com.example.openeer.ui.panel.media.MediaStripItem
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NotePanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {

    private val repo: NoteRepository by lazy {
        val db = AppDatabase.get(activity)
        NoteRepository(db.noteDao(), db.attachmentDao())
    }

    var openNoteId: Long? = null
        private set

    private var currentNote: Note? = null

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(activity)
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao  = db.noteDao(),
            linkDao  = db.blockLinkDao()   // ✅ liens AUDIO→TEXTE disponibles
        )
    }

    private val mediaActions = MediaActions(activity, blocksRepo)

    private var blocksJob: Job? = null
    private val blockViews = mutableMapOf<Long, View>()
    private var pendingHighlightBlockId: Long? = null

    private val mediaAdapter = MediaStripAdapter(
        onClick = { item -> mediaActions.handleClick(item) },
        onPileClick = { category ->
            openNoteId?.let { noteId ->
                mediaActions.handlePileClick(noteId, category)
            }
        },
        onLongPress = { view, item -> mediaActions.showMenu(view, item) }
    )

    init {
        binding.mediaStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.mediaStrip.adapter = mediaAdapter
    }

    fun open(noteId: Long) {
        openNoteId = noteId
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

        // Reset visuel
        binding.txtBodyDetail.text = ""
        binding.noteMetaFooter.isGone = true
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        blockViews.clear()
        pendingHighlightBlockId = null
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true

        // Observe la note
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteFlow(noteId).collectLatest { note ->
                    currentNote = note
                    render(note)
                }
            }
        }

        // Observe les blocs
        blocksJob?.cancel()
        blocksJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                blocksRepo.observeBlocks(noteId).collectLatest { blocks ->
                    renderBlocks(blocks)
                }
            }
        }

        // Interactions locales
        binding.btnBack.setOnClickListener { close() }
        binding.txtTitleDetail.setOnClickListener { promptEditTitle() }
    }

    fun close() {
        openNoteId = null
        currentNote = null
        binding.notePanel.isGone = true
        binding.recycler.isVisible = true

        binding.txtBodyDetail.text = ""
        binding.noteMetaFooter.isGone = true

        blocksJob?.cancel()
        blocksJob = null
        blockViews.clear()
        pendingHighlightBlockId = null
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true

        SimplePlayer.stop { }
    }

    fun onAppendLive(displayBody: String) {
        binding.txtBodyDetail.text = displayBody
    }

    fun onReplaceFinal(finalBody: String, addNewline: Boolean) {
        val current = binding.txtBodyDetail.text?.toString().orEmpty()
        val toAppend = if (addNewline) finalBody + "\n" else finalBody
        val newText = current + toAppend
        binding.txtBodyDetail.text = newText
        val nid = openNoteId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            repo.setBody(nid, newText)
        }
    }

    fun highlightBlock(blockId: Long) {
        if (openNoteId == null) return
        pendingHighlightBlockId = blockId
        if (!tryHighlightBlock(blockId)) {
            // Le bloc sera mis en évidence lors du prochain rendu des enfants.
        }
    }

    private fun renderBlocks(blocks: List<BlockEntity>) {
        updateMediaStrip(blocks)

        val container = binding.childBlocksContainer
        container.removeAllViews()
        blockViews.clear()

        if (blocks.isEmpty()) {
            container.isGone = true
            return
        }

        val margin = (8 * container.resources.displayMetrics.density).toInt()
        var hasRenderable = false

        blocks.forEach { block ->
            val view = when (block.type) {
                BlockType.TEXT -> null
                BlockType.SKETCH, BlockType.PHOTO, BlockType.VIDEO, BlockType.AUDIO -> null
                BlockType.ROUTE, BlockType.FILE ->
                    BlockRenderers.createUnsupportedBlockView(container.context, block, margin)
                BlockType.LOCATION -> null
            }
            if (view != null) {
                hasRenderable = true
                container.addView(view)
                blockViews[block.id] = view
            }
        }

        container.isGone = !hasRenderable
        if (hasRenderable) {
            pendingHighlightBlockId?.let { tryHighlightBlock(it) }
        }
    }

    /**
     * Piles médias :
     *  - PHOTO = photos + vidéos
     *  - AUDIO = audios + textes de transcription (TEXT partageant le groupId d’un audio)
     *  - TEXT  = textes indépendants (pas liés à un audio)
     */
    private fun updateMediaStrip(blocks: List<BlockEntity>) {
        val audioGroupIds = blocks
            .filter { it.type == BlockType.AUDIO }
            .mapNotNull { it.groupId }
            .toSet()

        val photoItems = mutableListOf<MediaStripItem.Image>()
        val sketchItems = mutableListOf<MediaStripItem.Image>()
        val audioItems  = mutableListOf<MediaStripItem.Audio>()
        val textItems   = mutableListOf<MediaStripItem.Text>()
        var transcriptsLinkedToAudio = 0

        blocks.forEach { block ->
            when (block.type) {
                BlockType.PHOTO, BlockType.VIDEO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        photoItems += MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }
                }
                BlockType.SKETCH -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        sketchItems += MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }
                }
                BlockType.AUDIO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        audioItems += MediaStripItem.Audio(block.id, uri, block.mimeType, block.durationMs)
                    }
                }
                BlockType.TEXT -> {
                    val linkedToAudio = block.groupId != null && block.groupId in audioGroupIds
                    if (linkedToAudio) {
                        transcriptsLinkedToAudio += 1
                    } else {
                        textItems += MediaStripItem.Text(
                            blockId = block.id,
                            noteId = block.noteId,
                            content = block.text.orEmpty(),
                        )
                    }
                }
                else -> Unit
            }
        }

        val piles = buildList {
            if (photoItems.isNotEmpty()) {
                val sorted = photoItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.PHOTO, sorted.size, sorted.first()))
            }
            if (sketchItems.isNotEmpty()) {
                val sorted = sketchItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.SKETCH, sorted.size, sorted.first()))
            }
            if (audioItems.isNotEmpty()) {
                val sorted = audioItems.sortedByDescending { it.blockId }
                val countWithTranscripts = sorted.size + transcriptsLinkedToAudio
                add(MediaStripItem.Pile(MediaCategory.AUDIO, countWithTranscripts, sorted.first()))
            }
            if (textItems.isNotEmpty()) {
                val sorted = textItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.TEXT, sorted.size, sorted.first()))
            }
        }.sortedByDescending { it.cover.blockId }

        mediaAdapter.submitList(piles)
        binding.mediaStrip.isGone = piles.isEmpty()
    }

    private fun createTextBlockView(block: BlockEntity, margin: Int): View {
        val ctx = binding.root.context
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()
        return MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(TextView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = block.text?.trim().orEmpty()
                textSize = 16f
                setPadding(padding, padding, padding, padding)
            })
        }
    }

    private fun tryHighlightBlock(blockId: Long): Boolean {
        val view = blockViews[blockId] ?: return false
        binding.scrollBody.post {
            val density = view.resources.displayMetrics.density
            val offset = (16 * density).toInt()
            val targetY = (view.top - offset).coerceAtLeast(0)
            binding.scrollBody.smoothScrollTo(0, targetY)
            flashView(view)
        }
        pendingHighlightBlockId = null
        return true
    }

    private fun flashView(view: View) {
        view.animate().cancel()
        view.alpha = 0.5f
        view.animate().alpha(1f).setDuration(350L).start()
    }

    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

    private fun render(note: Note?) {
        if (note == null) return

        val title = note.title?.takeIf { it.isNotBlank() } ?: "Sans titre"
        binding.txtTitleDetail.text = title

        val keepCurrentStyled =
            (binding.txtBodyDetail.text is Spanned) &&
                    (binding.txtBodyDetail.text as Spanned).getSpans(
                        0,
                        binding.txtBodyDetail.text.length, StyleSpan::class.java
                    ).any { it.style == Typeface.ITALIC }

        if (!keepCurrentStyled) {
            binding.txtBodyDetail.text = note.body
        }

        val meta = note.formatMeta()
        if (meta.isBlank()) {
            binding.noteMetaFooter.isGone = true
        } else {
            binding.noteMetaFooter.isVisible = true
            binding.noteMetaFooter.text = meta
        }
    }

    private fun promptEditTitle() {
        val note = currentNote ?: return
        val input = EditText(activity).apply {
            hint = "Titre (facultatif)"
            setText(note.title ?: "")
        }
        AlertDialog.Builder(activity)
            .setTitle("Définir le titre")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val t = input.text?.toString()?.trim()
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    repo.setTitle(note.id, t?.ifBlank { null })
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
