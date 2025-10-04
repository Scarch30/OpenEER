package com.example.openeer.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.databinding.ActivityStarterBinding
import com.example.openeer.startup.Startup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer


/**
 * Écran de chargement minimal pendant le warm-up de Vosk/Whisper.
 * Navigue automatiquement vers MainActivity quand c’est prêt.
 *
 * -> On initialise aussi MapLibre ici (v12 l’exige avant toute inflation de MapView).
 */
class StarterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStarterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Init MapLibre v12 (idempotent, aucun token requis avec le serveur public)
        try {
            MapLibre.getInstance(
                applicationContext,
                "no-token-required",
                WellKnownTileServer.MapLibre
            )
        } catch (t: Throwable) {
            Log.w("StarterActivity", "MapLibre init warning (safe to continue): ", t)
        }

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
