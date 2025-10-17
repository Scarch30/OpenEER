package com.example.openeer

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.example.openeer.core.ReminderChannels
import com.example.openeer.data.AppDatabase
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.services.WhisperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OpenEERApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ReminderChannels.ensureCreated(this)

        // Au démarrage de l'application, on lance le chargement du modèle
        // dans une coroutine pour ne pas bloquer le démarrage.
        CoroutineScope(Dispatchers.IO).launch {
            WhisperService.loadModel(applicationContext)
        }
        // Channel du service d'itinéraire
        com.example.openeer.route.RouteRecordingService.ensureChannel(this)

        CoroutineScope(Dispatchers.IO).launch {
            val reminderUseCases = ReminderUseCases(
                this@OpenEERApp,
                AppDatabase.getInstance(this@OpenEERApp),
                getSystemService(Context.ALARM_SERVICE) as AlarmManager
            )
            reminderUseCases.restoreAllOnAppStart()
        }
    }
}
