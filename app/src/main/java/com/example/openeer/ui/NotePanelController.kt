// app/src/main/java/com/example/openeer/ui/NotePanelController.kt
package com.example.openeer.ui

import android.content.Intent
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.imports.MediaKind
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.panel.media.MediaStripAdapter
import com.example.openeer.ui.panel.media.MediaStripItem
import com.example.openeer.ui.panel.blocks.BlockRenderers
import com.example.openeer.ui.library.LibraryFragment
import com.example.openeer.ui.library.MapPreviewStorage
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.util.toast
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

data class PileCounts(
    val photos: Int = 0,
    val audios: Int = 0,
    val textes: Int = 0,
    val files: Int = 0,
    val locations: Int = 0,
) {
    fun increment(kind: MediaKind): PileCounts = when (kind) {
        MediaKind.IMAGE, MediaKind.VIDEO -> copy(photos = photos + 1)
        MediaKind.AUDIO -> copy(audios = audios + 1)
        MediaKind.TEXT -> copy(textes = textes + 1)
        MediaKind.PDF, MediaKind.UNKNOWN -> copy(files = files + 1)
    }
}

data class PileUi(
    val category: MediaCategory,
    val count: Int,
    val coverBlockId: Long?,
)

class NotePanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {

    private val repo: NoteRepository by lazy {
        val db = AppDatabase.get(activity)
        NoteRepository(db.noteDao(), db.attachmentDao(), db.blockReadDao(), blocksRepo)
    }

    var openNoteId: Long? = null
        private set

    private var currentNote: Note? = null

