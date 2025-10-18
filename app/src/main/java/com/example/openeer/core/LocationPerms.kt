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
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun requiresBackground(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = Build.VERSION.SDK_INT >= 29

    // Android 11+ (API 30) ne permet plus la requête BG directe → passage par les réglages
    fun mustOpenSettingsForBackground(): Boolean = Build.VERSION.SDK_INT >= 30

    fun dump(ctx: Context) {
        val fine = hasFine(ctx)
        val coarse = hasCoarse(ctx)
        val bg = hasBackground(ctx)
        Log.d(TAG, "perms: fine=$fine coarse=$coarse bg=$bg (SDK=${Build.VERSION.SDK_INT})")
    }

    fun requestFine(fragment: Fragment, cb: Callback) {
        Log.d(TAG, "perms: request FINE")
        registerCb(REQ_FINE, cb)
        ActivityCompat.requestPermissions(
            fragment.requireActivity(),
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQ_FINE
        )
    }

    // Uniquement pour API 29 (Android 10), au-delà il faut passer par Settings
    fun requestBackground(fragment: Fragment, cb: Callback) {
        if (Build.VERSION.SDK_INT >= 30) {
            Log.w(TAG, "requestBackground() called on API>=30 → must use launchSettingsForBackground()")
            cb.onResult(false)
            return
        }
        Log.d(TAG, "perms: request BG (API29 only)")
        registerCb(REQ_BG, cb)
        ActivityCompat.requestPermissions(
            fragment.requireActivity(),
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            REQ_BG
        )
    }

    /**
     * Ouvre au mieux la sous-page "Position" de l’app pour autoriser "Toujours autoriser".
     * Stratégie par paliers (OEM/Android varient) :
     *  1) MANAGE_APP_PERMISSION + data=package: + EXTRA_PERMISSION_NAME=ACCESS_BACKGROUND_LOCATION
     *  2) MANAGE_APP_PERMISSIONS (liste des permissions de l’app)
     *  3) APP_LOCATION_SETTINGS (générique)
     *  4) APPLICATION_DETAILS_SETTINGS (fiche appli)
     */
    fun launchSettingsForBackground(fragment: Fragment) {
        val ctx = fragment.requireContext()
        val pkg = ctx.packageName

        // Intents semi-publics (pour la tentative 1)
        val ACTION_MANAGE_APP_PERMISSION = "android.settings.MANAGE_APP_PERMISSION"
        val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"
        val EXTRA_PERMISSION_NAME = "android.provider.extra.PERMISSION_NAME"

        // 1) Tentative directe vers la sous-page "Toujours autoriser"
        val deepIntent = Intent(ACTION_MANAGE_APP_PERMISSION).apply {
            putExtra(EXTRA_APP_PACKAGE, pkg)
            putExtra(EXTRA_PERMISSION_NAME, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        var ok = runCatching {
            Log.d(TAG, "perms: trying MANAGE_APP_PERMISSION for $pkg (BG)")
            fragment.startActivity(deepIntent)
            true
        }.getOrElse { err ->
            Log.w(TAG, "perms: deep intent failed, fallback to app details", err)
            false
        }

        if (!ok) {
            // 2) Fallback à la liste des permissions de l'application
            val permissionsIntent = Intent("android.settings.APPLICATION_PERMISSIONS_SETTINGS").apply {
                data = Uri.fromParts("package", pkg, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ok = tryStart(fragment, permissionsIntent) // Utilisation de votre fonction tryStart existante

            if (!ok) {
                // 3) Fallback garanti → fiche de l’application (Infos sur l'appli)
                val fallbackDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", pkg, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { fragment.startActivity(fallbackDetails) }
                    .onFailure { e -> Log.e(TAG, "perms: unable to open app details", e) }
            }
        }
    }


    private fun tryStart(fragment: Fragment, intent: Intent): Boolean {
        return runCatching {
            fragment.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "startActivity failed for ${intent.action}", it)
            false
        }
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
