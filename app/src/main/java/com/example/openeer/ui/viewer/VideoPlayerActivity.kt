package com.example.openeer.ui.viewer

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.databinding.ActivityVideoPlayerBinding
import com.example.openeer.ui.dialogs.ChildNameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var lastPosition = 0L
    private val blockId: Long by lazy { intent.getLongExtra("blockId", -1L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top, bottom = sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setSupportActionBar(binding.viewerToolbar)
        binding.viewerToolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_close)
        binding.viewerToolbar.setNavigationOnClickListener { finishAfterTransition() }
        updateToolbarTitle(null)
        refreshToolbarTitle()

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.video_player_error), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initPlayer(Uri.parse(uriString))
    }

    private fun initPlayer(uri: Uri) {
        val p = ExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            val item = MediaItem.fromUri(uri)
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = playWhenReady
            if (lastPosition > 0) player.seekTo(lastPosition)

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.playIndicator.visibility = if (isPlaying) View.GONE else View.VISIBLE
                }
            })
        }
        this.player = p
    }

    override fun onStart() {
        super.onStart()
        // rien, init dans onCreate
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = playWhenReady
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            lastPosition = it.currentPosition
            playWhenReady = it.playWhenReady
            it.playWhenReady = false
        }
    }

    override fun onStop() {
        super.onStop()
        // ne pas release ici pour conserver PiP potentiel
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.playerView.player = null
        player?.release()
        player = null
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_item, menu)
        menu.findItem(R.id.action_rename)?.isVisible = blockId > 0
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finishAfterTransition()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }

    private fun showRenameDialog() {
        if (blockId <= 0) return
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(blockId) }
            ChildNameDialog.show(
                context = this@VideoPlayerActivity,
                initialValue = current,
                onSave = { newName ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { blocksRepository.setChildNameForBlock(blockId, newName) }
                        updateToolbarTitle(newName)
                    }
                },
                onReset = {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { blocksRepository.setChildNameForBlock(blockId, null) }
                        updateToolbarTitle(null)
                    }
                }
            )
        }
    }

    private fun refreshToolbarTitle() {
        if (blockId <= 0) {
            updateToolbarTitle(null)
            return
        }
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(blockId) }
            updateToolbarTitle(current)
        }
    }

    private fun updateToolbarTitle(name: String?) {
        val title = name?.takeIf { it.isNotBlank() } ?: ""
        supportActionBar?.title = title
    }
}
