package com.example.openeer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
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
import com.example.openeer.ui.capture.SketchCaptureActivity
import com.example.openeer.R
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.editor.EditorBodyController
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    // Repos
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
    private lateinit var editorBody: EditorBodyController

    // Photo
    private var tempPhotoPath: String? = null
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
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

    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
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

    private val keyboardCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val noteId = data.getLongExtra(KeyboardCaptureActivity.EXTRA_NOTE_ID, -1L)
                .takeIf { it > 0 } ?: notePanel.openNoteId ?: return@registerForActivityResult
            val added = data.getBooleanExtra("addedText", false)
            if (!added) return@registerForActivityResult
            val blockId = data.getLongExtra(KeyboardCaptureActivity.EXTRA_BLOCK_ID, -1L)
                .takeIf { it > 0 }
            onChildBlockSaved(noteId, blockId, "Post-it texte ajouté")
        }

    private val sketchCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val noteId = data.getLongExtra(SketchCaptureActivity.EXTRA_NOTE_ID, -1L)
                .takeIf { it > 0 } ?: notePanel.openNoteId ?: return@registerForActivityResult
            val blockId = data.getLongExtra(SketchCaptureActivity.EXTRA_BLOCK_ID, -1L)
                .takeIf { it > 0 } ?: return@registerForActivityResult
            onChildBlockSaved(noteId, blockId, "Post-it dessin ajouté")
        }

    private val readMediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openGallery()
            else Toast.makeText(this, "Permission galerie refusée", Toast.LENGTH_LONG).show()
        }

    // Permissions micro
    private val recordPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (!ok) Toast.makeText(this, "Permission micro refusée", Toast.LENGTH_LONG).show()
        }

    private fun hasRecordPerm(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // Note panel
        notePanel = NotePanelController(this, b)

        // Mic controller
        micCtl = MicBarController(
            activity = this,
            binding = b,
            repo = repo,
            blocksRepo = blocksRepo,
            getOpenNoteId = { notePanel.openNoteId },
            onAppendLive = { body -> notePanel.onAppendLive(body) },
            onReplaceFinal = { body, addNewline -> notePanel.onReplaceFinal(body, addNewline) }
        )

        // Geste micro
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
                                repo.createTextNote("(transcription en cours…)")
                            }
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

        editorBody = EditorBodyController(
            activity = this,
            binding = b,
            repo = repo
        )

        // Boutons barre du bas
        b.btnKeyboard.setOnClickListener {
            lifecycleScope.launch {
                val nid = ensureOpenNote()
                val intent = Intent(this@MainActivity, KeyboardCaptureActivity::class.java).apply {
                    putExtra(KeyboardCaptureActivity.EXTRA_NOTE_ID, nid)
                }
                keyboardCaptureLauncher.launch(intent)
            }
        }
        b.btnPhoto.setOnClickListener {
            lifecycleScope.launch {
                val nid = ensureOpenNote()
                val intent = Intent(this@MainActivity, SketchCaptureActivity::class.java).apply {
                    putExtra(SketchCaptureActivity.EXTRA_NOTE_ID, nid)
                }
                sketchCaptureLauncher.launch(intent)
            }
        }

        // Clic sur le corps = édition inline
        b.txtBodyDetail.setOnClickListener {
            editorBody.enterInlineEdit(notePanel.openNoteId)
        }

    }

    // ---------- Photo ----------
    private fun showPhotoSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
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
        val nid = notePanel.openNoteId ?: return
        val dir = File(filesDir, "images").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        tempPhotoPath = file.absolutePath
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePhotoLauncher.launch(uri)
    }

    private fun openGallery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            readMediaPermLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            return
        }
        pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tempPhotoPath", tempPhotoPath)
    }

    override fun onPause() {
        super.onPause()
        editorBody.commitInlineEdit(notePanel.openNoteId)
    }

    // ---------- Ouvrir une note ----------
    private fun onNoteClicked(note: Note) {
        notePanel.open(note.id)
    }

    // ---------- Titre rapide ----------
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

    private fun onChildBlockSaved(noteId: Long, blockId: Long?, message: String) {
        lifecycleScope.launch {
            if (blockId != null) {
                awaitBlock(noteId, blockId)
            }
            if (notePanel.openNoteId != noteId) {
                notePanel.open(noteId)
            }
            blockId?.let { notePanel.highlightBlock(it) }
            Snackbar.make(b.root, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private suspend fun awaitBlock(noteId: Long, blockId: Long) {
        withContext(Dispatchers.IO) {
            blocksRepo.observeBlocks(noteId)
                .filter { blocks -> blocks.any { it.id == blockId } }
                .first()
        }
    }

}

class NotesVm(private val repo: NoteRepository) : ViewModel() {
    val notes = repo.allNotes
}
