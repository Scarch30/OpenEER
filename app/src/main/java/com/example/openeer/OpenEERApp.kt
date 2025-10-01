package com.example.openeer

import android.app.Application
import com.example.openeer.services.WhisperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OpenEERApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Au démarrage de l'application, on lance le chargement du modèle
        // dans une coroutine pour ne pas bloquer le démarrage.
        CoroutineScope(Dispatchers.IO).launch {
            WhisperService.loadModel(applicationContext)
        }
    }
}