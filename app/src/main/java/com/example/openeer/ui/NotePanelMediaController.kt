package com.example.openeer.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.library.MapPreviewStorage
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.panel.media.MediaStripAdapter
import com.example.openeer.ui.panel.media.MediaStripItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotePanelMediaController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    blocksRepository: BlocksRepository,
) {
    var onPileCountsChanged: ((PileCounts) -> Unit)? = null
    var openNoteIdProvider: () -> Long? = { null }

    private val mediaActions = MediaActions(activity, blocksRepository)
    private val mediaAdapter = MediaStripAdapter(
        onClick = { item -> mediaActions.handleClick(item) },
        onPileClick = { category ->
            openNoteIdProvider()?.let { noteId ->
                mediaActions.handlePileClick(noteId, category)
            }
        },
        onLongPress = { view, item -> mediaActions.showMenu(view, item) },
    )

    private val pileUiState = MutableStateFlow<List<PileUi>>(emptyList())

    init {
        binding.mediaStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.mediaStrip.adapter = mediaAdapter
    }

    fun observePileUi(): Flow<List<PileUi>> = pileUiState.asStateFlow()

    fun currentPileUi(): List<PileUi> = pileUiState.value

    fun reset() {
        onPileCountsChanged?.invoke(PileCounts())
        pileUiState.value = emptyList()
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true
    }

    fun render(blocks: List<BlockEntity>) {
        val counts = PileCounts(
            photos = blocks.count { it.type == BlockType.PHOTO || it.type == BlockType.VIDEO },
            audios = blocks.count { it.type == BlockType.AUDIO },
            textes = blocks.count { it.type == BlockType.TEXT },
            files = blocks.count { it.type == BlockType.FILE },
            locations = blocks.count { it.type == BlockType.LOCATION },
        )
        onPileCountsChanged?.invoke(counts)
        updateMediaStrip(blocks)
    }

    private fun updateMediaStrip(blocks: List<BlockEntity>) {
        val ctx = binding.root.context

        val audioGroupIds = blocks.filter { it.type == BlockType.AUDIO }
            .mapNotNull { it.groupId }
            .toSet()
        val videoGroupIds = blocks.filter { it.type == BlockType.VIDEO }
            .mapNotNull { it.groupId }
            .toSet()

        val photoItems = mutableListOf<MediaStripItem.Image>()
        val sketchItems = mutableListOf<MediaStripItem.Image>()
        val audioItems = mutableListOf<MediaStripItem.Audio>()
        val textItems = mutableListOf<MediaStripItem.Text>()
        val mapBlocks = blocks.filter { it.type == BlockType.LOCATION || it.type == BlockType.ROUTE }

        var transcriptsLinkedToAudio = 0
        var transcriptsLinkedToVideo = 0

        blocks.forEach { block ->
            when (block.type) {
                BlockType.PHOTO, BlockType.VIDEO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        photoItems += MediaStripItem.Image(
                            blockId = block.id,
                            mediaUri = uri,
                            mimeType = block.mimeType,
                            type = block.type,
                            childOrdinal = block.childOrdinal,
                            childName = block.childName,
                        )
                    }
                }
                BlockType.SKETCH -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        sketchItems += MediaStripItem.Image(
                            blockId = block.id,
                            mediaUri = uri,
                            mimeType = block.mimeType,
                            type = block.type,
                            childOrdinal = block.childOrdinal,
                            childName = block.childName,
                        )
                    }
                }
                BlockType.AUDIO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        audioItems += MediaStripItem.Audio(
                            blockId = block.id,
                            mediaUri = uri,
                            mimeType = block.mimeType,
                            durationMs = block.durationMs,
                            childOrdinal = block.childOrdinal,
                            childName = block.childName,
                        )
                    }
                }
                BlockType.TEXT -> {
                    val gid = block.groupId
                    val linkedToAudio = gid != null && gid in audioGroupIds
                    val linkedToVideo = gid != null && gid in videoGroupIds
                    when {
                        linkedToAudio -> {
                            transcriptsLinkedToAudio += 1
                        }
                        linkedToVideo -> {
                            transcriptsLinkedToVideo += 1
                        }
                        else -> {
                            textItems += MediaStripItem.Text(
                                blockId = block.id,
                                noteId = block.noteId,
                                content = block.text.orEmpty(),
                                isList = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST,
                                childOrdinal = block.childOrdinal,
                                childName = block.childName,
                            )
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
            if (textItems.isNotEmpty()) {
                val sortedStandalone = textItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.TEXT, sortedStandalone.size, sortedStandalone.first()))
            }
            if (mapBlocks.isNotEmpty()) {
                val sorted = mapBlocks.sortedByDescending { it.id }
                val coverImage: MediaStripItem.Image? = sorted.firstNotNullOfOrNull { block ->
                    val file = MapPreviewStorage.fileFor(ctx, block.id, block.type)
                    if (file.exists()) {
                        MediaStripItem.Image(
                            blockId = block.id,
                            mediaUri = file.absolutePath,
                            mimeType = "image/png",
                            type = block.type,
                            childOrdinal = block.childOrdinal,
                            childName = block.childName,
                        )
                    } else {
                        null
                    }
                }
                val coverBlock = sorted.first()
                val cover: MediaStripItem = coverImage ?: MediaStripItem.Text(
                    blockId = coverBlock.id,
                    noteId = openNoteIdProvider() ?: 0L,
                    content = "Carte",
                    isList = false,
                    childOrdinal = coverBlock.childOrdinal,
                    childName = coverBlock.childName,
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
}
