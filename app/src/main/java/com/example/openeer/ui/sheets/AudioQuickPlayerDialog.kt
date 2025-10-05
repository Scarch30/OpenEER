package com.example.openeer.ui.sheets

import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.example.openeer.ui.SimplePlayer
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView

class AudioQuickPlayerDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        private const val ARG_SRC = "arg_src"

        fun show(fm: FragmentManager, id: Long, src: String) {
            AudioQuickPlayerDialog().apply {
                arguments = bundleOf(ARG_ID to id, ARG_SRC to src)
            }.show(fm, "audio_quick_player")
        }
    }

    private val audioId: Long by lazy { requireArguments().getLong(ARG_ID) }
    private val source: String by lazy { requireArguments().getString(ARG_SRC).orEmpty() }

    private var playPause: ImageView? = null
    private var seek: SeekBar? = null
    private var elapsed: TextView? = null
    private var duration: TextView? = null
    private var title: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // UI dynamique minimaliste
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            val p = dp(12)
            setContentPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
        }
        val parent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPause = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_media_play)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        title = TextView(ctx).apply {
            text = "Audio"
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        elapsed = TextView(ctx).apply { text = "0:00" }
        duration = TextView(ctx).apply {
            text = "/ 0:00"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
            }
        }
        seek = SeekBar(ctx).apply {
            max = 1000
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        row.addView(playPause)
        row.addView(title)
        row.addView(elapsed)
        row.addView(duration)
        parent.addView(row)
        parent.addView(seek)
        card.addView(parent)

        return card
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)

        // callbacks lecteur
        SimplePlayer.setCallbacks(
            onStart = { id ->
                if (id == audioId) playPause?.setImageResource(android.R.drawable.ic_media_pause)
            },
            onProgress = { st ->
                if (st.currentId == audioId) {
                    val d = if (st.durationMs > 0) st.durationMs else SimplePlayer.duration()
                    if (d > 0) {
                        seek?.max = 1000
                        val p = (st.positionMs * 1000f / d).toInt().coerceIn(0, 1000)
                        seek?.progress = p
                    }
                    elapsed?.text = msToMinSec(st.positionMs)
                    duration?.text = "/ ${msToMinSec(if (st.durationMs > 0) st.durationMs else d)}"
                }
            },
            onPause = { id ->
                if (id == audioId) playPause?.setImageResource(android.R.drawable.ic_media_play)
            },
            onComplete = { id ->
                if (id == audioId) {
                    playPause?.setImageResource(android.R.drawable.ic_media_play)
                    seek?.progress = 0
                    elapsed?.text = "0:00"
                }
            },
            onErrorId = { /* no-op */ },
            onErrorThrowable = { /* no-op (silencieux) */ }
        )

        // Démarrer la lecture si nécessaire
        if (source.isNotBlank()) {
            SimplePlayer.play(requireContext(), audioId, source)
        }

        playPause?.setOnClickListener {
            if (SimplePlayer.isPlaying(audioId)) {
                SimplePlayer.pause()
            } else {
                SimplePlayer.resume()
            }
        }
        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var user = false
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = SimplePlayer.duration()
                    val target = if (d > 0) (progress / 1000f * d).toInt() else 0
                    elapsed?.text = msToMinSec(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { user = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (!user) return
                user = false
                val d = SimplePlayer.duration()
                val target = if (d > 0) (seek?.progress ?: 0) / 1000f * d else 0f
                SimplePlayer.seekTo(target.toInt())
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SimplePlayer.setCallbacks(null, null, null, null, null, null)
        playPause = null; seek = null; elapsed = null; duration = null; title = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun msToMinSec(ms: Int): String {
        val total = if (ms < 0) 0 else (ms / 1000)
        val m = total / 60
        val s = total % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
