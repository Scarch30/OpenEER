package com.example.openeer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.reminders.ReminderBadgeFormatter
import com.example.openeer.ui.sheets.ReminderListSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteReminderController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) : DefaultLifecycleObserver {

    var openNoteIdProvider: () -> Long? = { null }

    private var reminderReceiverRegistered = false

    private val reminderChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val targetNoteId = intent?.getLongExtra(ReminderListSheet.EXTRA_NOTE_ID, -1L) ?: return
            val currentOpenId = openNoteIdProvider() ?: return
            if (targetNoteId == currentOpenId) {
                refreshReminderChip(targetNoteId)
            }
        }
    }

    fun attach() {
        activity.lifecycle.addObserver(this)
    }

    fun resetUi() {
        binding.btnReminders.isVisible = false
        binding.noteReminderBadge.isVisible = false
        binding.noteReminderBadge.isEnabled = false
        binding.noteReminderBadge.text = ""
        binding.noteReminderBadge.contentDescription = null
        ViewCompat.setTooltipText(binding.noteReminderBadge, null)
    }

    fun onNoteOpened(noteId: Long) {
        refreshReminderChip(noteId)
    }

    fun onNoteClosed() {
        resetUi()
    }

    override fun onStart(owner: LifecycleOwner) {
        registerReminderReceiver()
    }

    override fun onStop(owner: LifecycleOwner) {
        unregisterReminderReceiver()
    }

    private fun registerReminderReceiver() {
        if (reminderReceiverRegistered) return
        ContextCompat.registerReceiver(
            activity,
            reminderChangedReceiver,
            IntentFilter(ReminderListSheet.ACTION_REMINDERS_CHANGED),
            RECEIVER_NOT_EXPORTED,
        )
        reminderReceiverRegistered = true
    }

    private fun unregisterReminderReceiver() {
        if (!reminderReceiverRegistered) return
        runCatching { activity.unregisterReceiver(reminderChangedReceiver) }
        reminderReceiverRegistered = false
    }

    private fun refreshReminderChip(noteId: Long) {
        activity.lifecycleScope.launch {
            val appCtx = activity.applicationContext
            val (totalCount, activeReminders) = withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(appCtx).reminderDao()
                val reminders = dao.listForNoteOrdered(noteId)
                val active = dao.getActiveByNoteId(noteId)
                reminders.size to active
            }
            if (openNoteIdProvider() != noteId) return@launch
            val activeCount = activeReminders.size
            binding.btnReminders.isVisible = totalCount > 0
            if (totalCount > 0) {
                binding.btnReminders.alpha = if (activeCount > 0) 1f else 0.6f
                binding.btnReminders.text = activity.getString(
                    R.string.reminders_chip_label,
                    activeCount,
                )
            }

            val badgeState = ReminderBadgeFormatter.buildState(activity, activeReminders)
            if (badgeState != null) {
                binding.noteReminderBadge.isVisible = true
                binding.noteReminderBadge.isEnabled = true
                binding.noteReminderBadge.text = badgeState.iconText
                binding.noteReminderBadge.contentDescription = badgeState.contentDescription
                ViewCompat.setTooltipText(binding.noteReminderBadge, badgeState.tooltip)
            } else {
                binding.noteReminderBadge.isVisible = false
                binding.noteReminderBadge.isEnabled = false
                binding.noteReminderBadge.text = ""
                binding.noteReminderBadge.contentDescription = null
                ViewCompat.setTooltipText(binding.noteReminderBadge, null)
            }
            binding.noteMetaFooterRow.isVisible =
                binding.noteMetaFooter.isVisible || binding.noteReminderBadge.isVisible
        }
    }
}
