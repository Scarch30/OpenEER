package com.example.openeer.ui.sheets

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.ui.SimplePlayer
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val blocksRepository by lazy { Injection.provideBlocksRepository(requireContext()) }

    private var playPause: ImageView? = null
    private var seek: SeekBar? = null
    private var elapsed: TextView? = null
    private var duration: TextView? = null
    private var title: TextView? = null

    private var toolbar: MaterialToolbar? = null
    private var currentDisplayName: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottomsheet_audio_quick_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)

        toolbar = view.findViewById<MaterialToolbar>(R.id.viewerToolbar).apply {
            navigationIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_close)
            setNavigationOnClickListener { dismiss() }
            inflateMenu(R.menu.menu_viewer_item)
            menu.findItem(R.id.action_rename)?.isVisible = audioId > 0
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> {
                        showRenameDialog()
                        true
                    }
                    else -> false
                }
            }
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val sys = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updatePadding(top = sys.top)
                insets
            }
        }

        playPause = view.findViewById(R.id.playPause)
        seek = view.findViewById(R.id.seekBar)
        elapsed = view.findViewById(R.id.textElapsed)
        duration = view.findViewById(R.id.textDuration)
        title = view.findViewById(R.id.textTitle)

        updateToolbarTitle(null)
        refreshDisplayName()

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
        playPause = null; seek = null; elapsed = null; duration = null; title = null; toolbar = null
    }

    private fun refreshDisplayName() {
        viewLifecycleOwner.lifecycleScope.launch {
            val fallback = getString(R.string.media_category_audio)
            if (audioId <= 0) {
                currentDisplayName = fallback
                title?.text = fallback
                updateToolbarTitle(null)
                return@launch
            }
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(audioId) }
            val display = current?.takeIf { it.isNotBlank() } ?: fallback
            currentDisplayName = display
            title?.text = display
            updateToolbarTitle(current)
        }
    }

    private fun showRenameDialog() {
        if (audioId <= 0) return
        viewLifecycleOwner.lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(audioId) }
            ChildNameDialog.show(
                context = requireContext(),
                initialValue = current,
                onSave = { newName ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        blocksRepository.setChildNameForBlock(audioId, newName)
                    }.invokeOnCompletion { refreshDisplayName() }
                },
                onReset = {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        blocksRepository.setChildNameForBlock(audioId, null)
                    }.invokeOnCompletion { refreshDisplayName() }
                }
            )
        }
    }

    private fun updateToolbarTitle(name: String?) {
        val fallback = currentDisplayName.ifBlank { getString(R.string.media_category_audio) }
        toolbar?.title = name?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun msToMinSec(ms: Int): String {
        val total = if (ms < 0) 0 else (ms / 1000)
        val m = total / 60
        val s = total % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
