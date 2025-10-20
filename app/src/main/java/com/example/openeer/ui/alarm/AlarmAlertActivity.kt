package com.example.openeer.ui.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.example.openeer.R
import com.example.openeer.core.AlarmTonePlayer
import com.example.openeer.receiver.ReminderReceiver
import com.google.android.material.button.MaterialButton
import java.util.Date

class AlarmAlertActivity : AppCompatActivity() {

    private var reminderId: Long = -1L
    private var noteId: Long = -1L
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_alert)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sourceIntent = intent ?: return finish()
        reminderId = sourceIntent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        noteId = sourceIntent.getLongExtra(EXTRA_NOTE_ID, -1L)
        if (reminderId <= 0L) {
            finish()
            return
        }

        val label = sourceIntent.getStringExtra(EXTRA_LABEL)
        val noteTitle = sourceIntent.getStringExtra(EXTRA_NOTE_TITLE)
        val preview = sourceIntent.getStringExtra(EXTRA_PREVIEW)
        val triggerAt = sourceIntent.getLongExtra(EXTRA_TRIGGER_AT, System.currentTimeMillis())

        val labelView: TextView = findViewById(R.id.textAlarmLabel)
        val titleView: TextView = findViewById(R.id.textAlarmNoteTitle)
        val previewView: TextView = findViewById(R.id.textAlarmPreview)
        val timeView: TextView = findViewById(R.id.textAlarmTime)

        labelView.text = label ?: getString(R.string.notif_title_reminder)
        if (!noteTitle.isNullOrBlank()) {
            titleView.text = noteTitle
            titleView.visibility = android.view.View.VISIBLE
        }
        if (!preview.isNullOrBlank()) {
            previewView.text = preview
            previewView.visibility = android.view.View.VISIBLE
        }
        val timeText = buildString {
            val date = Date(triggerAt)
            val timeFormat = DateFormat.getTimeFormat(this@AlarmAlertActivity)
            val dateFormat = DateFormat.getDateFormat(this@AlarmAlertActivity)
            append(timeFormat.format(date))
            append(" • ")
            append(dateFormat.format(date))
        }
        timeView.text = timeText

        findViewById<MaterialButton>(R.id.btnAlarmStop).setOnClickListener {
            sendReceiverAction(ReminderReceiver.ACTION_STOP_ALARM)
            completeAndFinish()
        }
        findViewById<MaterialButton>(R.id.btnAlarmSnooze5).setOnClickListener {
            sendReceiverAction(ReminderReceiver.ACTION_SNOOZE_5)
            completeAndFinish()
        }
        findViewById<MaterialButton>(R.id.btnAlarmSnooze10).setOnClickListener {
            sendReceiverAction(ReminderReceiver.ACTION_SNOOZE_10)
            completeAndFinish()
        }
        findViewById<MaterialButton>(R.id.btnAlarmSnooze60).setOnClickListener {
            sendReceiverAction(ReminderReceiver.ACTION_SNOOZE_60)
            completeAndFinish()
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmTonePlayer.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && completed) {
            AlarmTonePlayer.stop()
        }
    }

    private fun sendReceiverAction(action: String) {
        val broadcast = Intent(this, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
        }
        sendBroadcast(broadcast)
    }

    private fun completeAndFinish() {
        completed = true
        AlarmTonePlayer.stop()
        if (reminderId > 0) {
            NotificationManagerCompat.from(this).cancel(reminderId.toInt())
        }
        finish()
    }

    companion object {
        private const val EXTRA_NOTE_ID = "alarm_note_id"
        private const val EXTRA_REMINDER_ID = "alarm_reminder_id"
        private const val EXTRA_LABEL = "alarm_label"
        private const val EXTRA_NOTE_TITLE = "alarm_note_title"
        private const val EXTRA_PREVIEW = "alarm_preview"
        private const val EXTRA_TRIGGER_AT = "alarm_trigger_at"

        fun newIntent(
            context: Context,
            noteId: Long,
            reminderId: Long,
            label: String?,
            noteTitle: String?,
            preview: String?,
            triggerAt: Long,
        ): Intent {
            return Intent(context, AlarmAlertActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
                putExtra(EXTRA_REMINDER_ID, reminderId)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_NOTE_TITLE, noteTitle)
                putExtra(EXTRA_PREVIEW, preview)
                putExtra(EXTRA_TRIGGER_AT, triggerAt)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }
}
