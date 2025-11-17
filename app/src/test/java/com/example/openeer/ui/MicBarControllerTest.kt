package com.example.openeer.ui

import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.dialogs.UnknownPlaceDialog
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.voice.VoiceComponents
import com.example.openeer.voice.VoiceDependencies
import com.example.openeer.voice.VoiceRouteDecision
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MicBarControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var activity: AppCompatActivity
    private lateinit var binding: ActivityMainBinding

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        binding = ActivityMainBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        mockkObject(VoiceComponents)
        every { VoiceComponents.obtain(any()) } returns VoiceDependencies(
            favoritesService = mockk(relaxed = true),
            placeParser = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `registerReminderError cleans transcription before unknown place dialog`() =
        runTest(testDispatcher.scheduler) {
            val reminderCleanup = mockk<ReminderTranscriptionCleaner>()
            val controller = buildController(reminderCleanup)
            var cleanupDone = false
            coEvery { reminderCleanup.discard(any(), any(), any()) } answers {
                cleanupDone = true
                Unit
            }
            mockkObject(UnknownPlaceDialog)
            val labelSlot = slot<String>()
            every {
                UnknownPlaceDialog.show(
                    activity = activity,
                    label = capture(labelSlot),
                    onCreateFavorite = any(),
                    onCancel = any(),
                    builderFactory = any(),
                )
            } answers {
                assertTrue(cleanupDone)
            }

            val error = VoiceCommandHandler.ReminderCommandError(
                type = VoiceCommandHandler.ReminderCommandErrorType.FAVORITE_NOT_FOUND,
                disputedLabel = "Boulangerie",
            )

            controller.registerReminderError(
                audioBlockId = 321L,
                noteId = 42L,
                refinedText = "Rappel boulangerie",
                audioPath = "/tmp/audio.wav",
                decision = VoiceRouteDecision.ReminderError(
                    errorType = VoiceRouteDecision.ReminderErrorType.FAVORITE_NOT_FOUND,
                    disputedLabel = "Boulangerie",
                ),
                sessionBaseline = null,
                commitContext = buildCommitContext(),
                reqId = "req-clean",
                intentKey = "intent-clean",
                error = error,
            )

            advanceUntilIdle()

            assertEquals("Boulangerie", labelSlot.captured)
            coVerify(exactly = 1) {
                reminderCleanup.discard(321L, "/tmp/audio.wav", "req-clean")
            }
        }

    @Test
    fun `unknown place dialog forwards label to favorite creation flow`() =
        runTest(testDispatcher.scheduler) {
            val reminderCleanup = mockk<ReminderTranscriptionCleaner>(relaxed = true)
            val controller = buildController(reminderCleanup)
            mockkObject(UnknownPlaceDialog)
            val createSlot = slot<() -> Unit>()
            every {
                UnknownPlaceDialog.show(
                    activity = activity,
                    label = any(),
                    onCreateFavorite = capture(createSlot),
                    onCancel = any(),
                    builderFactory = any(),
                )
            } answers { }

            val error = VoiceCommandHandler.ReminderCommandError(
                type = VoiceCommandHandler.ReminderCommandErrorType.FAVORITE_NOT_FOUND,
                disputedLabel = "Chez Paul",
            )

            controller.registerReminderError(
                audioBlockId = 654L,
                noteId = 99L,
                refinedText = "Rappel boulangerie",
                audioPath = "/tmp/audio2.wav",
                decision = VoiceRouteDecision.ReminderError(
                    errorType = VoiceRouteDecision.ReminderErrorType.FAVORITE_NOT_FOUND,
                    disputedLabel = "Chez Paul",
                ),
                sessionBaseline = null,
                commitContext = buildCommitContext(),
                reqId = "req-launch",
                intentKey = "intent-launch",
                error = error,
            )

            advanceUntilIdle()

            val createFavorite = createSlot.captured
            createFavorite.invoke()
            advanceUntilIdle()

            val startedIntent = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(MapActivity::class.java.name, startedIntent.component?.className)
            assertEquals(
                "Chez Paul",
                startedIntent.getStringExtra(MapActivity.EXTRA_INITIAL_SEARCH_QUERY),
            )
            coVerify(exactly = 1) {
                reminderCleanup.discard(654L, "/tmp/audio2.wav", "req-launch")
            }
        }

    private fun buildController(
        reminderCleanup: ReminderTranscriptionCleaner,
    ): MicBarController {
        val repo = mockk<NoteRepository>(relaxed = true)
        val blocksRepo = mockk<BlocksRepository>(relaxed = true)
        return MicBarController(
            activity = activity,
            binding = binding,
            repo = repo,
            blocksRepo = blocksRepo,
            getOpenNoteId = { 1L },
            getOpenNote = { null },
            onAppendLive = {},
            onReplaceFinal = { _, _ -> },
            showTopBubble = {},
            reminderCleanupOverride = reminderCleanup,
        )
    }

    private fun buildCommitContext(): BodyTranscriptionManager.DictationCommitContext {
        return BodyTranscriptionManager.DictationCommitContext(
            mode = BodyTranscriptionManager.DictationCommitMode.VOSK,
            baselineHash = null,
            intentKey = null,
            reconciled = false,
            baselineBody = null,
        )
    }
}
