package com.example.openeer.ui.util

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.snackbar.Snackbar

fun Context.toast(@StringRes messageRes: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, messageRes, duration).show()
}

fun Context.toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun View.snackbar(message: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar {
    return Snackbar.make(this, message, duration).also { it.show() }
}

fun AppCompatActivity.configureSystemInsets(fitsSystemWindows: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, fitsSystemWindows)
}
