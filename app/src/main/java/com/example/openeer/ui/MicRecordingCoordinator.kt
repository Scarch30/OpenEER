package com.example.openeer.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.openeer.audio.PcmRecorder
import com.example.openeer.stt.FinalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MicRecordingCoordinator(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLiveEvent(event: LiveTranscriber.TranscriptionEvent)
        fun onSegmentReady(result: SegmentResult)
        fun onRecordingError(error: Throwable)
    }

    data class SegmentResult(
        val wavPath: String?,
        val finalResult: FinalResult,
        val segmentDurationMs: Long?,
    )

    private var recorder: PcmRecorder? = null
    private var liveTranscriber: LiveTranscriber? = null
    private var segmentStartRealtime: Long? = null

    fun startSegment() {
        if (recorder != null) return
        try {
            liveTranscriber = LiveTranscriber(context).apply {
                onEvent = { event -> listener.onLiveEvent(event) }
                start()
            }
            recorder = PcmRecorder(context).also { rec ->
                rec.onPcmChunk = { chunk -> liveTranscriber?.feed(chunk) }
                rec.start()
            }
            segmentStartRealtime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Recorder.start()")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start recording", error)
            listener.onRecordingError(error)
            reset()
        }
    }

    suspend fun stopSegment(): SegmentResult {
        val rec = recorder
        recorder = null
        val live = liveTranscriber
        liveTranscriber = null
        rec?.onPcmChunk = null
        val durationMs = segmentStartRealtime?.let { SystemClock.elapsedRealtime() - it }
        segmentStartRealtime = null

        return try {
            val wavPath = withContext(Dispatchers.IO) {
                rec?.stop()
                rec?.finalizeToWav()
            }
            val finalResult = withContext(Dispatchers.IO) {
                live?.stopDetailed() ?: FinalResult.Empty
            }
            val result = SegmentResult(
                wavPath = wavPath,
                finalResult = finalResult,
                segmentDurationMs = durationMs,
            )
            listener.onSegmentReady(result)
            result
        } catch (error: Throwable) {
            listener.onRecordingError(error)
            reset()
            throw error
        }
    }

    fun reset() {
        recorder?.let {
            runCatching { it.stop() }
            it.onPcmChunk = null
        }
        recorder = null
        liveTranscriber?.let {
            runCatching { it.stopDetailed() }
        }
        liveTranscriber = null
        segmentStartRealtime = null
    }

    companion object {
        private const val TAG = "MicRecCoord"
    }
}
