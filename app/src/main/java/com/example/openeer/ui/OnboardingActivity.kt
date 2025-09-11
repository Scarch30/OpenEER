package com.example.openeer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.openeer.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var b: ActivityOnboardingBinding
    private val permissionQueue = ArrayDeque<String>()
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestNextPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // >>> Empêche le contenu de passer sous les barres système
        WindowCompat.setDecorFitsSystemWindows(window, true)

        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)

        buildPermissionQueue()
        b.btnStart.setOnClickListener { requestNextPermission() }
    }

    private fun buildPermissionQueue() {
        permissionQueue.addLast(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionQueue.addLast(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionQueue.addLast(Manifest.permission.CAMERA)
        permissionQueue.addLast(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestNextPermission() {
        val next = permissionQueue.removeFirstOrNull()
        if (next == null) { goToMain(); return }
        permissionLauncher.launch(next)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
