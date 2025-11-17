package com.example.openeer.ui

import android.text.SpannableStringBuilder
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderTranscriptionCleanerTest {

    @MockK(relaxUnitFun = true)
    private lateinit var listManager: ListProvisionalManager

    @MockK(relaxUnitFun = true)
    private lateinit var bodyManager: BodyTranscriptionManager

    @MockK(relaxUnitFun = true)
    private lateinit var voiceCommandHandler: VoiceCommandHandler

    private val buffer = mockk<ProvisionalBodyBuffer>(relaxed = true)

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { bodyManager.buffer } returns buffer
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `discard removes provisional body text when note editor session exists`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleaner = ReminderTranscriptionCleaner(
            listManager = listManager,
            bodyManager = bodyManager,
            voiceCommandHandler = voiceCommandHandler,
            mainDispatcher = dispatcher,
        )
        val blockId = 42L
        val removedRange = IntRange(0, 3)

        every { listManager.has(blockId) } returns false
        every { buffer.removeCurrentSession() } returns removedRange
        every { bodyManager.onProvisionalRangeRemoved(blockId, removedRange) } just runs
        every { buffer.ensureSpannable() } returns SpannableStringBuilder("abc")
        every { bodyManager.maybeCommitBody() } just runs
        coEvery { voiceCommandHandler.cleanupVoiceCaptureReferences(blockId) } just runs
        every { voiceCommandHandler.scheduleVoiceCaptureCleanup(blockId, "/tmp/audio.wav") } just runs

        cleaner.discard(blockId, "/tmp/audio.wav", "req-1")

        verify { buffer.removeCurrentSession() }
        verify { bodyManager.onProvisionalRangeRemoved(blockId, removedRange) }
        verify { buffer.ensureSpannable() }
        verify { bodyManager.maybeCommitBody() }
        coVerify { voiceCommandHandler.cleanupVoiceCaptureReferences(blockId) }
        verify { voiceCommandHandler.scheduleVoiceCaptureCleanup(blockId, "/tmp/audio.wav") }
        coVerify(exactly = 0) {
            listManager.removeProvisionalForBlock(any(), any(), any())
        }
    }

    @Test
    fun `discard removes provisional list entries when needed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleaner = ReminderTranscriptionCleaner(
            listManager = listManager,
            bodyManager = bodyManager,
            voiceCommandHandler = voiceCommandHandler,
            mainDispatcher = dispatcher,
        )
        val blockId = 7L

        every { listManager.has(blockId) } returns true
        coEvery {
            listManager.removeProvisionalForBlock(
                blockId,
                ProvisionalRemovalReason.CANCEL,
                "req-99",
            )
        } returns true
        coEvery { voiceCommandHandler.cleanupVoiceCaptureReferences(blockId) } just runs
        every { voiceCommandHandler.scheduleVoiceCaptureCleanup(blockId, "audio.wav") } just runs

        cleaner.discard(blockId, "audio.wav", "req-99")

        coVerify {
            listManager.removeProvisionalForBlock(
                blockId,
                ProvisionalRemovalReason.CANCEL,
                "req-99",
            )
        }
        verify(exactly = 0) { buffer.removeCurrentSession() }
        coVerify { voiceCommandHandler.cleanupVoiceCaptureReferences(blockId) }
        verify { voiceCommandHandler.scheduleVoiceCaptureCleanup(blockId, "audio.wav") }
    }
}
