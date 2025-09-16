package com.example.openeer.ui.util

import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat

fun AppCompatActivity.configureSystemInsets(useDarkIcons: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT

    ViewCompat.getWindowInsetsController(window.decorView)?.apply {
        isAppearanceLightStatusBars = useDarkIcons
        isAppearanceLightNavigationBars = useDarkIcons
    }
}
