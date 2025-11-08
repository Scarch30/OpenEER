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
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.R
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
    private var currentBlock: BlockEntity? = null
    private var currentChildName: String? = null

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
        menuInflater.inflate(R.menu.menu_viewer_audio, menu)
        menu.findItem(R.id.action_rename)?.isVisible = blockId > 0
        menu.findItem(R.id.action_delete)?.isVisible = blockId > 0
        menu.findItem(R.id.action_share)?.isVisible = !uriString.isNullOrBlank()
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
                showRenameDialog(); true
            }
            R.id.action_share -> {
                shareCurrentAudio(); true
            }
            R.id.action_delete -> {
                confirmDelete(); true
            }
            R.id.action_link_to_element -> {
                openLinkMenuForAudio(); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showRenameDialog() {
        if (blockId <= 0) return
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(blockId) }
            ChildNameDialog.show(
                context = this@AudioViewerActivity,
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
        val title = name?.takeIf { it.isNotBlank() } ?: getString(R.string.audio_viewer_default_title)
        supportActionBar?.title = title
    }

    private fun openLinkMenuForAudio() {
        val block = currentBlock ?: return
        val mediaUri = (block.mediaUri ?: uriString).orEmpty()
        val item = MediaStripItem.Audio(
            blockId = block.id,
            mediaUri = mediaUri,
            mimeType = block.mimeType,
            durationMs = block.durationMs,
            childOrdinal = block.childOrdinal,
            childName = currentChildName
        )
        mediaActions.showMenu(binding.viewerToolbar, item)
    }

    private fun shareCurrentAudio() {
        val source = uriString ?: return
        val shareUri = resolveShareUri(source) ?: run {
            Toast.makeText(this, getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pm = packageManager
        val targets = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
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
            .setPositiveButton(R.string.action_validate) { _, _ -> deleteAudio() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteAudio() {
        val source = uriString
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    source?.let { deleteMediaFile(it) }
                    blocksRepository.deleteBlock(blockId)
                }.isSuccess
            }
            if (success) {
                Toast.makeText(this@AudioViewerActivity, getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@AudioViewerActivity, getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteMediaFile(raw: String) {
        runCatching {
            val uri = Uri.parse(raw)
            when {
                uri.scheme.isNullOrEmpty() -> File(raw).takeIf { it.exists() }?.delete()
                uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.delete() }
                uri.scheme.equals("content", ignoreCase = true) -> contentResolver.delete(uri, null, null)
                else -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.delete() }
            }
        }
    }

    private fun resolveShareUri(raw: String): Uri? {
        val parsed = Uri.parse(raw)
        return when {
            parsed.scheme.isNullOrEmpty() -> {
                val file = File(raw)
                if (!file.exists()) null else FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
            parsed.scheme.equals("file", ignoreCase = true) -> {
                val file = File(parsed.path ?: return null)
                if (!file.exists()) null else FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
            parsed.scheme.equals("content", ignoreCase = true) -> parsed
            else -> parsed
        }
    }
}
