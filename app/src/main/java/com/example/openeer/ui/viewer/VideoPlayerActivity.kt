package com.example.openeer.ui.viewer

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.openeer.R
import com.example.openeer.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var lastPosition = 0L

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

        binding.btnClose.setOnClickListener { finish() }

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
}
