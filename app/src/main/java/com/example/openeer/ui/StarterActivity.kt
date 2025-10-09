// app/src/main/java/com/example/openeer/ui/StarterActivity.kt
package com.example.openeer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.openeer.databinding.ActivityStarterBinding
import com.example.openeer.startup.Startup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

/**
 * Écran de démarrage :
 * - Initialise MapLibre
 * - Lance le warm-up (Startup.ensureInit)
 * - Demande toutes les autorisations nécessaires au premier démarrage
 * - Navigue vers MainActivity quand TOUT est prêt et autorisé
 */
class StarterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStarterBinding

    // Lanceur moderne pour la demande multiple d’autorisations
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Après réponse, on vérifie si tout est accordé
        val allGranted = requiredPermissions().all { p -> results[p] == true || isGranted(p) }
        if (allGranted) {
            maybeGoToMain()
        } else {
            // Certaines autorisations sont refusées.
            // Si "Ne plus demander" a été coché pour au moins l'une, on propose d’ouvrir Paramètres.
            if (hasAnyPermanentlyDenied()) {
                showSettingsDialog()
            } else {
                showRetryDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Init MapLibre v12 (idempotent)
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

        // Observe la disponibilité du warm-up.
        lifecycleScope.launch {
            Startup.ready.collectLatest {
                // Dès que le warm-up est OK, on tente de partir (si permissions OK)
                maybeGoToMain()
            }
        }

        // Premier check des permissions au démarrage
        checkAndRequestPermissions()
    }

    // ------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            // Android 12L et moins
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return list.toTypedArray()
    }

    private fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): Array<String> =
        requiredPermissions().filterNot { isGranted(it) }.toTypedArray()

    private fun checkAndRequestPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            // Tout est déjà OK → on pourra partir dès que le warm-up est prêt
            maybeGoToMain()
        } else {
            // Demande groupée
            permLauncher.launch(missing)
        }
    }

    // Après une demande, s’il reste des refus et qu’on ne doit plus afficher de rationale
    // pour au moins un d’entre eux → "Ne plus demander" probable → proposer Paramètres
    private fun hasAnyPermanentlyDenied(): Boolean {
        val stillMissing = missingPermissions()
        if (stillMissing.isEmpty()) return false
        // shouldShowRequestPermissionRationale == false après refus + "Ne plus demander"
        // Attention : au tout premier passage (jamais demandé), c’est aussi false.
        // Ici on l’appelle uniquement après une demande, donc c’est pertinent.
        return stillMissing.any { perm -> !shouldShowRequestPermissionRationale(perm) }
    }

    private fun showRetryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Autorisations requises")
            .setMessage(
                "L’application a besoin du micro, de la localisation, de la caméra et de l’accès aux médias " +
                        "pour fonctionner correctement."
            )
            .setNegativeButton("Quitter") { _, _ -> finish() }
            .setPositiveButton("Réessayer") { _, _ -> checkAndRequestPermissions() }
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Autorisation bloquée")
            .setMessage(
                "Certaines autorisations ont été définitivement refusées. " +
                        "Ouvre les paramètres de l’application pour les accorder."
            )
            .setNegativeButton("Quitter") { _, _ -> finish() }
            .setPositiveButton("Ouvrir les paramètres") { _, _ ->
                openAppSettings()
            }
            .show()
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Fallback : écran des paramètres généraux
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------

    private fun maybeGoToMain() {
        // On part uniquement si : warm-up prêt ET toutes permissions OK
        val warmupReady = Startup.ready.value
        val allPermsGranted = missingPermissions().isEmpty()
        if (warmupReady && allPermsGranted) {
            startActivity(Intent(this@StarterActivity, MainActivity::class.java))
            finish()
        }
    }
}
