package com.example.openeer.ui

import android.view.View
import android.widget.EditText
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
import com.example.openeer.ui.formatMeta
import com.example.openeer.ui.SimplePlayer
import com.example.openeer.ui.panel.blocks.BlockRenderers
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripAdapter
import com.example.openeer.ui.panel.media.MediaStripItem
import com.google.android.material.card.MaterialCardView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Contrôle l'affichage de la "note ouverte" dans MainActivity (panel en haut de la liste).
 * - Observe la note + blocs enfants
 * - Met à jour le titre, corps, méta
 * - Expose open()/close()
 *
 * L’édition inline (clavier) est gérée par MainActivity.
 */
class NotePanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {

    private val repo: NoteRepository by lazy {
        val db = AppDatabase.get(activity)
        NoteRepository(db.noteDao(), db.attachmentDao())
    }

    /** id de la note actuellement ouverte (ou null si aucune) */
    var openNoteId: Long? = null
        private set

    /** Dernière note rendue (pour partage, etc.) */
    private var currentNote: Note? = null

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(activity)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private val mediaActions = MediaActions(activity, blocksRepo)

    private var blocksJob: Job? = null
    private val blockViews = mutableMapOf<Long, View>()
    private var pendingHighlightBlockId: Long? = null

    private val mediaAdapter = MediaStripAdapter(
        onClick = { item -> mediaActions.handleClick(item) },
        onLongPress = { view, item -> mediaActions.showMenu(view, item) }
    )

    init {
        binding.mediaStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.mediaStrip.adapter = mediaAdapter
    }

    /** Ouvre visuellement le panneau et commence à observer une note. */
    fun open(noteId: Long) {
        openNoteId = noteId
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

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
        // ⚠️ L’édition du corps (tap sur txtBodyDetail) est gérée dans MainActivity (inline edit).
    }

    /** Ferme le panneau et revient à la liste. */
    fun close() {
        openNoteId = null
        currentNote = null
        binding.notePanel.isGone = true
        binding.recycler.isVisible = true

        // RAZ visuelle pour éviter que le prochain "enterInlineEdit" lise l'ancien texte
        binding.txtBodyDetail.text = "(transcription en cours…)"
        binding.noteMetaFooter.isGone = true

        blocksJob?.cancel()
        blocksJob = null
        blockViews.clear()
        pendingHighlightBlockId = null
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true

        // Stopper toute lecture éventuelle (plus d'UI à mettre à jour ici)
        SimplePlayer.stop { }
    }

    /** Affiche du texte "live" (transcription en cours) — pas d’écriture DB ici. */
    fun onAppendLive(displayBody: String) {
        binding.txtBodyDetail.text = displayBody
    }

    /** Remplace le corps par le texte final et persiste. */
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
                // On n’affiche plus les blocs TEXT dans le corps : tout le texte vit dans Note.body
                BlockType.TEXT -> null
                BlockType.SKETCH, BlockType.PHOTO ->
                    BlockRenderers.createImageBlockView(container.context, block, margin)
                BlockType.VIDEO, BlockType.ROUTE, BlockType.FILE ->
                    BlockRenderers.createUnsupportedBlockView(container.context, block, margin)
                BlockType.AUDIO, BlockType.LOCATION -> null
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

    private fun updateMediaStrip(blocks: List<BlockEntity>) {
        val items = blocks.mapNotNull { block ->
            when (block.type) {
                BlockType.PHOTO, BlockType.SKETCH -> block.mediaUri?.takeIf { it.isNotBlank() }?.let {
                    MediaStripItem.Image(block.id, it, block.mimeType, block.type)
                }
                BlockType.AUDIO -> block.mediaUri?.takeIf { it.isNotBlank() }?.let {
                    MediaStripItem.Audio(block.id, it, block.mimeType, block.durationMs)
                }
                BlockType.TEXT -> {
                    val preview = (block.text ?: "")
                        .lineSequence()
                        .firstOrNull()
                        ?.trim()
                        .orEmpty()
                    MediaStripItem.Text(block.id, preview)
                }
                else -> null
            }
        }
        mediaAdapter.submitList(items)
        binding.mediaStrip.isGone = items.isEmpty()
    }

    // (fabrique de “rectangle texte” non utilisée mais conservée si besoin d’aperçu inline)
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

    // ---- Internes ----

    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

    private fun render(note: Note?) {
        if (note == null) return

        // Titre
        val title = note.title?.takeIf { it.isNotBlank() } ?: "Sans titre"
        binding.txtTitleDetail.text = title

        // Corps
        val bodyShown = note.body.ifBlank { "(transcription en cours…)" }
        binding.txtBodyDetail.text = bodyShown

        // Méta
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
