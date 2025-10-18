package com.example.openeer.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.WeakHashMap

object LocationPerms {
    private const val TAG = "LocationPerms"
    const val REQ_FINE = 9101
    const val REQ_BG_29 = 9102

    interface Callback {
        fun onResult(granted: Boolean)
    }

    private data class PendingCallbacks(
        var foreground: Callback? = null,
        var background: Callback? = null,
        var waitingSettingsResult: Boolean = false
    )

    private val pendingByFragment = WeakHashMap<Fragment, PendingCallbacks>()

    private fun cleanupIfIdle(fragment: Fragment, callbacks: PendingCallbacks) {
        if (callbacks.foreground == null && callbacks.background == null && !callbacks.waitingSettingsResult) {
            pendingByFragment.remove(fragment)
        }
    }

    fun hasFine(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasCoarse(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun logAll(ctx: Context) {
        val fine = hasFine(ctx)
        val coarse = hasCoarse(ctx)
        val bg = hasBackground(ctx)
        Log.d(TAG, "perms: fine=$fine coarse=$coarse bg=$bg (SDK=${Build.VERSION.SDK_INT})")
    }

    private fun callbacksFor(fragment: Fragment): PendingCallbacks =
        pendingByFragment.getOrPut(fragment) { PendingCallbacks() }

    fun ensureForeground(fragment: Fragment, cb: Callback) {
        val ctx = fragment.context
        if (ctx == null) {
            Log.w(TAG, "ensureForeground(): fragment not attached, ignoring request")
            cb.onResult(false)
            return
        }
        if (hasFine(ctx)) {
            Log.d(TAG, "ensureForeground(): already granted")
            cb.onResult(true)
            return
        }

        Log.d(TAG, "ensureForeground(): requesting ACCESS_FINE_LOCATION…")
        callbacksFor(fragment).foreground = cb
        fragment.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_FINE)
    }

    fun ensureBackground(fragment: Fragment, cb: Callback) {
        val ctx = fragment.context
        if (ctx == null) {
            Log.w(TAG, "ensureBackground(): fragment not attached, ignoring request")
            cb.onResult(false)
            return
        }
        if (hasBackground(ctx)) {
            Log.d(TAG, "ensureBackground(): already granted")
            cb.onResult(true)
            return
        }

        if (Build.VERSION.SDK_INT == 29) {
            Log.d(TAG, "ensureBackground(): API29 → request ACCESS_BACKGROUND_LOCATION in-app")
            val callbacks = callbacksFor(fragment)
            callbacks.background = cb
            callbacks.waitingSettingsResult = false
            fragment.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BG_29
            )
            return
        }

        if (Build.VERSION.SDK_INT >= 30) {
            Log.w(TAG, "ensureBackground(): API${Build.VERSION.SDK_INT} → opening Settings for 'Allow all the time'")
            val callbacks = callbacksFor(fragment)
            callbacks.background = cb
            callbacks.waitingSettingsResult = true
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", fragment.requireContext().packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            fragment.startActivity(intent)
            return
        }

        cb.onResult(true)
    }

    fun onRequestPermissionsResult(
        fragment: Fragment,
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        val callbacks = pendingByFragment[fragment] ?: return false
        if (requestCode == REQ_FINE) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            Log.d(TAG, "onRequestPermissionsResult(FINE): granted=$granted")
            callbacks.foreground?.onResult(granted)
            callbacks.foreground = null
            cleanupIfIdle(fragment, callbacks)
            return true
        }
        if (requestCode == REQ_BG_29) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            Log.d(TAG, "onRequestPermissionsResult(BG29): granted=$granted")
            callbacks.background?.onResult(granted)
            callbacks.background = null
            callbacks.waitingSettingsResult = false
            cleanupIfIdle(fragment, callbacks)
            return true
        }
        return false
    }

    fun onFragmentResume(fragment: Fragment) {
        val callbacks = pendingByFragment[fragment] ?: return
        if (!callbacks.waitingSettingsResult) return

        val ctx = fragment.context ?: return
        val granted = hasBackground(ctx)
        Log.d(TAG, "onFragmentResume(): BG settings check granted=$granted")
        callbacks.background?.onResult(granted)
        callbacks.background = null
        callbacks.waitingSettingsResult = false
        cleanupIfIdle(fragment, callbacks)
    }
}
