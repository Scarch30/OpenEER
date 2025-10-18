package com.example.openeer.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.openeer.core.GeofenceDiag
import com.example.openeer.data.AppDatabase
import com.example.openeer.domain.ReminderUseCases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(
            TAG,
            "BootRestoreReceiver onReceive: action=${intent?.action} sdk=${android.os.Build.VERSION.SDK_INT}"
        )

        val appContext = context.applicationContext
        GeofenceDiag.logProviders(appContext)
        GeofenceDiag.logPerms(appContext)

        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val database = AppDatabase.getInstance(appContext)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val useCases = ReminderUseCases(appContext, database, alarmManager)

        Log.d(TAG, "Restoring reminders on $action")
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Calling restoreAllOnAppStart()")
            useCases.restoreAllOnAppStart()
            Log.d(TAG, "Completed restoreAllOnAppStart()")
            Log.d(TAG, "Calling restoreGeofences()")
            runCatching { useCases.restoreGeofences() }
                .onFailure { error ->
                    Log.w(TAG, "Failed to restore geofences after $action", error)
                }
                .onSuccess {
                    Log.d(TAG, "Completed restoreGeofences()")
                }
        }
    }

    private companion object {
        private const val TAG = "BootRestore"
    }
}
