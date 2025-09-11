package com.example.openeer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    // VM / liste
    private val vm: NotesVm by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dao = AppDatabase.get(this@MainActivity).noteDao()
                return NotesVm(NoteRepository(dao)) as T
            }
        }
    }

    private val adapter = NotesAdapter(
        onClick = { note -> onNoteClicked(note) },
        onLongClick = { note -> promptTitle(note) }
    )

    // Repo partagé
    private val repo: NoteRepository by lazy {
        val dao = AppDatabase.get(this).noteDao()
        NoteRepository(dao)
    }

    // Contrôleurs
    private lateinit var notePanel: NotePanelController
    private lateinit var micCtl: MicBarController

    // Permissions
    private val recordPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (!ok) {
            Toast.makeText(this, "Permission micro refusée", Toast.LENGTH_LONG).show()
        }
        // L’enregistrement est géré côté MicBarController à l’appui.
    }

    private fun hasRecordPerm(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Liste
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        lifecycleScope.launch {
            vm.notes.collectLatest { adapter.submitList(it) }
        }

        // Note panel controller (panneau "note ouverte")
        notePanel = NotePanelController(this, b)

        // Mic controller (push-to-talk + mains libres)
        micCtl = MicBarController(
            activity = this,
            binding = b,
            repo = repo,
            getOpenNoteId = { notePanel.openNoteId },
            onAppendLive = { body -> notePanel.onAppendLive(body) },
            onReplaceFinal = { body, addNewline -> notePanel.onReplaceFinal(body, addNewline) }
        )

        // Geste micro : créer une note immédiatement si aucune note n’est ouverte
        b.btnMicBar.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    // Permission micro si pas accordée (ne bloque pas la création)
                    if (!hasRecordPerm()) {
                        recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    // Si aucune note ouverte, on en crée une tout de suite et on ouvre le panneau
                    if (notePanel.openNoteId == null) {
                        lifecycleScope.launch {
                            val newId = withContext(Dispatchers.IO) {
                                // Création immédiate, corps placeholder
                                repo.createTextNote(body = "(transcription en cours…)")
                            }
                            Toast.makeText(this@MainActivity, "Note créée (#$newId)", Toast.LENGTH_SHORT).show()
                            notePanel.open(newId)

                            // 🌍 Enrichissement lieu en arrière-plan (non bloquant)
                            lifecycleScope.launch(Dispatchers.IO) {
                                val place = runCatching { getOneShotPlace(this@MainActivity) }.getOrNull()
                                if (place != null) {
                                    repo.updateLocation(
                                        id = newId,
                                        lat = place.lat,
                                        lon = place.lon,
                                        place = place.label,
                                        accuracyM = place.accuracyM
                                    )
                                }
                            }

                            // Puis on laisse le touch continuer vers le MicBarController
                            micCtl.beginPress(initialX = ev.x)
                        }
                        return@setOnTouchListener true
                    } else {
                        // Une note est déjà ouverte : on démarre tout de suite le PTT
                        micCtl.beginPress(initialX = ev.x)
                        return@setOnTouchListener true
                    }
                }
            }
            // Délègue les mouvements/relâchements au mic controller
            micCtl.onTouch(ev)
            true
        }

        // Boutons bas
        b.btnKeyboard.setOnClickListener {
            ensureOpenNote()
            Toast.makeText(this, "Saisie clavier à venir (note déjà ouverte).", Toast.LENGTH_SHORT).show()
        }
        b.btnPhoto.setOnClickListener {
            ensureOpenNote()
            Toast.makeText(this, "Capture photo à venir (note déjà ouverte).", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureOpenNote() {
        if (notePanel.openNoteId != null) return
        lifecycleScope.launch {
            val newId = withContext(Dispatchers.IO) { repo.createTextNote("(transcription en cours…)") }
            Toast.makeText(this@MainActivity, "Note créée (#$newId)", Toast.LENGTH_SHORT).show()
            notePanel.open(newId)

            // 🌍 enrichissement lieu non bloquant
            lifecycleScope.launch(Dispatchers.IO) {
                val place = runCatching { getOneShotPlace(this@MainActivity) }.getOrNull()
                if (place != null) {
                    repo.updateLocation(
                        id = newId,
                        lat = place.lat,
                        lon = place.lon,
                        place = place.label,
                        accuracyM = place.accuracyM
                    )
                }
            }
        }
    }

    // ---------- Ouvrir une note existante (depuis la liste) ----------
    private fun onNoteClicked(note: Note) {
        notePanel.open(note.id)
    }

    // ---------- Dialog (édition rapide du titre depuis la liste) ----------
    private fun promptTitle(note: Note) {
        val input = EditText(this).apply {
            hint = "Titre (facultatif)"
            setText(note.title ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Définir le titre")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val t = input.text?.toString()?.trim()
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.setTitle(note.id, t?.ifBlank { null })
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}

class NotesVm(private val repo: NoteRepository) : ViewModel() {
    val notes = repo.allNotes
}
