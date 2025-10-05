package com.example.openeer.ui.player

import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.ui.SimplePlayer
import kotlin.math.max
import kotlin.math.min

/**
 * Lie un view d'item audio (item_block_audio.xml) au SimplePlayer.
 * Usage: dans ton AudioHolder.bind(block) -> AudioBinder.bind(itemView, block)
 */
object AudioBinder {

    fun bind(itemView: View, block: BlockEntity) {
        require(block.type == BlockType.AUDIO) { "AudioBinder sur un bloc non-audio" }

        val btn = itemView.findViewById<ImageButton>(R.id.btnPlayPause)
        val seek = itemView.findViewById<SeekBar>(R.id.seek)
        val txtElapsed = itemView.findViewById<TextView>(R.id.txtElapsed)
        val txtDuration = itemView.findViewById<TextView>(R.id.txtDuration)
        val txtTitle = itemView.findViewById<TextView>(R.id.txtAudioTitle)
        val preview = itemView.findViewById<TextView>(R.id.txtTranscriptPreview)

        txtTitle.text = "Audio – ${(block.durationMs ?: 0L) / 1000}s"
        preview.text = block.text ?: ""

        fun msToMinSec(ms: Int): String {
            val totalSec = max(0, ms / 1000)
            val m = totalSec / 60
            val s = totalSec % 60
            return "$m:${s.toString().padStart(2, '0')}"
        }

        fun renderPlayIcon(playing: Boolean) {
            btn.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        }

        // Init état
        renderPlayIcon(SimplePlayer.isPlaying(block.id))
        val dur = max(SimplePlayer.duration(), (block.durationMs ?: 0L).toInt())
        seek.max = 1000
        val cur = SimplePlayer.currentPosition()
        if (dur > 0) {
            seek.progress = min(1000, (cur * 1000f / dur).toInt())
        } else {
            seek.progress = 0
        }
        txtElapsed.text = msToMinSec(cur)
        txtDuration.text = "/ ${msToMinSec(dur)}"

        // Callbacks globaux (pour que le holder se mette à jour quand ça bouge)
        SimplePlayer.setCallbacks(
            onStart = { id ->
                if (id == block.id) renderPlayIcon(true)
            },
            onProgress = { st ->
                if (st.currentId == block.id) {
                    val d = if (st.durationMs > 0) st.durationMs else dur
                    val prog = if (d > 0) (st.positionMs * 1000f / d).toInt() else 0
                    seek.progress = prog.coerceIn(0, 1000)
                    txtElapsed.text = msToMinSec(st.positionMs)
                    txtDuration.text = "/ ${msToMinSec(d)}"
                }
            },
            onPause = { id ->
                if (id == block.id) renderPlayIcon(false)
            },
            onComplete = { id ->
                if (id == block.id) {
                    renderPlayIcon(false)
                    seek.progress = 0
                    txtElapsed.text = "0:00"
                }
            }
        )

        // Play/Pause
        btn.setOnClickListener {
            val ctx = itemView.context
            val uri = block.mediaUri ?: return@setOnClickListener
            if (SimplePlayer.isPlaying(block.id)) {
                SimplePlayer.pause()
            } else {
                SimplePlayer.play(ctx, block.id, uri)
            }
        }

        // Seek
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var user = false
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = SimplePlayer.duration().takeIf { it > 0 }
                        ?: (block.durationMs ?: 0L).toInt()
                    val target = (progress / 1000f * d).toInt()
                    txtElapsed.text = msToMinSec(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { user = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (!user) return
                user = false
                val d = SimplePlayer.duration().takeIf { it > 0 }
                    ?: (block.durationMs ?: 0L).toInt()
                val target = (seek.progress / 1000f * d).toInt()
                SimplePlayer.seekTo(target)
            }
        })
    }
}
