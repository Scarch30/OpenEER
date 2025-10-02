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
 * Écran de chargement minimal pendant le warm-up de Vosk.
 * Navigue automatiquement vers MainActivity quand c’est prêt.
 *
 * (Option A) Whisper sera préchauffé en arrière-plan depuis MainActivity,
 * pour ne pas rallonger le temps d’ouverture de l’app.
 */
class StarterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStarterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStarterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Démarre le warm-up (idempotent).
        Startup.ensureInit(applicationContext)

        // Observe la disponibilité et navigue automatiquement.
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
