package com.example.openeer.ui.viewer

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.openeer.Injection
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.R
import com.example.openeer.databinding.ActivityVideoPlayerBinding
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripItem
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
    private val sourceUri: String? by lazy { intent.getStringExtra(EXTRA_URI) }
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }
    private val mediaActions by lazy { MediaActions(this, blocksRepository) }
    private var currentBlock: BlockEntity? = null
    private var currentChildName: String? = null

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

        val uriString = sourceUri
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
        menu.findItem(R.id.action_delete)?.isVisible = blockId > 0
        menu.findItem(R.id.action_share)?.isVisible = !sourceUri.isNullOrBlank()
        menu.findItem(R.id.action_link_to_element)?.isVisible = blockId > 0 && currentBlock != null
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
            R.id.action_share -> {
                shareVideo()
                true
            }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            R.id.action_link_to_element -> {
                startLinkFlowForVideo()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

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
            currentBlock = null
            updateToolbarTitle(null)
            invalidateOptionsMenu()
            return
        }
        lifecycleScope.launch {
            val (block, childName) = withContext(Dispatchers.IO) {
                val entity = blocksRepository.getBlock(blockId)
                val name = entity?.let { blocksRepository.getChildNameForBlock(blockId) }
                entity to name
            }
            currentBlock = block
            updateToolbarTitle(childName)
            invalidateOptionsMenu()
        }
    }

    private fun updateToolbarTitle(name: String?) {
        currentChildName = name
        val title = name?.takeIf { it.isNotBlank() } ?: ""
        supportActionBar?.title = title
    }

    private fun startLinkFlowForVideo() {
        val block = currentBlock ?: return
        val mediaUri = (block.mediaUri ?: sourceUri).orEmpty()
        val item = MediaStripItem.Image(
            blockId = block.id,
            mediaUri = mediaUri,
            mimeType = block.mimeType,
            type = block.type,
            childOrdinal = block.childOrdinal,
            childName = currentChildName
        )
        val anchor = binding.viewerToolbar
        mediaActions.showLinkOnly(anchor, item)
    }

    private fun shareVideo() {
        val source = sourceUri ?: run {
            Toast.makeText(this, getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val shareUri = ViewerMediaUtils.resolveShareUri(this, source) ?: run {
            Toast.makeText(this, getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val targets = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        targets.forEach { info ->
            grantUriPermission(info.activityInfo.packageName, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        if (blockId <= 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ -> deleteVideo() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteVideo() {
        val source = sourceUri
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    source?.let { ViewerMediaUtils.deleteMediaFile(this@VideoPlayerActivity, it) }
                    blocksRepository.deleteBlock(blockId)
                }.isSuccess
            }
            if (success) {
                Toast.makeText(this@VideoPlayerActivity, getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@VideoPlayerActivity, getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }
}
