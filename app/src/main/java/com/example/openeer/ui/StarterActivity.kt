package com.example.openeer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.databinding.ActivityStarterBinding
import com.example.openeer.startup.Startup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows a minimal loading screen while Vosk + Whisper warm up.
 * Automatically navigates to MainActivity when ready.
 */
class StarterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStarterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStarterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Kick off warm-up (idempotent).
        Startup.ensureInit(applicationContext)

        // Observe readiness and navigate automatically.
        lifecycleScope.launch {
            Startup.ready.collectLatest { ready ->
                if (ready) {
                    startActivity(Intent(this@StarterActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }
}
