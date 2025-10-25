package com.example.openeer.ui.sheets

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import com.example.openeer.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

internal fun BottomSheetReminderPicker.showTimePicker() {
    val ctx = requireContext()
    val now = calendarFactory()
    val is24h = DateFormat.is24HourFormat(ctx)
    TimePickerDialog(
        ctx,
        { _, hourOfDay, minute ->
            val selected = calendarFactory().apply {
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val nowMillis = nowProvider()
            if (selected.timeInMillis <= nowMillis) {
                selected.add(Calendar.DAY_OF_YEAR, 1)
            }
            Log.d(
                BottomSheetReminderPicker.TAG,
                "Preset custom time=${selected.time} for noteId=$noteId blockId=$blockId"
            )
            setSelectedDateTime(selected.timeInMillis, "time_picker")
        },
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
        is24h
    ).show()
}

internal fun BottomSheetReminderPicker.showDateThenTimePicker() {
    if (!isAdded) return
    val ctx = requireContext()
    val baseCalendar = selectedDateTimeMillis?.let {
        calendarFactory().apply { timeInMillis = it }
    } ?: calendarFactory()
    DatePickerDialog(
        ctx,
        { _, year, month, dayOfMonth ->
            if (!isAdded) return@DatePickerDialog
            val seedTime = selectedDateTimeMillis?.let {
                calendarFactory().apply { timeInMillis = it }
            } ?: calendarFactory()
            val is24h = DateFormat.is24HourFormat(ctx)
            TimePickerDialog(
                ctx,
                { _, hourOfDay, minute ->
                    val picked = calendarFactory().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(Calendar.HOUR_OF_DAY, hourOfDay)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    Log.d(
                        BottomSheetReminderPicker.TAG,
                        "Preset other date/time=${picked.time} for noteId=$noteId blockId=$blockId"
                    )
                    val millis = picked.timeInMillis
                    setSelectedDateTime(millis, "date_time_picker")
                },
                seedTime.get(Calendar.HOUR_OF_DAY),
                seedTime.get(Calendar.MINUTE),
                is24h
            ).show()
        },
        baseCalendar.get(Calendar.YEAR),
        baseCalendar.get(Calendar.MONTH),
        baseCalendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

internal fun BottomSheetReminderPicker.saveTimeReminder(timeMillis: Long) {
    if (isEditing && editingReminder == null) {
        Log.w(BottomSheetReminderPicker.TAG, "saveTimeReminder(): editing reminder not loaded yet")
        return
    }
    if (!updateRepeatEveryMinutes("schedule")) {
        if (radioRepeat.checkedRadioButtonId == R.id.radioRepeatCustom) {
            editRepeatCustom.requestFocus()
        }
        Log.w(BottomSheetReminderPicker.TAG, "Aborting scheduleTimeReminder: repeat selection invalid")
        return
    }
    if (!isEditing) {
        storePreferredDelivery(selectedDelivery)
    }

    viewLifecycleOwner.lifecycleScope.launch {
        val appContext = requireContext().applicationContext
        val label = currentLabel()
        val timeFmt = DateFormat.getTimeFormat(appContext)
        val dateFmt = DateFormat.getDateFormat(appContext)
        val sameDay = isSameDay(timeMillis)
        val whenText = if (sameDay) {
            timeFmt.format(Date(timeMillis))
        } else {
            "${dateFmt.format(Date(timeMillis))} ${timeFmt.format(Date(timeMillis))}"
        }
        val repeatText = formatRepeatDescription(repeatEveryMinutes)
        val whenWithRepeat = repeatText?.let { "$whenText • $it" } ?: whenText
        runCatching {
            withContext(Dispatchers.IO) {
                val current = editingReminder
                if (current == null) {
                    reminderUseCases.scheduleAtEpoch(
                        noteId = noteId,
                        timeMillis = timeMillis,
                        label = label,
                        repeatEveryMinutes = repeatEveryMinutes,
                        delivery = selectedDelivery
                    )
                } else {
                    reminderUseCases.updateTimeReminder(
                        reminderId = current.id,
                        nextTriggerAt = timeMillis,
                        label = label,
                        repeatEveryMinutes = repeatEveryMinutes,
                        delivery = selectedDelivery
                    )
                }
            }
            ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        }.onSuccess {
            notifySuccess(whenWithRepeat, repeatEveryMinutes != null)
            dismiss()
        }.onFailure { error ->
            handleFailure(error)
        }
    }
}

internal fun BottomSheetReminderPicker.attemptScheduleTimeReminder() {
    val timeMillis = resolveTimeReminderTrigger()
    if (timeMillis == null) {
        Toast.makeText(requireContext(), getString(R.string.reminder_time_missing), Toast.LENGTH_SHORT)
            .show()
        return
    }
    saveTimeReminder(timeMillis)
}

internal fun BottomSheetReminderPicker.isSameDay(t: Long): Boolean {
    val now = calendarFactory()
    val tgt = calendarFactory().apply { timeInMillis = t }
    return now.get(Calendar.YEAR) == tgt.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == tgt.get(Calendar.DAY_OF_YEAR)
}
