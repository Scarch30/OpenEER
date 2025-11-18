package com.example.openeer.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.example.openeer.core.RecordingState
import com.example.openeer.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Contrôle l'état UI de la barre micro : textes, animations et indicateurs temporaires.
 */
class MicUiStateController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    enum class Mode {
        Idle,
        RecordingPressToTalk,
        RecordingHandsFree,
    }

    data class UiState(
        val mode: Mode,
        val recordingState: RecordingState,
        val label: CharSequence,
        val indicator: CharSequence,
        val iconAlpha: Float,
        val emittedAt: Long,
    )

    data class IndicatorOverride(
        val message: CharSequence,
        val expiresAt: Long,
    )

    fun interface Listener {
        fun onUiStateChanged(state: UiState)
    }

    private val listeners = LinkedHashSet<Listener>()
    private var indicatorOverride: IndicatorOverride? = null
    private var indicatorJob: Job? = null
    private var waveformAnimator: ValueAnimator? = null
    private var currentMode: Mode = Mode.Idle
    private var recordingState: RecordingState = RecordingState.IDLE

    init {
        applyUiState(buildState(Mode.Idle))
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onUiStateChanged(buildState(currentMode))
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun recordingState(): RecordingState = recordingState

    fun isIdle(): Boolean = recordingState == RecordingState.IDLE

    fun isRecording(): Boolean =
        recordingState == RecordingState.RECORDING_PTT ||
            recordingState == RecordingState.RECORDING_HANDS_FREE

    fun isRecordingPtt(): Boolean = recordingState == RecordingState.RECORDING_PTT

    fun isRecordingHandsFree(): Boolean = recordingState == RecordingState.RECORDING_HANDS_FREE

    fun onPressToTalkStarted() {
        transitionTo(Mode.RecordingPressToTalk)
    }

    fun onHandsFreeEngaged() {
        if (!isRecordingPtt()) return
        transitionTo(Mode.RecordingHandsFree)
    }

    fun onRecordingStopped() {
        if (isIdle()) return
        transitionTo(Mode.Idle)
    }

    fun showTransientIndicator(text: CharSequence, durationMs: Long = DEFAULT_INDICATOR_DURATION_MS) {
        if (text.isBlank()) return
        indicatorJob?.cancel()
        val expiresAt = clock() + durationMs
        indicatorOverride = IndicatorOverride(text, expiresAt)
        binding.txtActivity.text = text
        indicatorJob = scope.launch {
            delay(durationMs)
            indicatorOverride = null
            applyIndicatorText()
            Log.d(TAG, "indicator_reset duration=$durationMs")
        }
        Log.d(TAG, "indicator_override duration=$durationMs text=\"${text.take(32)}\"")
    }

    private fun transitionTo(mode: Mode) {
        if (currentMode == mode) {
            applyUiState(buildState(mode))
            return
        }
        currentMode = mode
        val newState = buildState(mode)
        recordingState = newState.recordingState
        if (mode != Mode.Idle) {
            cancelIndicatorOverride()
        }
        applyUiState(newState)
        listeners.forEach { listener -> listener.onUiStateChanged(newState) }
        Log.d(TAG, "ui_state=${mode.name}")
    }

    private fun buildState(mode: Mode): UiState {
        val label = when (mode) {
            Mode.Idle -> activity.getString(R.string.mic_label_idle)
            Mode.RecordingPressToTalk -> activity.getString(R.string.mic_label_recording_ptt)
            Mode.RecordingHandsFree -> activity.getString(R.string.mic_label_recording_hands_free)
        }
        val indicator = when (mode) {
            Mode.Idle -> activity.getString(R.string.mic_indicator_idle)
            Mode.RecordingPressToTalk -> activity.getString(R.string.mic_indicator_recording_ptt)
            Mode.RecordingHandsFree -> activity.getString(R.string.mic_indicator_recording_hands_free)
        }
        val alpha = if (mode == Mode.Idle) 0.9f else 1f
        val recordingState = when (mode) {
            Mode.Idle -> RecordingState.IDLE
            Mode.RecordingPressToTalk -> RecordingState.RECORDING_PTT
            Mode.RecordingHandsFree -> RecordingState.RECORDING_HANDS_FREE
        }
        return UiState(mode, recordingState, label, indicator, alpha, clock())
    }

    private fun applyUiState(state: UiState) {
        binding.labelMic.text = state.label
        binding.iconMic.alpha = state.iconAlpha
        if (indicatorOverride == null) {
            binding.txtActivity.text = state.indicator
        }
        if (state.mode == Mode.Idle) {
            stopWaveformAnimation()
        } else {
            startWaveformAnimation()
        }
    }

    private fun applyIndicatorText() {
        val override = indicatorOverride
        if (override != null) {
            binding.txtActivity.text = override.message
        } else {
            binding.txtActivity.text = when (currentMode) {
                Mode.Idle -> activity.getString(R.string.mic_indicator_idle)
                Mode.RecordingPressToTalk -> activity.getString(R.string.mic_indicator_recording_ptt)
                Mode.RecordingHandsFree -> activity.getString(R.string.mic_indicator_recording_hands_free)
            }
        }
    }

    private fun cancelIndicatorOverride() {
        indicatorJob?.cancel()
        indicatorJob = null
        indicatorOverride = null
    }

    private fun startWaveformAnimation() {
        if (waveformAnimator != null) return
        waveformAnimator = ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = WAVEFORM_ANIMATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                binding.iconMic.scaleX = scale
                binding.iconMic.scaleY = scale
            }
            start()
        }
        Log.d(TAG, "waveform_start")
    }

    private fun stopWaveformAnimation() {
        waveformAnimator?.cancel()
        waveformAnimator = null
        binding.iconMic.scaleX = 1f
        binding.iconMic.scaleY = 1f
        Log.d(TAG, "waveform_stop")
    }

    companion object {
        private const val TAG = "MicUiState"
        const val DEFAULT_INDICATOR_DURATION_MS = 3_000L
        private const val WAVEFORM_ANIMATION_DURATION_MS = 800L
    }
}
