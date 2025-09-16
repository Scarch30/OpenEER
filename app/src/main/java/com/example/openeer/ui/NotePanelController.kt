package com.example.openeer.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.formatMeta
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Contrôle l'affichage de la "note ouverte" dans MainActivity (panel en haut de la liste).
 * - Observe la note + pièces jointes
 * - Met à jour le titre, corps, méta, bouton lecture
 * - Expose open()/close()
 *
 * L’édition inline (clavier + dessin) est gérée par MainActivity.
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

    /** Dernière note rendue (pour lecture audio, etc.) */
    private var currentNote: Note? = null

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(activity)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private var blocksJob: Job? = null
    private val blockViews = mutableMapOf<Long, View>()
    private var pendingHighlightBlockId: Long? = null

    // Bandeau de pièces jointes
    private val attachmentAdapter = AttachmentsAdapter(
        onClick = { path ->
            val i = Intent(activity, PhotoViewerActivity::class.java)
            i.putExtra("path", path)
            activity.startActivity(i)
        }
    )

    /** Ouvre visuellement le panneau et commence à observer une note. */
    fun open(noteId: Long) {
        openNoteId = noteId
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        blockViews.clear()
        pendingHighlightBlockId = null

        // Bandeau PJ horizontales
        binding.attachmentsStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.attachmentsStrip.adapter = attachmentAdapter

        // Observe PJ (on filtre les fichiers manquants pour éviter les miniatures cassées)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.attachments(noteId).collectLatest { list ->
                    val existing = list.filter { it.type == "photo" && File(it.path).exists() }
                    attachmentAdapter.submit(existing.map { it.path })
                    binding.attachmentsStrip.isGone = existing.isEmpty()
                }
            }
        }

        // Observe la note
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteFlow(noteId).collectLatest { note ->
                    currentNote = note
                    render(note)
                }
            }
        }

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
        binding.btnPlayDetail.setOnClickListener { togglePlay() }
        // ⚠️ L’édition du corps (tap sur txtBodyDetail) est gérée dans MainActivity (inline edit).
    }

    /** Ferme le panneau et revient à la liste. */
    fun close() {
        openNoteId = null
        currentNote = null
        binding.notePanel.isGone = true
        binding.recycler.isVisible = true
        blocksJob?.cancel()
        blocksJob = null
        blockViews.clear()
        pendingHighlightBlockId = null
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        // Option : arrêter lecture si en cours
        SimplePlayer.stop {
            binding.btnPlayDetail.text = "Lecture"
        }
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
        val container = binding.childBlocksContainer
        if (blocks.isEmpty()) {
            container.isGone = true
            container.removeAllViews()
            blockViews.clear()
            return
        }

        container.isVisible = true
        container.removeAllViews()
        blockViews.clear()
        val margin = (8 * container.resources.displayMetrics.density).toInt()

        blocks.forEach { block ->
            val view = when (block.type) {
                BlockType.TEXT -> createTextBlockView(block, margin)
                BlockType.SKETCH, BlockType.PHOTO -> createImageBlockView(block, margin)
                else -> createUnsupportedBlockView(block, margin)
            }
            container.addView(view)
            blockViews[block.id] = view
        }

        pendingHighlightBlockId?.let { tryHighlightBlock(it) }
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

    private fun createImageBlockView(block: BlockEntity, margin: Int): View {
        val ctx = binding.root.context
        val image = ImageView(ctx).apply {
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

        return MaterialCardView(ctx).apply {
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

    private fun createUnsupportedBlockView(block: BlockEntity, margin: Int): View {
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
                text = block.type.name
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

        // Bouton Lecture
        val path = note.audioPath
        val playable = !path.isNullOrBlank() && File(path).exists()
        binding.btnPlayDetail.isEnabled = playable
        binding.btnPlayDetail.text = "Lecture"
    }

    private fun togglePlay() {
        val n = currentNote ?: return
        val path = n.audioPath
        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(activity, "Pas de fichier audio.", Toast.LENGTH_SHORT).show()
            binding.btnPlayDetail.isEnabled = false
            return
        }
        SimplePlayer.play(
            ctx = activity,
            path = path,
            onStart = {
                binding.btnPlayDetail.text = "Pause"
                Toast.makeText(activity, "Lecture…", Toast.LENGTH_SHORT).show()
            },
            onStop = {
                binding.btnPlayDetail.text = "Lecture"
                Toast.makeText(activity, "Lecture terminée", Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                binding.btnPlayDetail.text = "Lecture"
                Toast.makeText(activity, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
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

/** Liste horizontale de vignettes (photos). */
private class AttachmentsAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<AttachmentsAdapter.VH>() {

    private val items = mutableListOf<String>() // chemins absolus

    class VH(val card: MaterialCardView, val img: ImageView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val size = (72 * ctx.resources.displayMetrics.density).toInt()
        val card = MaterialCardView(ctx).apply {
            layoutParams = android.view.ViewGroup.MarginLayoutParams(size, size).apply {
                marginEnd = (8 * ctx.resources.displayMetrics.density).toInt()
            }
            radius = 16f
        }
        val img = ImageView(ctx).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
        card.addView(img)
        return VH(card, img)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val path = items[position]
        // éviter les miniatures fantômes
        holder.img.setImageDrawable(null)
        Glide.with(holder.img).clear(holder.img)
        Glide.with(holder.img)
            .load(File(path))
            .centerCrop()
            .into(holder.img)

        holder.card.setOnClickListener { onClick(path) }
    }

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
