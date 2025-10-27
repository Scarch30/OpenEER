package com.example.openeer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.Injection
import com.example.openeer.core.FeatureFlags
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.imports.ImportCoordinator
import com.example.openeer.imports.ImportEvent
import com.example.openeer.R
import com.example.openeer.services.WhisperService // ✅ warm-up Whisper en arrière-plan
import com.example.openeer.ui.capture.CaptureLauncher
import com.example.openeer.ui.editor.EditorBodyController
import com.example.openeer.ui.sheets.ReminderListSheet
import com.example.openeer.ui.util.configureSystemInsets
import com.example.openeer.ui.util.snackbar
import com.example.openeer.ui.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter


class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_NOTE_ID = "extra_open_note_id"
        const val ACTION_OPEN_NOTE = "com.example.openeer.ACTION_OPEN_NOTE"
        const val EXTRA_NOTE_ID = "extra_note_id"
    }
    // === Retour depuis la carte : ouvrir une note spécifique ===

    private val openNoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_OPEN_NOTE) return
            val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
            if (noteId > 0) {
                // Revenir visuellement dans la note demandée
                notePanel.open(noteId)
            }
        }
    }

    private lateinit var b: ActivityMainBinding

    // VM / liste
    private val vm: NotesVm by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = AppDatabase.get(this@MainActivity)
                val blocksRepo = Injection.provideBlocksRepository(this@MainActivity)
                return NotesVm(
                    NoteRepository(
                        applicationContext,
                        db.noteDao(),
                        db.attachmentDao(),
                        db.blockReadDao(),
                        blocksRepo,
                        db.listItemDao(),
                        database = db
                    )
                ) as T
            }
        }
    }

    private lateinit var adapter: NotesAdapter
    private lateinit var selectionController: NoteListSelectionController
    private lateinit var noteCreationHelper: NoteCreationHelper

    // Repos
    private val repo: NoteRepository by lazy {
        val db = AppDatabase.get(this)
        NoteRepository(
            applicationContext,
            db.noteDao(),
            db.attachmentDao(),
            db.blockReadDao(),
            blocksRepo,
            db.listItemDao(),
            database = db
        )
    }
    private val blocksRepo: BlocksRepository by lazy {
        Injection.provideBlocksRepository(this)
    }

    private lateinit var importCoordinator: ImportCoordinator

    // Contrôleurs
    private lateinit var notePanel: NotePanelController
    private lateinit var captureLauncher: CaptureLauncher
    private lateinit var micCtl: MicBarController
    private lateinit var editorBody: EditorBodyController
    private lateinit var topBubble: TopBubbleController
    private lateinit var bottomBarController: MainBottomBarController
    private lateinit var pileUiController: PileUiController
    private lateinit var tapToFocusManager: TapToFocusManager
    private val caretPositions = mutableMapOf<Long, Int>()

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
                // 🧊 fige la sélection avant de déclencher l'import (qui mettra à jour updatedAt)
                selectionController.freezeSelection(notePanel.openNoteId)
                editorBody.commitInlineEdit(notePanel.openNoteId)
                val targetNoteId = notePanel.openNoteId ?: noteCreationHelper.ensureOpenNote()
                runCatching {
                    importCoordinator.import(targetNoteId, uris)
                }.onFailure {
                    Log.e("MainActivity", "Import failed", it)
                    toast("Import impossible", Toast.LENGTH_LONG)
                }
            }
        }

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

        // Note panel
        notePanel = NotePanelController(this, b, blocksRepo)
        notePanel.attachTopBubble(topBubble)
        notePanel.onOpenNoteChanged = { id ->
            if (::selectionController.isInitialized) {
                selectionController.maintainSelection(id)
            }
            if (::micCtl.isInitialized) {
                micCtl.onOpenNoteChanged(id)
            }
        }
        pileUiController = PileUiController(this, b, notePanel)
        notePanel.onPileCountsChanged = { counts -> pileUiController.onPileCountsChanged(counts) }
        noteCreationHelper = NoteCreationHelper(this, repo, notePanel)
        lifecycleScope.launch {
            notePanel.observePileUi().collectLatest { piles ->
                pileUiController.renderPiles(piles)
            }
        }

        // === Adapter (créé après que 'b' soit prêt) ===
        adapter = NotesAdapter(
            onClick = { note ->
                if (!selectionController.handleNoteClick(note.id)) {
                    onNoteClicked(note)
                }
            },
            onLongClick = { note ->
                selectionController.handleNoteLongClick(note.id) { callback ->
                    startSupportActionMode(callback)
                }
            },
            onReminderClick = { note ->
                ReminderListSheet
                    .newInstance(note.id)
                    .show(supportFragmentManager, "reminder_list")
            }
        )

        selectionController = NoteListSelectionController(
            activity = this,
            binding = b,
            adapter = adapter,
            notePanel = notePanel,
            repo = repo
        )
        selectionController.maintainSelection(notePanel.openNoteId)

        // Liste
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        lifecycleScope.launch {
            vm.notes.collectLatest { notes ->
                selectionController.updateNotes(notes)
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
        Log.d("ListEarly", "MicCtl uses BlocksRepository singleton")
        micCtl = MicBarController(
            activity = this,
            binding = b,
            repo = repo,
            blocksRepo = blocksRepo,
            getOpenNoteId = { notePanel.openNoteId },
            getOpenNote = { notePanel.currentNoteSnapshot() },
            onAppendLive = { body -> notePanel.onAppendLive(body) },
            onReplaceFinal = { body, addNewline -> notePanel.onReplaceFinal(body, addNewline) },
            showTopBubble = { message -> topBubble.show(message) }
        )
        micCtl.onOpenNoteChanged(notePanel.openNoteId)

        handleOpenNoteIntent(intent)

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

                    // 🧊 fige la sélection pour éviter un switch pendant la prise
                    selectionController.freezeSelection(notePanel.openNoteId)

                    if (notePanel.openNoteId == null) {
                        lifecycleScope.launch {
                            val newId = noteCreationHelper.ensureOpenNote()
                            notePanel.onAppendLive("(transcription en cours…)")
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
            blocksRepository = blocksRepo,
            onEditModeChanged = { editing ->
                b.btnMicBar.isVisible = !editing
                b.bottomBar.isVisible = !editing
            },
            onCaretPositionChanged = { position ->
                notePanel.openNoteId?.let { caretPositions[it] = position }
            }
        )

        tapToFocusManager = TapToFocusManager(
            activity = this,
            binding = b,
            notePanel = notePanel,
            editorBody = editorBody,
            micController = micCtl,
            caretPositions = caretPositions
        )
        tapToFocusManager.bind()

        b.mediaStrip.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                tapToFocusManager.onMediaStripScrollStateChanged(newState)
            }
        })

        bottomBarController = MainBottomBarController(
            activity = this,
            binding = b,
            notePanel = notePanel,
            editorBody = editorBody,
            captureLauncher = captureLauncher,
            selectionController = selectionController,
            caretPositions = caretPositions,
            ensureOpenNote = { noteCreationHelper.ensureOpenNote() },
            onChildBlockSaved = ::onChildBlockSaved,
            launchImportPicker = { types -> importLauncher.launch(types) }
        )
        bottomBarController.bind()

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
                        pileUiController.increment(event.kind)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureLauncher.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenNoteIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        editorBody.commitInlineEdit(notePanel.openNoteId)
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_OPEN_NOTE)
        // ✅ Compatible toutes versions Android
        try {
            registerReceiver(
                openNoteReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } catch (_: NoSuchFieldError) {
            // ✅ Compat < Android 13 (RECEIVER_NOT_EXPORTED n’existe pas)
            @Suppress("DEPRECATION")
            registerReceiver(openNoteReceiver, filter)
        }
    }


    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(openNoteReceiver) }
    }

    override fun onDestroy() {
        if (::selectionController.isInitialized) {
            selectionController.onDestroy()
        }
        super.onDestroy()
    }

    private fun handleOpenNoteIntent(intent: Intent?) {
        if (intent == null) return

        val noteId = when {
            intent.hasExtra(EXTRA_OPEN_NOTE_ID) -> intent.getLongExtra(EXTRA_OPEN_NOTE_ID, -1L)
            intent.action == ACTION_OPEN_NOTE -> intent.getLongExtra(EXTRA_NOTE_ID, -1L)
            else -> -1L
        }

        if (noteId <= 0L) return

        runCatching { editorBody.commitInlineEdit(notePanel.openNoteId) }
        notePanel.open(noteId)
        intent.removeExtra(EXTRA_OPEN_NOTE_ID)
        intent.removeExtra(EXTRA_NOTE_ID)
    }
    // ---------- Ouvrir une note ----------
    private fun onNoteClicked(note: Note) {
        // Sécurise l’overlay avant de changer de note
        runCatching { editorBody.commitInlineEdit(notePanel.openNoteId) }
        notePanel.open(note.id)
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
