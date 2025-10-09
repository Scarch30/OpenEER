package com.example.openeer.ui.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.example.openeer.R
import com.example.openeer.databinding.ActivityMapBinding

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.map_title)
        }
        binding.toolbar.setNavigationOnClickListener { onSupportNavigateUp() }

        if (savedInstanceState == null) {
            val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L).takeIf { it > 0 }
            val blockId = intent.getLongExtra(EXTRA_BLOCK_ID, -1L).takeIf { it > 0 }
            val mode = intent.getStringExtra(EXTRA_MODE)

            val fragment = MapFragment.newInstance(noteId, blockId, mode)
            supportFragmentManager.commit {
                replace(binding.mapContainer.id, fragment, MAP_FRAGMENT_TAG)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_NOTE_ID = "com.example.openeer.map.EXTRA_NOTE_ID"
        const val EXTRA_BLOCK_ID = "com.example.openeer.map.EXTRA_BLOCK_ID"
        const val EXTRA_MODE = "com.example.openeer.map.EXTRA_MODE"

        const val MODE_BROWSE = "BROWSE"
        const val MODE_CENTER_ON_HERE = "CENTER_ON_HERE"

        private const val MAP_FRAGMENT_TAG = "map_fragment"

        @JvmStatic
        fun intentForBrowse(
            context: Context,
            noteId: Long? = null,
            blockId: Long? = null
        ): Intent {
            return Intent(context, MapActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_BROWSE)
                noteId?.takeIf { it > 0 }?.let { putExtra(EXTRA_NOTE_ID, it) }
                blockId?.takeIf { it > 0 }?.let { putExtra(EXTRA_BLOCK_ID, it) }
            }
        }
    }
}
