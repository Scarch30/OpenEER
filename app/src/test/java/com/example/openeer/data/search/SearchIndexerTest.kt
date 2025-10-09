package com.example.openeer.data.search

import com.example.openeer.data.Note
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockReadDao
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.tag.NoteTagCrossRef
import com.example.openeer.data.tag.TagDao
import com.example.openeer.data.tag.TagEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexerTest {

    @Test
    fun `reindex indexes place names from location and route blocks`() = runBlocking {
        val searchDao = FakeSearchDao()
        val blockDao = FakeBlockReadDao(
            listOf(
                block(
                    id = 1L,
                    type = BlockType.TEXT,
                    placeName = "Text block place"
                ),
                block(
                    id = 2L,
                    type = BlockType.LOCATION,
                    placeName = "Parc naturel régional"
                ),
                block(
                    id = 3L,
                    type = BlockType.ROUTE,
                    placeName = "GR34"
                )
            )
        )
        val tagDao = FakeTagDao()
        val indexer = SearchIndexer(searchDao, blockDao, tagDao)

        indexer.reindex(Note(id = 42L, body = ""))

        val inserted = searchDao.lastInsert
        assertNotNull(inserted)
        inserted!!
        assertTrue(inserted.placesText.contains("Parc naturel régional"))
        assertTrue(inserted.placesText.contains("GR34"))
        assertFalse(inserted.placesText.contains("Text block place"))
    }

    private fun block(
        id: Long,
        type: BlockType,
        placeName: String?
    ) = BlockEntity(
        id = id,
        noteId = 42L,
        type = type,
        position = id.toInt(),
        text = null,
        mediaUri = null,
        mimeType = null,
        durationMs = null,
        width = null,
        height = null,
        lat = null,
        lon = null,
        placeName = placeName,
        routeJson = null,
        extra = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    private class FakeSearchDao : SearchDao {
        data class Insert(
            val noteId: Long,
            val title: String,
            val body: String,
            val transcripts: String,
            val tagsText: String,
            val placesText: String
        )

        var lastInsert: Insert? = null
        val removedIds = mutableListOf<Long>()

        override suspend fun removeFromFts(noteId: Long) {
            removedIds += noteId
        }

        override suspend fun insertIntoFts(
            noteId: Long,
            title: String,
            body: String,
            transcripts: String,
            tagsText: String,
            placesText: String
        ) {
            lastInsert = Insert(noteId, title, body, transcripts, tagsText, placesText)
        }

        override suspend fun searchText(ftsQuery: String) = emptyList<Note>()

        override suspend fun searchTextWithTags(ftsQuery: String, tagNames: List<String>) = emptyList<Note>()

        override suspend fun searchByTags(tagNames: List<String>) = emptyList<Note>()

        override suspend fun searchByGeoBounds(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double
        ) = emptyList<Note>()

        override suspend fun searchLike(q: String) = emptyList<Note>()
    }

    private class FakeBlockReadDao(
        private val blocks: List<BlockEntity>
    ) : BlockReadDao {
        override suspend fun getBlocksForNote(noteId: Long): List<BlockEntity> = blocks

        override suspend fun getById(id: Long) = blocks.firstOrNull { it.id == id }
    }

    private class FakeTagDao : TagDao {
        override suspend fun insertTag(tag: TagEntity) = 0L

        override suspend fun getByNames(names: List<String>) = emptyList<TagEntity>()

        override suspend fun attach(noteTags: List<NoteTagCrossRef>) = Unit

        override suspend fun getTagsForNote(noteId: Long) = emptyList<TagEntity>()
    }
}
