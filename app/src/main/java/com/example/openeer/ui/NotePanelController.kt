package com.example.openeer.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.openeer.R
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Contrôle l'affichage de la "note ouverte" dans MainActivity (panel en haut de la liste).
 * - Observe la note + pièces jointes
 * - Met à jour le titre, corps, méta
 * - Expose open()/close()
 *
 * L’édition inline (clavier) est gérée par MainActivity.
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

    /** Dernière note rendue (pour partage, etc.) */
    private var currentNote: Note? = null

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(activity)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private var blocksJob: Job? = null
    private val blockViews = mutableMapOf<Long, View>()
    private var pendingHighlightBlockId: Long? = null

    private val mediaAdapter = MediaStripAdapter(
        onClick = { item -> handleMediaClick(item) },
        onLongPress = { view, item -> showMediaMenu(view, item) }
    )

    init {
        binding.mediaStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.mediaStrip.adapter = mediaAdapter
    }

    /** Ouvre visuellement le panneau et commence à observer une note. */
    fun open(noteId: Long) {
        openNoteId = noteId
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        blockViews.clear()
        pendingHighlightBlockId = null
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true

        // Observe la note
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteFlow(noteId).collectLatest { note ->
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
                    renderBlocks(blocks)
                }
            }
        }

        // Interactions locales
        binding.btnBack.setOnClickListener { close() }
        binding.txtTitleDetail.setOnClickListener { promptEditTitle() }
        // ⚠️ L’édition du corps (tap sur txtBodyDetail) est gérée dans MainActivity (inline edit).
    }

    /** Ferme le panneau et revient à la liste. */
    fun close() {
        openNoteId = null
        currentNote = null
        binding.notePanel.isGone = true
        binding.recycler.isVisible = true

        // RAZ visuelle pour éviter que le prochain "enterInlineEdit" lise l'ancien texte
        binding.txtBodyDetail.text = "(transcription en cours…)"
        binding.noteMetaFooter.isGone = true

        blocksJob?.cancel()
        blocksJob = null
        blockViews.clear()
        pendingHighlightBlockId = null
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true

        // Stopper toute lecture éventuelle (plus d'UI à mettre à jour ici)
        SimplePlayer.stop { }
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
                // On n’affiche plus les blocs TEXT : tout le texte vit dans Note.body
                BlockType.TEXT -> null
                BlockType.SKETCH, BlockType.PHOTO -> createImageBlockView(block, margin)
                BlockType.VIDEO, BlockType.ROUTE, BlockType.FILE -> createUnsupportedBlockView(block, margin)
                BlockType.AUDIO, BlockType.LOCATION -> null
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

    private fun updateMediaStrip(blocks: List<BlockEntity>) {
        val items = blocks.mapNotNull { block ->
            when (block.type) {
                BlockType.PHOTO, BlockType.SKETCH -> block.mediaUri?.takeIf { it.isNotBlank() }?.let {
                    MediaStripItem.Image(block.id, it, block.mimeType, block.type)
                }
                BlockType.AUDIO -> block.mediaUri?.takeIf { it.isNotBlank() }?.let {
                    MediaStripItem.Audio(block.id, it, block.mimeType, block.durationMs)
                }
                else -> null
            }
        }
        mediaAdapter.submitList(items)
        binding.mediaStrip.isGone = items.isEmpty()
    }

    // (la fabrique de “rectangle texte” reste ici au besoin, mais n’est plus utilisée)
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

    private fun handleMediaClick(item: MediaStripItem) {
        when (item) {
            is MediaStripItem.Image -> {
                val intent = Intent(activity, PhotoViewerActivity::class.java).apply {
                    putExtra("path", item.mediaUri)
                }
                activity.startActivity(intent)
            }
            is MediaStripItem.Audio -> playAudio(item)
        }
    }

    private fun showMediaMenu(anchor: View, item: MediaStripItem) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_SHARE, 0, activity.getString(R.string.media_action_share))
        popup.menu.add(0, MENU_DELETE, 1, activity.getString(R.string.media_action_delete))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_SHARE -> {
                    shareMedia(item)
                    true
                }
                MENU_DELETE -> {
                    confirmDeleteMedia(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareMedia(item: MediaStripItem) {
        val shareUri = resolveShareUri(item.mediaUri)
        if (shareUri == null) {
            Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when (item) {
            is MediaStripItem.Audio -> item.mimeType ?: "audio/*"
            is MediaStripItem.Image -> item.mimeType ?: "image/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val targets = activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        targets.forEach { info ->
            activity.grantUriPermission(info.activityInfo.packageName, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(activity, activity.getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteMedia(item: MediaStripItem) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ ->
                performDeleteMedia(item)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performDeleteMedia(item: MediaStripItem) {
        activity.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    deleteMediaFile(item.mediaUri)
                    blocksRepo.deleteBlock(item.blockId)
                }.isSuccess
            }
            if (success) {
                Toast.makeText(activity, activity.getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, activity.getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteMediaFile(rawUri: String) {
        runCatching {
            val uri = Uri.parse(rawUri)
            when {
                uri.scheme.isNullOrEmpty() -> File(rawUri).takeIf { it.exists() }?.delete()
                uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let { path ->
                    File(path).takeIf { it.exists() }?.delete()
                }
                uri.scheme.equals("content", ignoreCase = true) ->
                    activity.contentResolver.delete(uri, null, null)
                else -> uri.path?.let { path ->
                    File(path).takeIf { it.exists() }?.delete()
                }
            }
        }
    }

    private fun resolveShareUri(raw: String): Uri? {
        val parsed = Uri.parse(raw)
        return when {
            parsed.scheme.isNullOrEmpty() -> fileProviderUri(File(raw))
            parsed.scheme.equals("file", ignoreCase = true) -> parsed.path?.let { path ->
                fileProviderUri(File(path))
            }
            parsed.scheme.equals("content", ignoreCase = true) -> parsed
            else -> parsed
        }
    }

    private fun fileProviderUri(file: File): Uri? {
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun playAudio(item: MediaStripItem.Audio) {
        val raw = item.mediaUri
        if (raw.startsWith("content://")) {
            Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(raw)
        if (!file.exists()) {
            Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        SimplePlayer.play(
            ctx = activity,
            path = file.absolutePath,
            onStart = {
                Toast.makeText(activity, "Lecture…", Toast.LENGTH_SHORT).show()
            },
            onStop = {
                Toast.makeText(activity, "Lecture terminée", Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                Toast.makeText(activity, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
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
        // Plus de gestion de bouton Lecture ici (supprimé).
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

    private companion object {
        const val MENU_SHARE = 1
        const val MENU_DELETE = 2
    }
}

private sealed class MediaStripItem {
    abstract val blockId: Long
    abstract val mediaUri: String
    abstract val mimeType: String?

    data class Image(
        override val blockId: Long,
        override val mediaUri: String,
        override val mimeType: String?,
        val type: BlockType,
    ) : MediaStripItem()

    data class Audio(
        override val blockId: Long,
        override val mediaUri: String,
        override val mimeType: String?,
        val durationMs: Long?,
    ) : MediaStripItem()
}

private class MediaStripAdapter(
    private val onClick: (MediaStripItem) -> Unit,
    private val onLongPress: (View, MediaStripItem) -> Unit,
) : ListAdapter<MediaStripItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_AUDIO = 1

        private val DIFF = object : DiffUtil.ItemCallback<MediaStripItem>() {
            override fun areItemsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem.blockId == newItem.blockId

            override fun areContentsTheSame(oldItem: MediaStripItem, newItem: MediaStripItem): Boolean =
                oldItem == newItem
        }
    }

    private val durationCache = mutableMapOf<Long, String>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).blockId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is MediaStripItem.Audio -> TYPE_AUDIO
        is MediaStripItem.Image -> TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_AUDIO -> createAudioHolder(parent)
            else -> createImageHolder(parent)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ImageHolder -> holder.bind(getItem(position) as MediaStripItem.Image)
            is AudioHolder -> holder.bind(getItem(position) as MediaStripItem.Audio)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImageHolder) {
            Glide.with(holder.image).clear(holder.image)
        }
        super.onViewRecycled(holder)
    }

    override fun submitList(list: List<MediaStripItem>?) {
        if (list == null) {
            durationCache.clear()
        } else {
            val ids = list.map { it.blockId }.toSet()
            durationCache.keys.retainAll(ids)
        }
        super.submitList(list?.let { ArrayList(it) })
    }

    private fun createImageHolder(parent: ViewGroup): ImageHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val size = (160 * density).toInt()
        val margin = (8 * density).toInt()
        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                marginEnd = margin
            }
            radius = 20f
            cardElevation = 6f
            isClickable = true
            isFocusable = true
        }
        val image = ImageView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
        card.addView(image)
        return ImageHolder(card, image)
    }

    private fun createAudioHolder(parent: ViewGroup): AudioHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val height = (48 * density).toInt()
        val horizontalPadding = (16 * density).toInt()
        val verticalPadding = (8 * density).toInt()
        val margin = (8 * density).toInt()

        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                height
            ).apply {
                marginEnd = margin
            }
            radius = 40f
            cardElevation = 4f
            isClickable = true
            isFocusable = true
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
            setImageResource(android.R.drawable.ic_btn_speak_now)
        }

        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (8 * density).toInt()
            }
            textSize = 14f
        }

        row.addView(icon)
        row.addView(text)
        card.addView(row)

        return AudioHolder(card, text)
    }

    inner class ImageHolder(val card: MaterialCardView, val image: ImageView) :
        RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem.Image) {
            Glide.with(image)
                .load(item.mediaUri)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(image)

            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    inner class AudioHolder(val card: MaterialCardView, private val text: TextView) :
        RecyclerView.ViewHolder(card) {
        fun bind(item: MediaStripItem.Audio) {
            text.text = resolveDuration(card.context, item)
            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }

    private fun resolveDuration(context: Context, item: MediaStripItem.Audio): String {
        durationCache[item.blockId]?.let { return it }

        val label = item.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) }
            ?: extractDuration(context, item.mediaUri)
            ?: "--:--"

        durationCache[item.blockId] = label
        return label
    }

    private fun extractDuration(context: Context, rawUri: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(rawUri)
            when {
                uri.scheme.isNullOrEmpty() -> retriever.setDataSource(rawUri)
                uri.scheme.equals("content", ignoreCase = true) ||
                        uri.scheme.equals("file", ignoreCase = true) -> retriever.setDataSource(context, uri)
                else -> retriever.setDataSource(rawUri)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            duration?.let { formatDuration(it) }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