    private var topBubble: TopBubbleController? = null

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(activity)
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao  = db.noteDao(),
            linkDao  = db.blockLinkDao()   // ✅ liens AUDIO↔TEXTE / VIDEO↔TEXTE
        )
    }

    private val mediaActions = MediaActions(activity, blocksRepo)

    private var blocksJob: Job? = null
    // 🧊 Nouveau : on garde un handle pour l’observation de la note et on l’annule lors d’un open() suivant
    private var noteJob: Job? = null

    private val blockViews = mutableMapOf<Long, View>()
    private var pendingHighlightBlockId: Long? = null

    private val pileUiState = MutableStateFlow<List<PileUi>>(emptyList())

    private val mediaAdapter = MediaStripAdapter(
        onClick = { item -> mediaActions.handleClick(item) },
        onPileClick = { category ->
            openNoteId?.let { noteId ->
                mediaActions.handlePileClick(noteId, category)
            }
        },
        onLongPress = { view, item -> mediaActions.showMenu(view, item) }
    )

    var onPileCountsChanged: ((PileCounts) -> Unit)? = null
    var onOpenNoteChanged: ((Long?) -> Unit)? = null

    init {
        binding.mediaStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.mediaStrip.adapter = mediaAdapter
        binding.btnNoteMenu.setOnClickListener { view ->
            showNoteMenu(view)
        }
    }

    fun attachTopBubble(controller: TopBubbleController) {
        topBubble = controller
    }

    fun observePileUi(): Flow<List<PileUi>> = pileUiState.asStateFlow()

    fun currentPileUi(): List<PileUi> = pileUiState.value

    fun open(noteId: Long) {
        openNoteId = noteId
        onOpenNoteChanged?.invoke(noteId)
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

        onPileCountsChanged?.invoke(PileCounts())
        pileUiState.value = emptyList()

        // Reset visuel
        binding.txtBodyDetail.text = ""
        binding.noteMetaFooter.isGone = true
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        blockViews.clear()
        pendingHighlightBlockId = null
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true

        // 🔁 Annule l’observation précédente de la note (évite les écritures UI d’une ancienne note)
        noteJob?.cancel()
        noteJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteFlow(noteId).collectLatest { note ->
                    // Garde-fou : si l’onglet a changé entre temps, on ignore
                    if (openNoteId != noteId) return@collectLatest
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
                    // Garde-fou : si l’onglet a changé entre temps, on ignore
                    if (openNoteId != noteId) return@collectLatest
                    renderBlocks(blocks)
                }
            }
        }

        // Interactions locales
        binding.btnBack.setOnClickListener { close() }
        binding.txtTitleDetail.setOnClickListener { promptEditTitle() }
    }

    fun close() {
        openNoteId = null
        currentNote = null
        onOpenNoteChanged?.invoke(null)
        binding.notePanel.isGone = true
        binding.recycler.isVisible = true

        binding.txtBodyDetail.text = ""
        binding.noteMetaFooter.isGone = true

        // 🔚 Coupe proprement les collecteurs
        noteJob?.cancel()
        noteJob = null
        blocksJob?.cancel()
        blocksJob = null

        blockViews.clear()
        pendingHighlightBlockId = null
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true

        SimplePlayer.stop { }

        onPileCountsChanged?.invoke(PileCounts())
        pileUiState.value = emptyList()
    }

    private fun showNoteMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_CREATE_REMINDER, 0, activity.getString(R.string.note_menu_create_reminder)).apply {
            isEnabled = openNoteId != null
        }
        popup.menu.add(0, MENU_MERGE_WITH, 1, activity.getString(R.string.note_menu_merge_with)).apply {
            isEnabled = openNoteId != null
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CREATE_REMINDER -> {
                    val noteId = openNoteId ?: return@setOnMenuItemClickListener false
                    BottomSheetReminderPicker.newInstance(noteId)
                        .show(activity.supportFragmentManager, "reminder_picker")
                    true
                }
                MENU_MERGE_WITH -> {
                    promptMergeSelection()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun promptMergeSelection() {
        val targetId = openNoteId ?: return
        activity.lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                repo.allNotes.first()
            }.filter { note ->
                note.id != targetId && !note.isMerged
            }

            if (candidates.isEmpty()) {
                activity.toast(R.string.note_merge_empty)
                return@launch
            }

            val labels = candidates.map { formatMergeLabel(it) }.toTypedArray()
            val checked = BooleanArray(labels.size)
            val selected = mutableSetOf<Long>()

            AlertDialog.Builder(activity)
                .setTitle(R.string.note_merge_dialog_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val id = candidates[which].id
                    if (isChecked) {
                        selected += id
                    } else {
                        selected -= id
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.library_merge_positive) { _, _ ->
                    performMerge(targetId, selected.toList())
                }
                .show()
        }
    }

    private fun performMerge(targetId: Long, sourceIds: List<Long>) {
        if (sourceIds.isEmpty()) return

        activity.lifecycleScope.launch {
            topBubble?.show(activity.getString(R.string.note_merge_in_progress))
            Log.d(TAG, "mergeNotes() from panel — target=$targetId, sources=$sourceIds")

            runCatching {
                withContext(Dispatchers.IO) { repo.mergeNotes(sourceIds, targetId) }
            }.onSuccess { result ->
                if (result.mergedCount == 0) {
                    val msg = result.reason ?: activity.getString(R.string.note_merge_failed)
                    topBubble?.showFailure(msg)
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    return@onSuccess
                }

                val mergedSources = result.mergedSourceIds
                if (mergedSources.isNotEmpty()) {
                    notifyLibrarySourcesMerged(mergedSources)
                }

                val message = activity.getString(
                    R.string.library_merge_success_with_count,
                    result.mergedCount,
                    result.total
                )
                topBubble?.show(message)

                val transaction = result.transactionTimestamp?.let { ts ->
                    NoteRepository.MergeTransaction(targetId, result.mergedSourceIds, ts)
                }
                if (transaction != null) {
                    showUndoSnackbar(message, transaction)
                }
            }.onFailure { e ->
                Log.e(TAG, "mergeNotes() UI failed", e)
                topBubble?.showFailure(activity.getString(R.string.note_merge_failed))
                Snackbar.make(binding.root, activity.getString(R.string.note_merge_failed), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUndoSnackbar(message: String, tx: NoteRepository.MergeTransaction) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                activity.lifecycleScope.launch {
                    val success = runCatching {
                        withContext(Dispatchers.IO) { repo.undoMerge(tx) }
                    }.getOrDefault(false)
                    if (success) {
                        if (tx.sources.size == 1) {
                            open(tx.sources.first())
                        }
                        notifyLibraryMergeUndone()
                        topBubble?.show(activity.getString(R.string.library_merge_undo_success))
                        Snackbar.make(
                            binding.root,
                            activity.getString(R.string.library_merge_undo_success),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        Snackbar.make(
                            binding.root,
                            activity.getString(R.string.library_merge_undo_failed),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun formatMergeLabel(note: Note): String {
        val title = note.title?.takeIf { it.isNotBlank() }
        val body = note.body.trim().takeIf { it.isNotEmpty() }
        val content = title ?: body?.take(80) ?: activity.getString(R.string.library_merge_untitled_placeholder)
        return "#${note.id} • $content"
    }

    private fun notifyLibrarySourcesMerged(sourceIds: List<Long>) {
        if (sourceIds.isEmpty()) return
        val intent = Intent(LibraryFragment.ACTION_SOURCES_MERGED).apply {
            setPackage(activity.packageName)
            putExtra(LibraryFragment.EXTRA_MERGED_SOURCE_IDS, sourceIds.toLongArray())
        }
        activity.sendBroadcast(intent)
    }

    private fun notifyLibraryMergeUndone() {
        val intent = Intent(LibraryFragment.ACTION_REFRESH_LIBRARY).apply {
            setPackage(activity.packageName)
        }
        activity.sendBroadcast(intent)
    }

    companion object {
        private const val MENU_CREATE_REMINDER = 1
        private const val MENU_MERGE_WITH = 2
        private const val TAG = "MergeDiag"
    }

    fun onAppendLive(displayBody: String) {
        binding.txtBodyDetail.text = displayBody
    }

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
        // 🛡 Garde-fou : si entre temps on a changé de note, on ne touche pas à l’UI
        val nid = openNoteId ?: return

        val counts = PileCounts(
            photos = blocks.count { it.type == BlockType.PHOTO || it.type == BlockType.VIDEO },
            audios = blocks.count { it.type == BlockType.AUDIO },
            textes = blocks.count { it.type == BlockType.TEXT },
            files = blocks.count { it.type == BlockType.FILE },
            locations = blocks.count { it.type == BlockType.LOCATION },
        )
        onPileCountsChanged?.invoke(counts)

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
                BlockType.TEXT -> null
                BlockType.SKETCH,
                BlockType.PHOTO,
                BlockType.VIDEO,
                BlockType.AUDIO -> null

                // ⛔️ NE PLUS RENDRE DE CARTE “ROUTE” (on a déjà l’aperçu image dans la pellicule)
                BlockType.ROUTE -> null

                // idem : pas de carte “LOCATION” (l’aperçu image suffit)
                BlockType.LOCATION -> null

                // On garde un fallback uniquement pour FILE
                BlockType.FILE ->
                    BlockRenderers.createUnsupportedBlockView(container.context, block, margin)
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


    /**
     * Piles médias :
     *  - PHOTO = photos + vidéos (+ transcriptions TEXT liées aux vidéos)
     *  - AUDIO = audios + textes de transcription (TEXT partageant le groupId d’un audio)
     *  - TEXT  = textes indépendants (pas liés à un audio ni à une vidéo)
     *  - LOCATION = lieux + itinéraires (cover = snapshot si dispo, sinon fallback texte)
     *
     *  🔧 Ajustement : la pile TEXT prend désormais en compte les TEXT liés
     *  uniquement pour la COVER/ordre (tri), pas pour le COMPTEUR.
     */
    private fun updateMediaStrip(blocks: List<BlockEntity>) {
        val ctx = binding.root.context

        // Grouper par liens implicites via groupId
        val audioGroupIds = blocks.filter { it.type == BlockType.AUDIO }
            .mapNotNull { it.groupId }
            .toSet()
        val videoGroupIds = blocks.filter { it.type == BlockType.VIDEO }
            .mapNotNull { it.groupId }
            .toSet()

        val photoItems = mutableListOf<MediaStripItem.Image>()   // photos + vidéos
        val sketchItems = mutableListOf<MediaStripItem.Image>()
        val audioItems  = mutableListOf<MediaStripItem.Audio>()
        val textItems   = mutableListOf<MediaStripItem.Text>()   // ✅ textes SAISIS AU CLAVIER UNIQUEMENT
        val transcriptTextItems = mutableListOf<MediaStripItem.Text>() // textes liés (A/V) — exclus de la pile

        // 🗺️ Pile "Carte" : on agrège LOCATION + ROUTE
        val mapBlocks = blocks.filter { it.type == BlockType.LOCATION || it.type == BlockType.ROUTE }

        var transcriptsLinkedToAudio = 0
        var transcriptsLinkedToVideo = 0

        blocks.forEach { block ->
            when (block.type) {
                BlockType.PHOTO, BlockType.VIDEO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        photoItems += MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }
                }
                BlockType.SKETCH -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        sketchItems += MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }
                }
                BlockType.AUDIO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        audioItems += MediaStripItem.Audio(block.id, uri, block.mimeType, block.durationMs)
                    }
                }
                BlockType.TEXT -> {
                    val gid = block.groupId
                    val linkedToAudio = gid != null && gid in audioGroupIds
                    val linkedToVideo = gid != null && gid in videoGroupIds

                    when {
                        linkedToAudio -> {
                            transcriptsLinkedToAudio += 1
                            // On les collecte pour compter côté AUDIO/VIDEO, mais on ne les montrera pas dans la pile TEXT
                            transcriptTextItems += MediaStripItem.Text(block.id, block.noteId, block.text.orEmpty())
                        }
                        linkedToVideo -> {
                            transcriptsLinkedToVideo += 1
                            transcriptTextItems += MediaStripItem.Text(block.id, block.noteId, block.text.orEmpty())
                        }
                        else -> {
                            // ✅ TEXT sans groupId → considéré comme "post-it" saisi au clavier
                            textItems += MediaStripItem.Text(block.id, block.noteId, block.text.orEmpty())
                        }
                    }
                }
                else -> Unit
            }
        }

        val piles = buildList {
            if (photoItems.isNotEmpty()) {
                val sorted = photoItems.sortedByDescending { it.blockId }
                val countWithVideoTranscripts = sorted.size + transcriptsLinkedToVideo
                add(MediaStripItem.Pile(MediaCategory.PHOTO, countWithVideoTranscripts, sorted.first()))
            }
            if (sketchItems.isNotEmpty()) {
                val sorted = sketchItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.SKETCH, sorted.size, sorted.first()))
            }
            if (audioItems.isNotEmpty()) {
                val sorted = audioItems.sortedByDescending { it.blockId }
                val countWithTranscripts = sorted.size + transcriptsLinkedToAudio
                add(MediaStripItem.Pile(MediaCategory.AUDIO, countWithTranscripts, sorted.first()))
            }

            // ✅ PILE TEXT : uniquement si *vrais* post-its (textItems) existent.
            //    Aucun recours aux transcripts pour la cover ni l’ordre.
            if (textItems.isNotEmpty()) {
                val sortedStandalone = textItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.TEXT, sortedStandalone.size, sortedStandalone.first()))
            }

            // 🗺️ Pile "Carte" (LOCATION + ROUTE) — cover = snapshot si dispo
            if (mapBlocks.isNotEmpty()) {
                val sorted = mapBlocks.sortedByDescending { it.id }
                val coverImage: MediaStripItem.Image? = sorted.firstNotNullOfOrNull { b ->
                    val file = MapPreviewStorage.fileFor(ctx, b.id, b.type)
                    if (file.exists()) {
                        MediaStripItem.Image(
                            blockId = b.id,
                            mediaUri = file.absolutePath,
                            mimeType = "image/png",
                            type = b.type
                        )
                    } else null
                }
                val cover: MediaStripItem = coverImage ?: MediaStripItem.Text(
                    blockId = -999L,
                    noteId = openNoteId ?: 0L,
                    content = "Carte"
                )
                add(MediaStripItem.Pile(MediaCategory.LOCATION, mapBlocks.size, cover))
            }
        }.sortedByDescending { it.cover.blockId }

        val pileUi = piles.map { pile ->
            PileUi(category = pile.category, count = pile.count, coverBlockId = pile.cover.blockId)
        }

        pileUiState.value = pileUi
        mediaAdapter.submitList(piles)
        binding.mediaStrip.isGone = piles.isEmpty()
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

    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

    private fun render(note: Note?) {
        // 🛡 Ignore toute émission d’une note qui n’est plus ouverte (possible si réordonnancement)
        val openId = openNoteId
        if (note == null || openId == null || note.id != openId) return

        val title = note.title?.takeIf { it.isNotBlank() } ?: "Sans titre"
        binding.txtTitleDetail.text = title

        val keepCurrentStyled =
            (binding.txtBodyDetail.text is Spanned) &&
                    (binding.txtBodyDetail.text as Spanned).getSpans(
                        0,
                        binding.txtBodyDetail.text.length, StyleSpan::class.java
                    ).any { it.style == Typeface.ITALIC }

        if (!keepCurrentStyled) {
            binding.txtBodyDetail.text = note.body
        }

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
