package com.example.openeer.ui.util

import android.view.View
import com.google.android.material.snackbar.Snackbar

fun View.snackbar(message: String, length: Int = Snackbar.LENGTH_SHORT) {
    Snackbar.make(this, message, length).show()
}
