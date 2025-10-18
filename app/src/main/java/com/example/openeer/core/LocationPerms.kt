package com.example.openeer.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object LocationPerms {
    private const val TAG = "LocationPerms"
    const val REQ_FINE = 9101
    const val REQ_BG_29 = 9102

    fun hasFine(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasCoarse(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        else true

    fun logAll(ctx: Context) {
        val fine = hasFine(ctx)
        val coarse = hasCoarse(ctx)
        val bg = hasBackground(ctx)
        Log.d(TAG, "perms: fine=$fine coarse=$coarse bg=$bg (SDK=${Build.VERSION.SDK_INT})")
    }

    /**
     * Step 1: ensure FINE. Returns true if already granted or after request fired.
     * Caller should handle result in onRequestPermissionsResult and retry.
     */
    fun ensureFine(activity: Activity): Boolean {
        if (hasFine(activity)) {
            Log.d(TAG, "ensureFine(): already granted")
            return true
        }
        Log.d(TAG, "ensureFine(): requesting ACCESS_FINE_LOCATION…")
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_FINE)
        return false
    }

    /**
     * Step 2: Background policy depends on SDK.
     * - API 29: request ACCESS_BACKGROUND_LOCATION via requestPermissions.
     * - API 30+: must send user to app settings to pick “Allow all the time”.
     */
    fun ensureBackground(activity: Activity): Boolean {
        if (hasBackground(activity)) {
            Log.d(TAG, "ensureBackground(): already granted")
            return true
        }
        if (Build.VERSION.SDK_INT == 29) {
            Log.d(TAG, "ensureBackground(): API29 → request ACCESS_BACKGROUND_LOCATION in-app")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BG_29
            )
            return false
        } else if (Build.VERSION.SDK_INT >= 30) {
            Log.w(TAG, "ensureBackground(): API${Build.VERSION.SDK_INT} → must open Settings for 'Allow all the time'")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            // Caller should retry when activity resumes; we only log here.
            return false
        }
        return true
    }
}
