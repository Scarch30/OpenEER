package com.example.openeer.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.data.block.BlockType
import com.example.openeer.databinding.ActivityAudioViewerBinding
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import androidx.core.content.FileProvider

class AudioViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_BLOCK_ID = "extra_block_id"

        fun newIntent(context: Context, rawUri: String, blockId: Long = -1L): Intent =
            Intent(context, AudioViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, rawUri)
                putExtra(EXTRA_BLOCK_ID, blockId)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    private lateinit var binding: ActivityAudioViewerBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var lastPosition = 0L
    private val blockId: Long by lazy { intent.getLongExtra(EXTRA_BLOCK_ID, -1L) }
    private val uriString: String? by lazy { intent.getStringExtra(EXTRA_URI) }
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }
    private val mediaActions by lazy { MediaActions(this, blocksRepository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAudioViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerToolbar) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = sys.top)
            insets
        }

        setSupportActionBar(binding.viewerToolbar)
        binding.viewerToolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_close)
        binding.viewerToolbar.setNavigationOnClickListener { finishAfterTransition() }

        updateToolbarTitle(null)
        refreshToolbarTitle()

        binding.playerView.setControllerShowTimeoutMs(0)
        binding.playerView.setControllerHideOnTouch(false)
        binding.playerView.setDefaultArtwork(
            AppCompatResources.getDrawable(this, R.drawable.ic_audio_wave)
        )

        val rawUri = uriString
        if (rawUri.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.audio_viewer_missing_file), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val parsed = Uri.parse(rawUri)
        initPlayer(parsed)
    }

    private fun initPlayer(uri: Uri) {
        val p = ExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            val item = MediaItem.fromUri(uri)
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = playWhenReady
            if (lastPosition > 0) player.seekTo(lastPosition)
        }
        player = p
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

    override fun onDestroy() {
        super.onDestroy()
        binding.playerView.player = null
        player?.release()
        player = null
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_item, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_more) {
            val anchor = findViewById<View>(R.id.action_more)
            showMoreMenu(anchor)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showMoreMenu(anchor: View) {
        if (blockId <= 0) return
        lifecycleScope.launch {
            val block = withContext(Dispatchers.IO) { blocksRepository.getBlock(blockId) } ?: return@launch
            val item = MediaStripItem.Audio(
                blockId = block.id,
                mediaUri = block.mediaUri ?: "",
                mimeType = block.mimeType,
                durationMs = block.durationMs,
                childOrdinal = block.childOrdinal,
                childName = block.childName
            )
            mediaActions.showMenu(anchor, item)
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
        val title = name?.takeIf { it.isNotBlank() } ?: getString(R.string.audio_viewer_default_title)
        supportActionBar?.title = title
    }
}
