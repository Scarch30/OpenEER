package com.example.openeer.ui.library

import android.os.SystemClock
import android.util.Log

object MapSnapDiag {
    const val TAG = "MapSnapDiag"

    inline fun trace(msg: () -> String) {
        Log.d(TAG, msg())
    }

    class Ticker {
        private val t0 = SystemClock.elapsedRealtime()
        fun ms(): Long = SystemClock.elapsedRealtime() - t0
    }
}
