package com.example.openeer.ui

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.formatMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Contrôle l'affichage/édition de la "note ouverte" dans MainActivity (notePanel).
 */
class NotePanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {
    private val repo: NoteRepository by lazy {
        val dao = AppDatabase.get(activity).noteDao()
        NoteRepository(dao)
    }

    /** id de la note actuellement ouverte (ou null si aucune) */
    var openNoteId: Long? = null
        private set

    /** Dernière note reçue du flux (pour rendre l'UI rapidement) */
    private var currentNote: Note? = null

    /** MediaPlayer simple géré via SimplePlayer (voir bas de fichier) */

    /**
     * Ouvre visuellement le panneau et commence à observer la note.
     */
    fun open(noteId: Long) {
        openNoteId = noteId
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

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

    /** Reçoit du texte partiel (live) -> on PUSH l'état courant (affichage + DB). */
    fun onAppendLive(displayBody: String) {
        binding.txtBodyDetail.text = displayBody
        val nid = openNoteId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            repo.setBody(nid, displayBody)
        }
    }

    /**
     * Reçoit du texte final.
     * Si addNewline = true, on ajoute juste un '\n' **au texte fourni** puis on remplace l'UI/DB.
     * On NE lit PAS le texte courant pour éviter les doublons.
     */
    fun onReplaceFinal(finalBody: String, addNewline: Boolean) {
        val text = if (addNewline) finalBody + "\n" else finalBody
        binding.txtBodyDetail.text = text
        val nid = openNoteId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) { repo.setBody(nid, text) }
    }

    // ---- Internes ----
    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

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
