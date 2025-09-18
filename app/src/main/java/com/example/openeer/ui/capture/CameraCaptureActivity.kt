package com.example.openeer.ui.capture

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.openeer.R
import com.google.android.material.appbar.MaterialToolbar

class CameraCaptureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_camera_capture)

        val toolbar: MaterialToolbar = findViewById(R.id.cameraToolbar)
        val controls: View = findViewById(R.id.cameraControls)
        val root: View = findViewById(R.id.cameraRoot)

        val originalToolbarPaddingTop = toolbar.paddingTop
        val originalControlsPaddingBottom = controls.paddingBottom

        toolbar.setNavigationOnClickListener { finishWithResultOk() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = originalToolbarPaddingTop + systemBars.top)
            controls.updatePadding(bottom = originalControlsPaddingBottom + systemBars.bottom)
            insets
        }

        ViewCompat.requestApplyInsets(root)

        onBackPressedDispatcher.addCallback(this) {
            finishWithResultOk()
        }
    }

    private fun finishWithResultOk() {
        setResult(Activity.RESULT_OK)
        finish()
    }
}
