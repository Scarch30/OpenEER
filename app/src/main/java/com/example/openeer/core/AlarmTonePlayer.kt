package com.example.openeer.core

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

object AlarmTonePlayer {
    private const val TAG = "AlarmTonePlayer"
    private val lock = Any()
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (ringtone?.isPlaying == true) {
                Log.d(TAG, "start(): ringtone already playing")
                return
            }
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val tone = RingtoneManager.getRingtone(appContext, alarmUri)
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            try {
                tone.play()
                ringtone = tone
                Log.d(TAG, "start(): ringtone started")
            } catch (t: Throwable) {
                Log.e(TAG, "start(): failed to play ringtone", t)
                ringtone = null
            }

            val vib = appContext.getSystemService(Vibrator::class.java)
            vibrator = vib
            val pattern = longArrayOf(0, 600, 400)
            try {
                if (vib != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val effect = VibrationEffect.createWaveform(pattern, 0)
                        vib.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(pattern, 0)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "start(): vibration failed", t)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                ringtone?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "stop(): failed to stop ringtone", t)
            } finally {
                ringtone = null
            }
            try {
                vibrator?.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "stop(): failed to cancel vibrator", t)
            } finally {
                vibrator = null
            }
        }
    }
}
