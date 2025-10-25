package com.example.openeer.ui.sheets

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.reminders.ReminderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BottomSheetReminderPicker.preloadExistingData(targetNoteId: Long) {
    viewLifecycleOwner.lifecycleScope.launch {
        val appContext = requireContext().applicationContext
        val db = obtainDatabase(appContext)
        val noteDao = db.noteDao()
        val blockDao = db.blockDao()
        val note = withContext(Dispatchers.IO) { noteDao.getByIdOnce(targetNoteId) }
        if (note != null) {
            val lat = note.lat
            val lon = note.lon
            if (lat != null && lon != null) {
                selectedLat = lat
                selectedLon = lon
                selectedLabel = note.placeLabel
                startingInsideGeofence = false
                updateLocationPreview()
            }
        }
        val existingBlockId = blockId
        if (existingBlockId != null) {
            val blockText = withContext(Dispatchers.IO) { blockDao.getById(existingBlockId)?.text }
            val firstLine = blockText
                ?.lineSequence()
                ?.firstOrNull()
                ?.removePrefix("⏰")
                ?.trim()
            if (!firstLine.isNullOrBlank()) {
                val maybeLabel = firstLine.substringBefore("—").trim()
                val existing = labelInput.editText?.text?.toString()
                if (maybeLabel.isNotEmpty() && existing.isNullOrBlank()) {
                    labelInput.editText?.setText(maybeLabel)
                }
            }
        }
    }
}

internal fun BottomSheetReminderPicker.loadReminderForEdit(reminderId: Long) {
    viewLifecycleOwner.lifecycleScope.launch {
        val appContext = requireContext().applicationContext
        val db = obtainDatabase(appContext)
        val reminder = withContext(Dispatchers.IO) { db.reminderDao().getById(reminderId) }
        if (reminder == null) {
            Log.w(BottomSheetReminderPicker.TAG, "loadReminderForEdit: reminderId=$reminderId not found")
            if (isAdded) {
                dismissAllowingStateLoss()
            }
            return@launch
        }
        editingReminder = reminder
        noteIdValue = reminder.noteId
        suppressDeliveryListener = true
        selectedDelivery = reminder.delivery
        deliveryToggle.check(
            if (reminder.delivery == ReminderEntity.DELIVERY_ALARM) R.id.btnDeliveryAlarm
            else R.id.btnDeliveryNotification
        )
        suppressDeliveryListener = false
        labelInput.editText?.setText(reminder.label)
        when (reminder.type) {
            ReminderEntity.TYPE_TIME_ONE_SHOT, ReminderEntity.TYPE_TIME_REPEATING -> {
                toggleGroup.check(R.id.btnModeTime)
                setSelectedDateTime(reminder.nextTriggerAt, "edit_load")
                applyRepeatSelection(reminder.repeatEveryMinutes)
            }
            ReminderEntity.TYPE_LOC_ONCE, ReminderEntity.TYPE_LOC_EVERY -> {
                toggleGroup.check(R.id.btnModePlace)
                selectedLat = reminder.lat
                selectedLon = reminder.lon
                selectedLabel = null
                val radius = reminder.radius ?: BottomSheetReminderPicker.DEFAULT_RADIUS_METERS
                radiusInput.editText?.setText(radius.toString())
                val cooldown = reminder.cooldownMinutes
                if (cooldown != null) {
                    cooldownInput.editText?.setText(cooldown.toString())
                } else {
                    cooldownInput.editText?.setText(null)
                }
                everySwitch.isChecked = reminder.type == ReminderEntity.TYPE_LOC_EVERY
                geoTriggerOnExit = reminder.isExit()
                geoTriggerToggle.check(if (geoTriggerOnExit) R.id.btnGeoTriggerExit else R.id.btnGeoTriggerEnter)
                startingInsideGeofence = false
                updateLocationPreview()
                repeatEveryMinutes = null
                repeatSelectionValid = true
            }
            else -> {
                toggleGroup.check(R.id.btnModeTime)
                setSelectedDateTime(reminder.nextTriggerAt, "edit_load_other")
                applyRepeatSelection(reminder.repeatEveryMinutes)
            }
        }
        updatePrimaryButtonsLabel()
        updatePlanTimeButtonState()
    }
}
