package com.example.openeer.ui.import

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.rules.MainDispatcherRule
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class MediaImporterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var blocksRepo: BlocksRepository
    private lateinit var importer: MediaImporter
    private var noteId: Long = 0L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        blocksRepo = BlocksRepository(db.blockDao(), db.noteDao())
        importer = MediaImporter(
            context = context,
            blocksRepo = blocksRepo,
            whisperTranscribe = { "stub transcription" }
        )
        noteId = runBlocking { db.noteDao().insert(Note()) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importImageCreatesPhotoAndPlaceholderText() = runTest {
        val uri = Uri.parse("content://test/image.jpg")
        ShadowContentResolver.registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))

        val result = importer.import(noteId, uri, overrideMimeType = "image/jpeg")
        assertNotNull(result)

        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(2, blocks.size)
        assertEquals(BlockType.PHOTO, blocks[0].type)
        assertEquals(BlockType.TEXT, blocks[1].type)
        assertEquals(blocks[0].groupId, blocks[1].groupId)
        assertEquals(OCR_PLACEHOLDER_TEXT, blocks[1].text)
        assertEquals(blocks[1].id, result?.highlightBlockId)
    }

    @Test
    fun importAudioCreatesAudioAndTranscribedText() = runTest {
        val uri = Uri.parse("content://test/audio.wav")
        ShadowContentResolver.registerInputStream(uri, ByteArrayInputStream(ByteArray(8)))

        val result = importer.import(noteId, uri, overrideMimeType = "audio/wav")
        assertNotNull(result)

        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(2, blocks.size)
        val audioBlock = blocks.first { it.type == BlockType.AUDIO }
        val textBlock = blocks.first { it.type == BlockType.TEXT }
        assertEquals(audioBlock.groupId, textBlock.groupId)
        assertFalse(textBlock.text.isNullOrBlank())
        assertEquals("stub transcription", textBlock.text)
        assertEquals("stub transcription", audioBlock.text)
        assertEquals(textBlock.id, result?.highlightBlockId)
    }
}
