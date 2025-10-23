package com.example.openeer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.core.FeatureFlags
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.imports.ImportCoordinator
import com.example.openeer.imports.ImportEvent
import com.example.openeer.imports.MediaKind
import com.example.openeer.R
import com.example.openeer.services.WhisperService // ✅ warm-up Whisper en arrière-plan
import com.example.openeer.ui.capture.CaptureLauncher
import com.example.openeer.ui.editor.EditorBodyController
import com.example.openeer.ui.library.LibraryActivity
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.sheets.ChildTextEditorSheet
import com.example.openeer.ui.sheets.ReminderListSheet
import com.example.openeer.ui.util.configureSystemInsets
import com.example.openeer.ui.util.snackbar
import com.example.openeer.ui.util.toast
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build


class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_NOTE_ID = "extra_open_note_id"
        const val ACTION_OPEN_NOTE = "com.example.openeer.ACTION_OPEN_NOTE"
        const val EXTRA_NOTE_ID = "extra_note_id"
        private const val MENU_CREATE_GLOBAL_REMINDER = 7001
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
                val blocksRepo = BlocksRepository(
                    blockDao = db.blockDao(),
                    noteDao = db.noteDao(),
                    linkDao = db.blockLinkDao()
                )
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

    // === Sélection / actions depuis l'écran principal ===
    private var actionMode: ActionMode? = null
    private val selectedIds: LinkedHashSet<Long> = linkedSetOf()

    private lateinit var adapter: NotesAdapter
    // === fin sélection ===

    // ⚠️ latestNotes = liste **filtrée** (sans notes fusionnées)
    private var baseNotes: List<Note> = emptyList()
    private var latestNotes: List<Note> = emptyList()
    private val pendingDeletionIds = mutableSetOf<Long>()
    private val pendingDeletions = mutableMapOf<Long, PendingDeletion>()
    private var lastSelectedNoteId: Long? = null

    // ⚓️ Gel/dégel de la sélection pendant un refresh provoqué
    private var frozenSelectionId: Long? = null
    private fun freezeSelection() {
        frozenSelectionId = notePanel.openNoteId ?: lastSelectedNoteId
    }
    private fun clearSelectionFreeze() {
        frozenSelectionId = null
    }

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
    private var isMediaStripDragging = false
    private val caretPositions = mutableMapOf<Long, Int>()

    private val pileCountViews by lazy {
        listOf(
            requireNotNull(b.root.findViewWithTag("pileCount1")) as TextView,
            requireNotNull(b.root.findViewWithTag("pileCount2")) as TextView,
            requireNotNull(b.root.findViewWithTag("pileCount3")) as TextView,
            requireNotNull(b.root.findViewWithTag("pileCount4")) as TextView,
            requireNotNull(b.root.findViewWithTag("pileCount5")) as TextView,
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
                // 🧊 fige la sélection avant de déclencher l'import (qui mettra à jour updatedAt)
                freezeSelection()
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

        // Note panel
        notePanel = NotePanelController(this, b)
        notePanel.attachTopBubble(topBubble)
        notePanel.onOpenNoteChanged = { id ->
            maintainSelection(id)
            if (::micCtl.isInitialized) {
                micCtl.onOpenNoteChanged(id)
            }
        }
        notePanel.onPileCountsChanged = { counts -> applyPileCounts(counts) }
        b.mediaStrip.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                isMediaStripDragging = newState == RecyclerView.SCROLL_STATE_DRAGGING
            }
        })
        lifecycleScope.launch {
            notePanel.observePileUi().collectLatest { piles ->
                renderPiles(piles)
            }
        }

        // === Adapter (créé après que 'b' soit prêt) ===
        adapter = NotesAdapter(
            onClick = { note ->
                if (actionMode != null) {
                    toggleSelection(note.id)
                } else {
                    onNoteClicked(note)
                }
            },
            onLongClick = { note ->
                if (actionMode == null) {
                    actionMode = startSupportActionMode(mainActionModeCb)
                    adapter.showSelectionUi = true // 👈 affiche les cases dès l’entrée en ActionMode
                }
                toggleSelection(note.id)
            },
            onReminderClick = { note ->
                ReminderListSheet
                    .newInstance(note.id)
                    .show(supportFragmentManager, "reminder_list")
            }
        )

        // Liste
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        lifecycleScope.launch {
            vm.notes.collectLatest { notes ->
                // ⛔️ MASQUER les notes fusionnées (isMerged = true)
                val visible = notes.filterNot { it.isMerged }
                baseNotes = visible
                val visibleIds = visible.map { it.id }.toSet()
                pendingDeletionIds.retainAll(visibleIds)
                val filtered = visible.filterNot { pendingDeletionIds.contains(it.id) }

                latestNotes = filtered

                // On restaure d’abord à partir d’un éventuel gel
                val restoredIdFromFreeze = frozenSelectionId
                val currentId = restoredIdFromFreeze ?: notePanel.openNoteId
                val index = currentId?.let { id -> filtered.indexOfFirst { it.id == id } } ?: -1

                adapter.submitList(filtered) {
                    // Restaure la sélection APRÈS le diff
                    maintainSelection(currentId, filtered, index)
                    // Dégèle si un gel était actif
                    if (restoredIdFromFreeze != null) clearSelectionFreeze()
                }
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
                    freezeSelection()

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
            },
            onCaretPositionChanged = { position ->
                notePanel.openNoteId?.let { caretPositions[it] = position }
            }
        )

        b.noteBodySurface.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && view.isPressed) {
                view.performClick()
            }
            false
        }
        b.noteBodySurface.setOnClickListener { handleTapToFocus() }

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
                    b.root.post { editorBody.enterInlineEdit(nid, caretPositions[nid]) }
                }
            }
        }
        b.btnPhoto.setOnClickListener {
            lifecycleScope.launch {
                // 🧊 fige la sélection avant la capture (mise à jour updatedAt à venir)
                freezeSelection()
                val nid = ensureOpenNote()
                captureLauncher.launchPhotoCapture(nid)
            }
        }
        b.btnSketch.setOnClickListener {
            lifecycleScope.launch {
                // 🧊 fige la sélection avant la capture
                freezeSelection()
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
            // On sécurise l’état courant avant de naviguer vers la carte
            editorBody.commitInlineEdit(notePanel.openNoteId)
            val targetNoteId = notePanel.openNoteId
            notePanel.close()

            Log.d("MapNav", "Main map button → exploration map")
            startActivity(MapActivity.newBrowseIntent(this, targetNoteId))
        }

        b.btnImport.setOnClickListener {
            if (!FeatureFlags.IMPORT_V1_ENABLED) {
                toast("Import bientôt…")
                return@setOnClickListener
            }
            // 🧊 fige la sélection avant l’UI système d’import
            freezeSelection()
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

        b.btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, MENU_CREATE_GLOBAL_REMINDER, 0, getString(R.string.menu_create_global_reminder))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CREATE_GLOBAL_REMINDER -> {
                        val openId = notePanel.openNoteId
                        if (openId != null && openId > 0L) {
                            BottomSheetReminderPicker.newInstance(openId)
                                .show(supportFragmentManager, "reminder_picker")
                        } else {
                            lifecycleScope.launch {
                                val newNoteId = ensureOpenNote()
                                BottomSheetReminderPicker.newInstance(newNoteId)
                                    .show(supportFragmentManager, "reminder_picker")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // Clic sur le corps = édition inline
        b.txtBodyDetail.setOnClickListener { handleTapToFocus() }

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
        pendingDeletions.values.forEach {
            it.job.cancel()
            it.snackbar.dismiss()
        }
        pendingDeletions.clear()
        pendingDeletionIds.clear()
        super.onDestroy()
    }
    // ---------- Ouvrir une note ----------
    private fun onNoteClicked(note: Note) {
        // Sécurise l’overlay avant de changer de note
        runCatching { editorBody.commitInlineEdit(notePanel.openNoteId) }
        notePanel.open(note.id)
    }

    private fun refreshVisibleNotes() {
        val filtered = baseNotes.filterNot { pendingDeletionIds.contains(it.id) }
        latestNotes = filtered
        val currentId = notePanel.openNoteId
        val index = currentId?.let { id -> filtered.indexOfFirst { it.id == id } } ?: -1
        adapter.submitList(filtered) {
            maintainSelection(currentId, filtered, index)
        }
    }

    private fun maintainSelection(
        noteId: Long?,
        notes: List<Note> = latestNotes, // ⚠️ latestNotes est filtrée
        presetIndex: Int = -1,
    ) {
        if (noteId == null) {
            if (lastSelectedNoteId != null || adapter.selectedIds.isNotEmpty()) {
                lastSelectedNoteId = null
                adapter.selectedIds = emptySet()
            }
            return
        }

        val id = noteId
        val index = if (presetIndex >= 0) presetIndex else notes.indexOfFirst { it.id == id }

        if (lastSelectedNoteId != id || !adapter.selectedIds.contains(id)) {
            lastSelectedNoteId = id
            adapter.selectedIds = setOf(id)
        }

        if (index == -1) return

        b.recycler.post {
            when (val layoutManager = b.recycler.layoutManager) {
                is LinearLayoutManager -> {
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()
                    if (first == RecyclerView.NO_POSITION || index < first || index > last) {
                        layoutManager.scrollToPositionWithOffset(index, 0)
                    }
                }
                null -> Unit
                else -> layoutManager.scrollToPosition(index)
            }
        }
    }

    // ---------- Renommer depuis la liste (utilisé par ActionMode) ----------
    private fun promptRename(noteId: Long) {
        val note = latestNotes.firstOrNull { it.id == noteId } ?: return
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
                    // 🧊 fige la sélection avant le setTitle (qui bump updatedAt)
                    freezeSelection()
                    repo.setTitle(note.id, t?.ifBlank { null })
                }
                clearMainSelection()
            }
            .setNegativeButton("Annuler") { _, _ -> clearMainSelection() }
            .show()
    }

    private fun promptDeleteSelectedNote() {
        if (selectedIds.isEmpty()) return

        val notes = selectedIds.mapNotNull { id ->
            latestNotes.firstOrNull { it.id == id } ?: baseNotes.firstOrNull { it.id == id }
        }
        if (notes.isEmpty()) return

        val pending = notes.firstOrNull { pendingDeletions.containsKey(it.id) }
        if (pending != null) {
            Snackbar.make(b.root, getString(R.string.library_delete_pending), Snackbar.LENGTH_SHORT).show()
            return
        }

        if (notes.size > 1) {
            showMultipleDeleteWarning(notes)
        } else {
            showDeletePrimaryConfirmation(notes)
        }
    }

    private fun showMultipleDeleteWarning(notes: List<Note>) {
        AlertDialog.Builder(this)
            .setMessage(R.string.library_delete_multiple_warning_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.library_delete_multiple_warning_positive) { _, _ ->
                showDeletePrimaryConfirmation(notes)
            }
            .show()
    }

    private fun showDeletePrimaryConfirmation(notes: List<Note>) {
        val message = if (notes.size > 1) {
            getString(R.string.library_delete_primary_message_multiple, notes.size)
        } else {
            getString(R.string.library_delete_primary_message)
        }

        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.library_delete_primary_positive) { _, _ ->
                showDeleteCascadeConfirmation(notes)
            }
            .show()
    }

    private fun showDeleteCascadeConfirmation(notes: List<Note>) {
        val message = if (notes.size > 1) {
            getString(R.string.library_delete_secondary_message_multiple, notes.size)
        } else {
            getString(R.string.library_delete_secondary_message)
        }

        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.library_delete_secondary_positive) { _, _ ->
                if (notes.size > 1) {
                    scheduleNotesDeletion(notes)
                } else {
                    val note = notes.first()
                    lifecycleScope.launch { scheduleNoteDeletion(note.id) }
                }
            }
            .show()
    }

    private fun scheduleNotesDeletion(notes: List<Note>) {
        lifecycleScope.launch {
            val cascades = mutableListOf<Pair<Long, Set<Long>>>()
            val coveredIds = mutableSetOf<Long>()

            for (note in notes) {
                val cascade = runCatching { repo.collectCascade(note.id) }
                    .onFailure {
                        Snackbar.make(b.root, getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
                    }
                    .getOrNull() ?: return@launch

                if (cascade.isEmpty()) {
                    Snackbar.make(b.root, getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                if (pendingDeletions.containsKey(note.id)) {
                    Snackbar.make(b.root, getString(R.string.library_delete_pending), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                if (cascade.all { coveredIds.contains(it) }) {
                    continue
                }

                cascades.add(note.id to cascade)
                coveredIds.addAll(cascade)
            }

            if (cascades.isEmpty()) return@launch

            cascades.forEach { (rootId, cascade) ->
                startPendingDeletion(rootId, cascade)
            }
        }
    }

    private suspend fun scheduleNoteDeletion(noteId: Long) {
        val cascade = runCatching { repo.collectCascade(noteId) }
            .onFailure {
                Snackbar.make(b.root, getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
            }
            .getOrNull() ?: return

        if (cascade.isEmpty()) {
            Snackbar.make(b.root, getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
            return
        }

        if (pendingDeletions.containsKey(noteId)) {
            Snackbar.make(b.root, getString(R.string.library_delete_pending), Snackbar.LENGTH_SHORT).show()
            return
        }

        startPendingDeletion(noteId, cascade)
    }

    private fun startPendingDeletion(rootId: Long, cascade: Set<Long>) {
        clearMainSelection()
        val openId = notePanel.openNoteId
        if (openId != null && cascade.contains(openId)) {
            notePanel.close()
        }

        pendingDeletionIds.addAll(cascade)
        refreshVisibleNotes()

        val snackbar = Snackbar.make(b.root, getString(R.string.library_delete_snackbar), Snackbar.LENGTH_INDEFINITE)
        snackbar.duration = 5000
        snackbar.setAction(R.string.action_undo) { undoPendingDeletion(rootId) }

        val job = lifecycleScope.launch {
            try {
                delay(5000)
                repo.deleteNoteCascade(rootId, cascade)
                withContext(Dispatchers.Main.immediate) {
                    pendingDeletions.remove(rootId)?.snackbar?.dismiss()
                    pendingDeletionIds.removeAll(cascade)
                    refreshVisibleNotes()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    pendingDeletions.remove(rootId)?.snackbar?.dismiss()
                    pendingDeletionIds.removeAll(cascade)
                    refreshVisibleNotes()
                    Snackbar.make(b.root, getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        pendingDeletions[rootId] = PendingDeletion(cascade, job, snackbar)
        snackbar.show()
    }

    private fun undoPendingDeletion(rootId: Long) {
        val pending = pendingDeletions.remove(rootId) ?: return
        pending.job.cancel()
        pending.snackbar.dismiss()
        pendingDeletionIds.removeAll(pending.ids)
        refreshVisibleNotes()
        Snackbar.make(b.root, getString(R.string.library_delete_undo_success), Snackbar.LENGTH_SHORT).show()
    }

    private fun handleTapToFocus() {
        val openNoteId = notePanel.openNoteId ?: return
        if (isTapToFocusBlocked()) return
        if (notePanel.isListMode()) return
        val caret = caretPositions[openNoteId]
        editorBody.enterInlineEdit(openNoteId, caret)
        val cursorLabel = caret?.let { "cursor=$it" } ?: "cursor=end"
        Log.d("TapToFocus", "focused, $cursorLabel, ime=shown")
    }

    private fun isTapToFocusBlocked(): Boolean {
        if (isMediaStripDragging) return true
        if (micCtl.isRecording()) return true
        if (isAnyBottomSheetVisible()) return true
        return false
    }

    private fun isAnyBottomSheetVisible(): Boolean {
        return supportFragmentManager.fragments.any { it.hasVisibleBottomSheet() }
    }

    private fun Fragment?.hasVisibleBottomSheet(): Boolean {
        this ?: return false
        if (this is BottomSheetDialogFragment && dialog?.isShowing == true) {
            return true
        }
        return childFragmentManager.fragments.any { it.hasVisibleBottomSheet() }
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
        val labels = listOf(b.pileLabel1, b.pileLabel2, b.pileLabel3, b.pileLabel4, b.pileLabel5)
        val counts = pileCountViews
        val titleByCategory = mapOf(
            MediaCategory.PHOTO to "Photos/Vidéos",
            MediaCategory.AUDIO to "Audios",
            MediaCategory.TEXT to "Textes",
            MediaCategory.SKETCH to "Fichiers",
            MediaCategory.LOCATION to getString(R.string.pile_label_locations)
        )
        val fallbackOrder = listOf(
            MediaCategory.PHOTO,
            MediaCategory.AUDIO,
            MediaCategory.TEXT,
            MediaCategory.SKETCH,
            MediaCategory.LOCATION
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
                    MediaCategory.LOCATION -> lastPileCounts.locations
                }
                counts[i].text = (countFromPiles ?: fallbackCount).toString()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenNoteIntent(intent)
    }

    private fun handleOpenNoteIntent(intent: Intent?) {
        val targetId = intent?.getLongExtra(EXTRA_OPEN_NOTE_ID, -1L) ?: -1L
        if (targetId > 0) {
            Log.d("MainActivity", "Received reminder open note request for id=$targetId")
            // TODO: Naviguer vers la note targetId via le routeur d'écran habituel.
            notePanel.open(targetId)
            intent?.removeExtra(EXTRA_OPEN_NOTE_ID)
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

    // ===== Sélection / ActionMode =====
    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        adapter.selectedIds = selectedIds
        if (selectedIds.isEmpty()) {
            actionMode?.finish()
        } else {
            actionMode?.title = "${selectedIds.size} sélectionnée(s)"
            actionMode?.invalidate()
        }
    }

    private val mainActionModeCb: ActionMode.Callback = object : ActionMode.Callback {
        private val MENU_MERGE = 1
        private val MENU_UNMERGE = 2
        private val MENU_RENAME = 3
        private val MENU_DELETE = 4

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            adapter.showSelectionUi = true // 👈 active l’UI de sélection (cases/coches + overlay)
            menu.add(0, MENU_MERGE, 0, "Fusionner")
            menu.add(0, MENU_UNMERGE, 1, "Défusionner…")
            menu.add(0, MENU_RENAME, 2, "Renommer")
            menu.add(0, MENU_DELETE, 3, getString(R.string.library_action_delete))
            mode.title = "${selectedIds.size} sélectionnée(s)"
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val one = selectedIds.size == 1
            val many = selectedIds.size >= 2
            menu.findItem(MENU_MERGE)?.isEnabled = many
            menu.findItem(MENU_UNMERGE)?.isEnabled = one
            menu.findItem(MENU_RENAME)?.isEnabled = one
            menu.findItem(MENU_DELETE)?.isEnabled = selectedIds.isNotEmpty()
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                MENU_MERGE -> {
                    val notes = latestNotes.filter { it.id in selectedIds }
                        .sortedByDescending { it.updatedAt }
                    if (notes.size >= 2) {
                        val target = notes.first()
                        val sources = notes.drop(1).map { it.id }
                        lifecycleScope.launch {
                            val res = repo.mergeNotes(sources, target.id)
                            if (res.mergedCount > 0) {
                                notePanel.open(target.id)
                                toast(getString(com.example.openeer.R.string.library_merge_success_with_count, res.mergedCount, res.total))
                            } else {
                                toast(res.reason ?: "Fusion impossible")
                            }
                            clearMainSelection()
                        }
                    }
                    return true
                }
                MENU_UNMERGE -> {
                    val only = selectedIds.first()
                    lifecycleScope.launch { performUnmergeFromMain(only) }
                    return true
                }
                MENU_RENAME -> {
                    val only = selectedIds.first()
                    promptRename(only)
                    return true
                }
                MENU_DELETE -> {
                    promptDeleteSelectedNote()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            clearMainSelection()
        }
    }

    private fun clearMainSelection() {
        selectedIds.clear()
        adapter.selectedIds = emptySet()
        adapter.showSelectionUi = false // 👈 masque l’UI de sélection quand on quitte l’ActionMode
        actionMode = null
    }

    private suspend fun performUnmergeFromMain(targetNoteId: Long) {
        val db = AppDatabase.get(this@MainActivity)
        val logs = withContext(Dispatchers.IO) { db.noteDao().listMergeLogsUi() }
            .filter { it.targetId == targetNoteId }
            .sortedByDescending { it.createdAt }

        if (logs.isEmpty()) {
            toast("Aucune fusion enregistrée pour cette note")
            clearMainSelection()
            return
        }

        val labels = logs.map { row ->
            "#${row.id} • source #${row.sourceId} → cible #${row.targetId}"
        }.toTypedArray()

        var checked = 0
        AlertDialog.Builder(this)
            .setTitle("Annuler une fusion")
            .setSingleChoiceItems(labels, checked) { _, which -> checked = which }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Défusionner") { _, _ ->
                val logId = logs[checked].id
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { repo.undoMergeById(logId) }
                    if (result.reassigned + result.recreated > 0) {
                        toast("Défusion OK (${result.reassigned} réassignés, ${result.recreated} recréés)")
                        notePanel.open(targetNoteId)
                    } else {
                        toast("Défusion impossible")
                    }
                    clearMainSelection()
                }
            }
            .show()
    }
    // ===== fin sélection / ActionMode =====

    private data class PendingDeletion(
        val ids: Set<Long>,
        val job: Job,
        val snackbar: Snackbar,
    )
}

class NotesVm(private val repo: NoteRepository) : ViewModel() {
    val notes = repo.allNotes
}
