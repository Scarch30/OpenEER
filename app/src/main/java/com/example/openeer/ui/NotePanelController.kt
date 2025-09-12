package com.example.openeer.ui

import android.widget.EditText
import android.widget.ImageView
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
import com.google.android.material.card.MaterialCardView
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.formatMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent
import java.io.File

/**
 * Contrôle l'affichage/édition de la "note ouverte" dans MainActivity (notePanel).
 */
class NotePanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {
    private val repo: NoteRepository by lazy {
        val db = AppDatabase.get(activity)
        NoteRepository(db.noteDao(), db.attachmentDao())
    }

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(activity)
        BlocksRepository(db.blockDao())
    }

    /** id de la note actuellement ouverte (ou null si aucune) */
    var openNoteId: Long? = null
        private set

    /** Dernière note reçue du flux (pour rendre l'UI rapidement) */
    private var currentNote: Note? = null

    private val attachmentAdapter = AttachmentsAdapter(
        onClick = { path ->
            val i = Intent(activity, PhotoViewerActivity::class.java)
            i.putExtra("path", path)
            activity.startActivity(i)
        }
    )

    /**
     * Ouvre visuellement le panneau et commence à observer une note.
     */
    fun open(noteId: Long) {
        openNoteId = noteId
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

        binding.attachmentsStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.attachmentsStrip.adapter = attachmentAdapter

        // Flux des pièces jointes (filtre les fichiers manquants pour éviter vignettes mortes)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.attachments(noteId).collectLatest { list ->
                    val existing = list.filter { File(it.path).exists() && it.type == "photo" }
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

        // Interactions
        binding.btnBack.setOnClickListener { close() }
        binding.txtTitleDetail.setOnClickListener { promptEditTitle() }
        binding.btnPlayDetail.setOnClickListener { togglePlay() }
    }

    /** Ferme le panneau et revient à la liste. */
    fun close() {
        openNoteId = null
        currentNote = null
        binding.notePanel.isGone = true
        binding.recycler.isVisible = true
    }

    /* Affiche le texte partiel (live) – **aucune écriture DB ici*. */
    fun onAppendLive(displayBody: String) {
        binding.txtBodyDetail.text = displayBody
    }

    /**
     * Reçoit du texte final.
     * Si addNewline = true, on ajoute juste un '\n' *au texte fourni* puis on remplace.
     * Ici on met à jour l'UI *et* la base (avec updatedAt géré par le repo).
     */
    fun onReplaceFinal(finalBody: String, addNewline: Boolean) {
        val toAppend = if (addNewline) finalBody + "\n" else finalBody
        val current = binding.txtBodyDetail.text?.toString().orEmpty()
        val newText = current + toAppend
        binding.txtBodyDetail.text = newText
        val nid = openNoteId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            repo.setBody(nid, newText) // le repo met à jour updatedAt
        }
    }

    // ---- Internes ----
    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

    fun observeBlocks(noteId: Long) = blocksRepo.observeBlocks(noteId)

    private fun render(note: Note?) {
        if (note == null) return
        val title = note.title?.takeIf { it.isNotBlank() } ?: "Sans titre"
        binding.txtTitleDetail.text = title

        val bodyShown = note.body.ifBlank { "(transcription en cours…)" }
        binding.txtBodyDetail.text = bodyShown

        val meta = note.formatMeta()
        if (meta.isBlank()) {
            binding.noteMetaFooter.isGone = true
        } else {
            binding.noteMetaFooter.isVisible = true
            binding.noteMetaFooter.text = meta
        }

        val path = note.audioPath
        val playable = !path.isNullOrBlank() && File(path).exists()
        binding.btnPlayDetail.isEnabled = playable
        binding.btnPlayDetail.text = if (playable) "Lecture" else "Lecture"
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

    /** Liste horizontale de vignettes. */
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
        // 🔑 évite la miniature « collée »
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

/** Lecteur audio minimaliste. */
private object SimplePlayer {
    private var mp: android.media.MediaPlayer? = null
    private var playingFlag: Boolean = false

    fun play(
        ctx: android.content.Context,
        path: String,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            if (playingFlag) stopSilently()
            mp = android.media.MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    playingFlag = false
                    onStop()
                }
                prepare()
                start()
            }
            playingFlag = true
            onStart()
        } catch (t: Throwable) {
            playingFlag = false
            stopSilently()
            onError(t)
        }
    }

    private fun stopSilently() {
        try { mp?.stop() } catch (_: Throwable) {}
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
    }
}
