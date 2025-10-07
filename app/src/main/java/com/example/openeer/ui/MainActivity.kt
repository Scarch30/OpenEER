// app/src/main/java/com/example/openeer/ui/MainActivity.kt
package com.example.openeer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.core.FeatureFlags
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.capture.CaptureLauncher
import com.example.openeer.ui.editor.EditorBodyController
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.sheets.ChildTextEditorSheet
import com.example.openeer.ui.util.configureSystemInsets
import com.example.openeer.ui.util.snackbar
import com.example.openeer.ui.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.openeer.imports.ImportCoordinator
import com.example.openeer.imports.ImportEvent
import com.example.openeer.imports.MediaKind
import com.example.openeer.ui.library.LibraryActivity
import com.example.openeer.services.WhisperService // ✅ warm-up Whisper en arrière-plan
import android.util.Log
import android.content.Intent
import androidx.core.view.isGone

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
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao  = db.noteDao(),
            linkDao  = db.blockLinkDao()   // ✅ injection pour liens AUDIO→TEXTE
        )
    }

    private lateinit var importCoordinator: ImportCoordinator

    // Contrôleurs
    private lateinit var notePanel: NotePanelController
    private lateinit var captureLauncher: CaptureLauncher
    private lateinit var micCtl: MicBarController
    private lateinit var editorBody: EditorBodyController
    private lateinit var topBubble: TopBubbleController

    private val pileCountViews by lazy {
        listOf(
            requireNotNull(b.root.findViewWithTag("pileCount1")) as TextView,
            requireNotNull(b.root.findViewWithTag("pileCount2")) as TextView,
            requireNotNull(b.root.findViewWithTag("pileCount3")) as TextView,
            requireNotNull(b.root.findViewWithTag("pileCount4")) as TextView,
        )
    }

    // Permissions micro
    private val recordPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (!ok) toast("Permission micro refusée", Toast.LENGTH_LONG)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult

            if (!FeatureFlags.IMPORT_V1_ENABLED) {
                toast("Import bientôt…")
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                editorBody.commitInlineEdit(notePanel.openNoteId)
                val targetNoteId = notePanel.openNoteId ?: ensureOpenNote()
                runCatching {
                    importCoordinator.import(targetNoteId, uris)
                }.onFailure {
                    Log.e("MainActivity", "Import failed", it)
                    toast("Import impossible", Toast.LENGTH_LONG)
                }
            }
        }

    private var lastPileCounts = PileCounts()

    private fun hasRecordPerm(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemInsets(true)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        importCoordinator = ImportCoordinator(
            context = this,
            resolver = contentResolver,
            noteRepository = repo,
            blocksRepository = blocksRepo,
            scope = lifecycleScope
        )

        topBubble = TopBubbleController(b, lifecycleScope)

        // Liste
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        lifecycleScope.launch {
            vm.notes.collectLatest { adapter.submitList(it) }
        }

        // Note panel
        notePanel = NotePanelController(this, b)
        notePanel.onPileCountsChanged = { counts -> applyPileCounts(counts) }
        lifecycleScope.launch {
            notePanel.observePileUi().collectLatest { piles ->
                renderPiles(piles)
            }
        }

        captureLauncher = CaptureLauncher(
            activity = this,
            notePanel = notePanel,
            repo = repo,
            blocksRepo = blocksRepo,
            onChildBlockSaved = ::onChildBlockSaved
        )
        captureLauncher.onCreate(savedInstanceState)

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
                    // Sécurise un éventuel overlay d’édition avant de changer de note
                    runCatching { editorBody.commitInlineEdit(notePanel.openNoteId) }

                    if (notePanel.openNoteId == null) {
                        lifecycleScope.launch {
                            val newId = withContext(Dispatchers.IO) {
                                // IMPORTANT : créer une note VIERGE
                                repo.createTextNote("")
                            }
                            this@MainActivity.toast("Note créée (#$newId)")
                            notePanel.open(newId)

                            // Placeholder visuel seulement (non persistant)
                            notePanel.onAppendLive("(transcription en cours…)")

                            // Ajout opportuniste de la localisation
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
            repo = repo,
            onEditModeChanged = { editing ->
                b.btnMicBar.isVisible = !editing
                b.bottomBar.isVisible = !editing
            }
        )

        // Boutons barre du bas
        b.btnKeyboard.setOnClickListener {
            lifecycleScope.launch {
                val openId = notePanel.openNoteId
                if (openId != null) {
                    // NOTE OUVERTE -> éditeur de post-it (BottomSheet)
                    editorBody.commitInlineEdit(openId)
                    val sheet = ChildTextEditorSheet.new(openId).apply {
                        onSaved = { noteId, blockId ->
                            onChildBlockSaved(
                                noteId,
                                blockId,
                                getString(com.example.openeer.R.string.msg_block_text_added)
                            )
                        }
                    }
                    sheet.show(supportFragmentManager, "child_text")
                } else {
                    // Aucune note -> créer note mère VIERGE + édition inline du body
                    val nid = ensureOpenNote()
                    b.root.post { editorBody.enterInlineEdit(nid) }
                }
            }
        }
        b.btnPhoto.setOnClickListener {
            lifecycleScope.launch {
                val nid = ensureOpenNote()
                captureLauncher.launchPhotoCapture(nid)
            }
        }
        b.btnSketch.setOnClickListener {
            lifecycleScope.launch {
                val nid = ensureOpenNote()
                captureLauncher.launchSketchCapture(nid)
            }
        }
        b.btnLibrary.setOnClickListener {
            // On sécurise l’état courant puis on ouvre la Bibliothèque
            editorBody.commitInlineEdit(notePanel.openNoteId)
            notePanel.close()

            startActivity(Intent(this, LibraryActivity::class.java))
        }

        b.btnMap.setOnClickListener {
            b.root.snackbar("Carte/Itinéraire — bientôt disponible")
        }

        b.btnImport.setOnClickListener {
            if (!FeatureFlags.IMPORT_V1_ENABLED) {
                toast("Import bientôt…")
                return@setOnClickListener
            }
            importLauncher.launch(
                arrayOf(
                    "image/*",
                    "video/*",
                    "audio/*",
                    "text/plain",
                    "application/pdf"
                )
            )
        }

        // Clic sur le corps = édition inline
        b.txtBodyDetail.setOnClickListener {
            lifecycleScope.launch {
                val nid = ensureOpenNote()
                b.root.post { editorBody.enterInlineEdit(nid) }
            }
        }

        // ✅ Option A : warm-up du modèle Whisper en arrière-plan dès que l’UI est prête
        lifecycleScope.launch(Dispatchers.Default) {
            Log.d("MainActivity", "Warm-up Whisper en arrière-plan…")
            runCatching { WhisperService.loadModel(applicationContext) }
                .onSuccess { Log.d("MainActivity", "Whisper prêt (contexte chargé).") }
                .onFailure { Log.w("MainActivity", "Warm-up Whisper a échoué (non bloquant).", it) }
        }

        lifecycleScope.launch {
            importCoordinator.events.collect { event ->
                when (event) {
                    is ImportEvent.Started -> {
                        topBubble.show("Import en cours… (${event.total})")
                    }
                    is ImportEvent.TranscriptionQueued -> {
                        topBubble.show("Transcription en file…")
                    }
                    is ImportEvent.OcrAwaiting -> {
                        val name = event.displayName ?: "élément"
                        topBubble.show("OCR en attente pour $name")
                    }
                    is ImportEvent.Failed -> {
                        val name = event.displayName ?: "élément"
                        topBubble.showFailure("Import échoué : $name")
                    }
                    is ImportEvent.Finished -> {
                        if (event.successCount == event.total) {
                            topBubble.show("Import terminé")
                        } else {
                            topBubble.show("Import partiel : ${event.successCount}/${event.total}")
                        }
                    }
                    is ImportEvent.ItemOk -> {
                        incrementPileCount(event.kind)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureLauncher.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        editorBody.commitInlineEdit(notePanel.openNoteId)
    }

    // ---------- Ouvrir une note ----------
    private fun onNoteClicked(note: Note) {
        // Sécurise l’overlay avant de changer de note
        runCatching { editorBody.commitInlineEdit(notePanel.openNoteId) }
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
        val newId = withContext(Dispatchers.IO) {
            // IMPORTANT : créer une note VIERGE
            repo.createTextNote("")
        }
        this@MainActivity.toast("Note créée (#$newId)")
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

    private fun applyPileCounts(counts: PileCounts) {
        lastPileCounts = counts
        val hasOpenNote = notePanel.openNoteId != null
        b.pileCounters.isGone = !hasOpenNote
        val currentPiles = if (hasOpenNote) notePanel.currentPileUi() else emptyList()
        renderPiles(currentPiles)
    }

    private fun incrementPileCount(kind: MediaKind) {
        if (notePanel.openNoteId == null) return

        applyPileCounts(lastPileCounts.increment(kind))
    }

    private fun renderPiles(piles: List<PileUi>) {
        val labels = listOf(b.pileLabel1, b.pileLabel2, b.pileLabel3, b.pileLabel4)
        val counts = pileCountViews
        val titleByCategory = mapOf(
            MediaCategory.PHOTO to "Photos/Vidéos",
            MediaCategory.AUDIO to "Audios",
            MediaCategory.TEXT to "Textes",
            MediaCategory.SKETCH to "Fichiers"
        )
        val fallbackOrder = listOf(
            MediaCategory.PHOTO,
            MediaCategory.AUDIO,
            MediaCategory.TEXT,
            MediaCategory.SKETCH
        )
        val orderedCategories = buildList {
            piles.forEach { add(it.category) }
            fallbackOrder.forEach { category ->
                if (category !in this) add(category)
            }
        }
        for (i in labels.indices) {
            val category = orderedCategories.getOrNull(i)
            if (category == null) {
                labels[i].text = "—"
                counts[i].text = "0"
            } else {
                labels[i].text = titleByCategory[category] ?: "—"
                val countFromPiles = piles.firstOrNull { it.category == category }?.count
                val fallbackCount = when (category) {
                    MediaCategory.PHOTO -> lastPileCounts.photos
                    MediaCategory.AUDIO -> lastPileCounts.audios
                    MediaCategory.TEXT -> lastPileCounts.textes
                    MediaCategory.SKETCH -> lastPileCounts.files
                }
                counts[i].text = (countFromPiles ?: fallbackCount).toString()
            }
        }
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
            b.root.snackbar(message)
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
