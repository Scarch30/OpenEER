package com.example.openeer.data.search

import com.example.openeer.data.Note
import com.example.openeer.data.block.BlockReadDao
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.tag.TagDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchIndexer(
    private val searchDao: SearchDao,
    private val blockReadDao: BlockReadDao,   // ✅ remplace BlockDao
    private val tagDao: TagDao
) {
    suspend fun reindex(note: Note) = withContext(Dispatchers.IO) {
        val noteId = note.id
        val blocks = blockReadDao.getBlocksForNote(noteId)
        val tags = tagDao.getTagsForNote(noteId)

        val title = note.title.orEmpty()
        val body = note.body.orEmpty()

        val transcripts = buildString {
            for (b in blocks) {
                if (b.mimeType?.startsWith("audio") == true && !b.text.isNullOrBlank()) {
                    appendLine(b.text)
                }
            }
        }

        val tagsText = tags.joinToString(" ") { "#${it.name}" }
        val placesText = buildString {
            for (b in blocks) {
                if (b.placeName.isNullOrBlank()) continue
                if (b.type == BlockType.LOCATION || b.type == BlockType.ROUTE) {
                    appendLine(b.placeName)
                }
            }
        }

        searchDao.removeFromFts(noteId)
        searchDao.insertIntoFts(
            noteId = noteId,
            title = title,
            body = body,
            transcripts = transcripts,
            tagsText = tagsText,
            placesText = placesText
        )
    }
}
