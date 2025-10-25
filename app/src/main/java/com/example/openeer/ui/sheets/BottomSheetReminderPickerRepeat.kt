package com.example.openeer.ui.sheets

import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import com.example.openeer.R
import java.util.Date

internal fun BottomSheetReminderPicker.setupRepeatControls() {
    val ctx = requireContext()
    val presetLabels = RepeatPreset.values().map { getString(it.labelRes) }
    spinnerRepeatPreset.adapter = ArrayAdapter(
        ctx,
        android.R.layout.simple_spinner_item,
        presetLabels
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    spinnerRepeatPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (radioRepeat.checkedRadioButtonId == R.id.radioRepeatPreset) {
                updateRepeatEveryMinutes("preset_changed")
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    val customUnitLabels = CustomRepeatUnit.values().map { getString(it.labelRes) }
    spinnerRepeatCustomUnit.adapter = ArrayAdapter(
        ctx,
        android.R.layout.simple_spinner_item,
        customUnitLabels
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    spinnerRepeatCustomUnit.setSelection(CustomRepeatUnit.DEFAULT.ordinal)
    spinnerRepeatCustomUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (radioRepeat.checkedRadioButtonId == R.id.radioRepeatCustom) {
                updateRepeatEveryMinutes("custom_unit_changed")
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    radioRepeat.setOnCheckedChangeListener { _, checkedId ->
        updateRepeatControlsVisibility(checkedId)
        updateRepeatEveryMinutes("radio_changed")
    }

    editRepeatCustom.doOnTextChanged { _, _, _, _ ->
        if (radioRepeat.checkedRadioButtonId == R.id.radioRepeatCustom) {
            updateRepeatEveryMinutes("custom_edited")
        }
    }

    radioRepeat.check(R.id.radioRepeatNever)
    updateRepeatControlsVisibility(R.id.radioRepeatNever)
    updateRepeatEveryMinutes("init")
}

internal fun BottomSheetReminderPicker.updateRepeatControlsVisibility(checkedId: Int) {
    spinnerRepeatPreset.isVisible = checkedId == R.id.radioRepeatPreset
    val isCustom = checkedId == R.id.radioRepeatCustom
    layoutRepeatCustom.isVisible = isCustom
    if (!isCustom) {
        inputRepeatCustom.error = null
    }
}

internal fun BottomSheetReminderPicker.updateWhenSummary() {
    if (!this::textWhenSummary.isInitialized) return
    val timeMillis = selectedDateTimeMillis
    val summaryText = when {
        timeMillis == null && repeatEveryMinutes != null && repeatSelectionValid -> {
            val repeatLabel = formatRepeatSummaryLabel(repeatEveryMinutes)
            getString(R.string.reminder_when_summary_immediate_repeat, repeatLabel)
        }
        timeMillis == null -> {
            getString(R.string.reminder_when_summary_placeholder)
        }
        else -> {
            val ctx = context ?: return
            val timeFmt = DateFormat.getTimeFormat(ctx)
            val dateFmt = DateFormat.getDateFormat(ctx)
            val sameDay = isSameDay(timeMillis)
            val whenText = if (sameDay) {
                timeFmt.format(Date(timeMillis))
            } else {
                "${dateFmt.format(Date(timeMillis))} ${timeFmt.format(Date(timeMillis))}"
            }
            val repeatLabel = formatRepeatSummaryLabel(repeatEveryMinutes)
            getString(R.string.reminder_when_summary_time_repeat, whenText, repeatLabel)
        }
    }
    textWhenSummary.text = summaryText
    updatePlanTimeButtonState()
}

internal fun BottomSheetReminderPicker.updatePlanTimeButtonState() {
    if (!this::planTimeButton.isInitialized) return
    planTimeButton.isEnabled = selectedDateTimeMillis != null ||
        (repeatEveryMinutes != null && repeatSelectionValid)
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun BottomSheetReminderPicker.setSelectedDateTime(timeMillis: Long, reason: String) {
    if (selectedDateTimeMillis != timeMillis) {
        Log.d(BottomSheetReminderPicker.TAG, "Selected date/time updated ($reason) -> $timeMillis")
    }
    selectedDateTimeMillis = timeMillis
    updateWhenSummary()
    updatePlanTimeButtonState()
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun BottomSheetReminderPicker.updateRepeatEveryMinutes(reason: String): Boolean {
    val result = computeRepeatEveryMinutes()
    val previousMinutes = repeatEveryMinutes
    val previousValid = repeatSelectionValid
    repeatEveryMinutes = result.minutes
    repeatSelectionValid = result.valid
    when {
        !result.valid -> if (previousValid || previousMinutes != result.minutes) {
            Log.d(BottomSheetReminderPicker.TAG, "Repeat interval invalid ($reason)")
        }
        previousMinutes != result.minutes || !previousValid -> {
            val display = result.minutes?.let { "$it min" } ?: "none"
            Log.d(BottomSheetReminderPicker.TAG, "Repeat interval ($reason) → $display")
        }
    }
    updateWhenSummary()
    return result.valid
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun BottomSheetReminderPicker.resolveTimeReminderTrigger(): Long? {
    selectedDateTimeMillis?.let { return it }
    if (repeatEveryMinutes != null && repeatSelectionValid) {
        val baseNow = nowProvider()
        return baseNow + BottomSheetReminderPicker.IMMEDIATE_REPEAT_OFFSET_MILLIS
    }
    return null
}

internal fun BottomSheetReminderPicker.computeRepeatEveryMinutes(): RepeatComputationResult {
    return when (radioRepeat.checkedRadioButtonId) {
        R.id.radioRepeatNever -> {
            inputRepeatCustom.error = null
            RepeatComputationResult(minutes = null, valid = true)
        }
        R.id.radioRepeatPreset -> {
            inputRepeatCustom.error = null
            val preset = RepeatPreset.values().getOrNull(spinnerRepeatPreset.selectedItemPosition)
            RepeatComputationResult(minutes = preset?.minutes, valid = true)
        }
        R.id.radioRepeatCustom -> computeCustomRepeatMinutes()
        else -> RepeatComputationResult(minutes = null, valid = true)
    }
}

internal fun BottomSheetReminderPicker.computeCustomRepeatMinutes(): RepeatComputationResult {
    val raw = editRepeatCustom.text?.toString()?.trim()
    if (raw.isNullOrEmpty()) {
        inputRepeatCustom.error = getString(R.string.reminder_repeat_custom_error)
        return RepeatComputationResult(minutes = null, valid = false)
    }
    val quantity = raw.toIntOrNull()
    if (quantity == null || quantity <= 0) {
        inputRepeatCustom.error = getString(R.string.reminder_repeat_custom_error)
        return RepeatComputationResult(minutes = null, valid = false)
    }
    inputRepeatCustom.error = null
    val unit = CustomRepeatUnit.values().getOrNull(spinnerRepeatCustomUnit.selectedItemPosition)
        ?: CustomRepeatUnit.DEFAULT
    val minutes = quantity * unit.minutesMultiplier
    return RepeatComputationResult(minutes = minutes, valid = true)
}

internal fun BottomSheetReminderPicker.formatRepeatSummaryLabel(minutes: Int?): String {
    return when (minutes) {
        null -> getString(R.string.reminder_repeat_summary_once)
        RepeatPreset.TEN_MINUTES.minutes -> getString(R.string.reminder_repeat_every_10_minutes)
        RepeatPreset.THREE_HOURS.minutes -> getString(R.string.reminder_repeat_every_3_hours)
        RepeatPreset.DAILY.minutes -> getString(R.string.reminder_repeat_every_day)
        else -> formatGenericRepeatLabel(minutes)
    }
}

internal fun BottomSheetReminderPicker.formatRepeatDescription(minutes: Int?): String? {
    return when (minutes) {
        null -> null
        RepeatPreset.TEN_MINUTES.minutes -> getString(R.string.reminder_repeat_every_10_minutes)
        RepeatPreset.THREE_HOURS.minutes -> getString(R.string.reminder_repeat_every_3_hours)
        RepeatPreset.DAILY.minutes -> getString(R.string.reminder_repeat_every_day)
        else -> formatGenericRepeatLabel(minutes)
    }
}

internal fun BottomSheetReminderPicker.formatGenericRepeatLabel(minutes: Int): String {
    val minutesPerHour = 60
    val minutesPerDay = 24 * minutesPerHour
    return when {
        minutes % minutesPerDay == 0 -> {
            val days = minutes / minutesPerDay
            getString(R.string.reminder_repeat_every_days, days)
        }
        minutes % minutesPerHour == 0 -> {
            val hours = minutes / minutesPerHour
            getString(R.string.reminder_repeat_every_hours, hours)
        }
        else -> getString(R.string.reminder_repeat_every_minutes_generic, minutes)
    }
}

internal fun BottomSheetReminderPicker.applyRepeatSelection(minutes: Int?) {
    when {
        minutes == null -> {
            radioRepeat.check(R.id.radioRepeatNever)
        }
        RepeatPreset.values().any { it.minutes == minutes } -> {
            radioRepeat.check(R.id.radioRepeatPreset)
            val index = RepeatPreset.values().indexOfFirst { it.minutes == minutes }
            if (index >= 0) {
                spinnerRepeatPreset.setSelection(index)
            }
        }
        else -> {
            radioRepeat.check(R.id.radioRepeatCustom)
            val minutesPerDay = 24 * 60
            val (quantity, unit) = when {
                minutes % minutesPerDay == 0 -> minutes / minutesPerDay to CustomRepeatUnit.DAYS
                minutes % 60 == 0 -> minutes / 60 to CustomRepeatUnit.HOURS
                else -> minutes to CustomRepeatUnit.MINUTES
            }
            spinnerRepeatCustomUnit.setSelection(unit.ordinal)
            editRepeatCustom.setText(quantity.toString())
        }
    }
    repeatEveryMinutes = minutes
    repeatSelectionValid = true
    updateRepeatControlsVisibility(radioRepeat.checkedRadioButtonId)
    updateRepeatEveryMinutes("edit_prefill")
}

private enum class RepeatPreset(val minutes: Int, @StringRes val labelRes: Int) {
    TEN_MINUTES(10, R.string.reminder_repeat_every_10_minutes),
    THREE_HOURS(3 * 60, R.string.reminder_repeat_every_3_hours),
    DAILY(24 * 60, R.string.reminder_repeat_every_day)
}

private enum class CustomRepeatUnit(@StringRes val labelRes: Int, val minutesMultiplier: Int) {
    MINUTES(R.string.reminder_repeat_custom_unit_minutes, 1),
    HOURS(R.string.reminder_repeat_custom_unit_hours, 60),
    DAYS(R.string.reminder_repeat_custom_unit_days, 24 * 60);

    companion object {
        val DEFAULT = DAYS
    }
}

internal data class RepeatComputationResult(val minutes: Int?, val valid: Boolean)
