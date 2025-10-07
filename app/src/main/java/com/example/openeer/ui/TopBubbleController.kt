package com.example.openeer.ui

import androidx.core.view.isVisible
import com.example.openeer.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TopBubbleController(
    private val binding: ActivityMainBinding,
    private val scope: CoroutineScope
) {
    private var hideJob: Job? = null

    fun show(message: String, durationMs: Long = DEFAULT_DURATION_MS) {
        hideJob?.cancel()
        binding.topBubbleText.text = message
        binding.topBubble.isVisible = true
        hideJob = scope.launch {
            delay(durationMs)
            binding.topBubble.isVisible = false
        }
    }

    fun showFailure(message: String) {
        show(message, FAILURE_DURATION_MS)
    }

    fun hide() {
        hideJob?.cancel()
        hideJob = null
        binding.topBubble.isVisible = false
    }

    companion object {
        private const val DEFAULT_DURATION_MS = 2500L
        private const val FAILURE_DURATION_MS = 4500L
    }
}
