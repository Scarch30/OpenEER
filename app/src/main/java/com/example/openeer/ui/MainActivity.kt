package com.example.openeer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.content.Intent
import com.example.openeer.ui.KeyboardCaptureActivity

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    // VM / liste
    private val vm: NotesVm by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = AppDatabase.get(this@MainActivity)
                return NotesVm(NoteRepository(db.noteDao(), db.attachmentDao())) as T
            }
        }
    }

    private val adapter = NotesAdapter(
        onClick = { note -> onNoteClicked(note) },
        onLongClick = { note -> promptTitle(note) }
    )

    // Repo partagé
    private val repo: NoteRepository by lazy {
        val db = AppDatabase.get(this)
        NoteRepository(db.noteDao(), db.attachmentDao())
    }

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    // Contrôleurs
    private lateinit var notePanel: NotePanelController
    private lateinit var micCtl: MicBarController

    // chemin temporaire utilisé par TakePicture()
    private var tempPhotoPath: String? = null

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = tempPhotoPath
        val nid = notePanel.openNoteId
        if (ok && path != null && nid != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                repo.addPhoto(nid, path)
                blocksRepo.appendPhoto(nid, path, mimeType = "image/*")
            }
        } else if (path != null) {
            File(path).delete()
        }
        tempPhotoPath = null
    }

    private val keyboardCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val noteId = data?.getLongExtra("noteId", -1L) ?: -1L
            if (noteId > 0) {
                notePanel.open(noteId)
            }
            val addedText = data?.getBooleanExtra("addedText", false) ?: false
            val addedSketch = data?.getBooleanExtra("addedSketch", false) ?: false
            val msg = when {
                addedText && addedSketch -> getString(R.string.msg_capture_added, 2)
                addedSketch -> getString(R.string.msg_sketch_added)
                addedText -> getString(R.string.msg_block_text_added)
                else -> null
            }
            msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val nid = notePanel.openNoteId ?: return@registerForActivityResult
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val dir = File(filesDir, "images").apply { mkdirs() }
                val dest = File(dir, "img_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                repo.addPhoto(nid, dest.absolutePath)
                blocksRepo.appendPhoto(nid, dest.absolutePath, mimeType = "image/*")
            }
        }
    }

    private val readMediaPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            openGallery()
        } else {
            Toast.makeText(this, "Permission galerie refusée", Toast.LENGTH_LONG).show()
        }
    }

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

        // ❌ on ne nettoie plus le cache des images ici — sinon les photos “disparaissent”
        // if (savedInstanceState == null) {
        //     File(cacheDir, "images").listFiles()?.forEach { it.delete() }
        // } else {
        //     tempPhotoPath = savedInstanceState.getString("tempPhotoPath")
        // }
        tempPhotoPath = savedInstanceState?.getString("tempPhotoPath")

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
        notePanel = NotePanelController(this, b) { mode, nid, focus ->
            launchKeyboardCapture(mode, nid, focus)
        }

        // Mic controller (push-to-talk + mains libres)
        micCtl = MicBarController(
            activity = this,
            binding = b,
            repo = repo,
            blocksRepo = blocksRepo,
            getOpenNoteId = { notePanel.openNoteId },
            onAppendLive = { body -> notePanel.onAppendLive(body) },
            onReplaceFinal = { body, addNewline -> notePanel.onReplaceFinal(body, addNewline) }
        )

        // Geste micro : créer une note immédiatement si aucune note n’est ouverte
        b.btnMicBar.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    if (!hasRecordPerm()) {
                        recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    if (notePanel.openNoteId == null) {
                        lifecycleScope.launch {
                            val newId = withContext(Dispatchers.IO) {
                                repo.createTextNote(body = "(transcription en cours…)")
                            }
                            Toast.makeText(this@MainActivity, "Note créée (#$newId)", Toast.LENGTH_SHORT).show()
                            notePanel.open(newId)

                            // 🌍 enrichissement lieu en arrière-plan
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
                                    blocksRepo.appendLocation(newId, place.lat, place.lon, place.label)
                                }
                            }

                            // Laisse le touch continuer vers le MicBarController
                            micCtl.beginPress(initialX = ev.x)
                        }
                        return@setOnTouchListener true
                        } else {
                            micCtl.beginPress(initialX = ev.x)
                            return@setOnTouchListener true
                        }
                }
            }
            micCtl.onTouch(ev)
            true
        }

        // Boutons bas
        b.btnKeyboard.setOnClickListener {
            val mode = if (notePanel.openNoteId == null) "NEW" else "SUB"
            launchKeyboardCapture(mode, notePanel.openNoteId)
        }
        b.btnPhoto.setOnClickListener {
            lifecycleScope.launch {
                ensureOpenNote()
                showPhotoSheet()
            }
        }
    }

    private suspend fun ensureOpenNote(): Long {
        notePanel.openNoteId?.let { return it }
        val newId = withContext(Dispatchers.IO) { repo.createTextNote("(transcription en cours…)") }
        Toast.makeText(this@MainActivity, "Note créée (#$newId)", Toast.LENGTH_SHORT).show()
        notePanel.open(newId)

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
                blocksRepo.appendLocation(newId, place.lat, place.lon, place.label)
            }
        }
        return newId
    }

    fun launchKeyboardCapture(mode: String, noteId: Long? = null, focusLast: Boolean = false) {
        val i = Intent(this, KeyboardCaptureActivity::class.java)
        i.putExtra("mode", mode)
        noteId?.let { i.putExtra("noteId", it) }
        if (focusLast) i.putExtra("focusLast", true)
        keyboardCaptureLauncher.launch(i)
    }

    private fun showPhotoSheet() {
        val sheet = BottomSheetDialog(this)
        val list = ListView(this)
        val opts = listOf("Prendre une photo", "Depuis la galerie")
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, opts)
        list.setOnItemClickListener { _, _, pos, _ ->
            when (pos) {
                0 -> openCamera()
                1 -> openGallery()
            }
            sheet.dismiss()
        }
        sheet.setContentView(list)
        sheet.show()
    }

    private fun openCamera() {
        // ✅ Sauvegarde PERSISTANTE : filesDir/images (et plus cacheDir)
        val nid = notePanel.openNoteId ?: return
        val dir = File(filesDir, "images").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        tempPhotoPath = file.absolutePath
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePhotoLauncher.launch(uri)
    }

    private fun openGallery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            readMediaPermLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            return
        }
        pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tempPhotoPath", tempPhotoPath)
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
