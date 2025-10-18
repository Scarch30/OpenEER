package com.example.openeer.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

object LocationPerms {
    private const val TAG = "LocationPerms"
    const val REQ_FINE = 9101
    const val REQ_BG = 9102

    interface Callback { fun onResult(granted: Boolean) }

    private val callbacks = mutableMapOf<Int, Callback>()

    fun hasFine(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarse(ctx: Context): Boolean =
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

    fun requiresBackground(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = Build.VERSION.SDK_INT >= 29

    fun mustOpenSettingsForBackground(): Boolean = Build.VERSION.SDK_INT >= 30

    fun dump(ctx: Context) {
        val fine = hasFine(ctx)
        val coarse = hasCoarse(ctx)
        val bg = hasBackground(ctx)
        Log.d(TAG, "perms: fine=$fine coarse=$coarse bg=$bg (SDK=${Build.VERSION.SDK_INT})")
    }

    fun requestFine(fragment: Fragment, cb: Callback) {
        Log.d(TAG, "perms: request FINE")
        ActivityCompat.requestPermissions(
            fragment.requireActivity(),
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQ_FINE
        )
        registerCb(REQ_FINE, cb)
    }

    fun requestBackground(fragment: Fragment, cb: Callback) {
        if (Build.VERSION.SDK_INT >= 30) {
            Log.w(TAG, "requestBackground() called on API>=30 → should use launchSettingsForBackground()")
            cb.onResult(false)
            return
        }
        Log.d(TAG, "perms: request BG (API29)")
        ActivityCompat.requestPermissions(
            fragment.requireActivity(),
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            REQ_BG
        )
        registerCb(REQ_BG, cb)
    }

    fun launchSettingsForBackground(fragment: Fragment) {
        val ctx = fragment.requireContext()
        val uri = Uri.parse("package:" + ctx.packageName)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        Log.d(TAG, "perms: launch Settings for BG → $uri")
        fragment.startActivity(intent)
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        @Suppress("UNUSED_PARAMETER") permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQ_FINE -> {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                Log.d(TAG, "perms: onRequestPermissionsResult(FINE) → $granted")
                callbacks.remove(REQ_FINE)?.onResult(granted)
            }
            REQ_BG -> {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                Log.d(TAG, "perms: onRequestPermissionsResult(BG) → $granted")
                callbacks.remove(REQ_BG)?.onResult(granted)
            }
        }
    }

    private fun registerCb(code: Int, cb: Callback) {
        callbacks[code] = cb
    }
}
