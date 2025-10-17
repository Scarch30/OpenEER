package com.example.openeer.ui.sheets

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.domain.ReminderUseCases
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.LazyThreadSafetyMode

class BottomSheetReminderPicker : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val TAG = "ReminderPicker"

        fun newInstance(noteId: Long, blockId: Long? = null): BottomSheetReminderPicker {
            val fragment = BottomSheetReminderPicker()
            fragment.arguments = bundleOf(ARG_NOTE_ID to noteId).apply {
                if (blockId != null) {
                    putLong(ARG_BLOCK_ID, blockId)
                }
            }
            return fragment
        }
    }

    private val noteId: Long
        get() = requireArguments().getLong(ARG_NOTE_ID)

    private val blockId: Long?
        get() = if (arguments?.containsKey(ARG_BLOCK_ID) == true) {
            arguments?.getLong(ARG_BLOCK_ID)
        } else {
            null
        }

    private val reminderUseCases by lazy(LazyThreadSafetyMode.NONE) {
        val appContext = requireContext().applicationContext
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ReminderUseCases(appContext, AppDatabase.getInstance(appContext), alarmManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_bottom_reminder_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnIn10).setOnClickListener {
            val timeMillis = System.currentTimeMillis() + 10 * 60_000L
            Log.d(TAG, "Preset +10 min for noteId=$noteId blockId=$blockId")
            scheduleReminder(timeMillis)
        }

        view.findViewById<View>(R.id.btnIn1h).setOnClickListener {
            val timeMillis = System.currentTimeMillis() + 60 * 60_000L
            Log.d(TAG, "Preset +1h for noteId=$noteId blockId=$blockId")
            scheduleReminder(timeMillis)
        }

        view.findViewById<View>(R.id.btnTomorrow9).setOnClickListener {
            val calendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            Log.d(TAG, "Preset tomorrow 9AM for noteId=$noteId blockId=$blockId")
            scheduleReminder(calendar.timeInMillis)
        }

        view.findViewById<View>(R.id.btnCustom).setOnClickListener {
            showTimePicker()
        }
    }

    private fun showTimePicker() {
        val ctx = requireContext()
        val now = Calendar.getInstance()
        val is24h = DateFormat.is24HourFormat(ctx)
        TimePickerDialog(
            ctx,
            { _, hourOfDay, minute ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val nowMillis = System.currentTimeMillis()
                if (selected.timeInMillis <= nowMillis) {
                    selected.add(Calendar.DAY_OF_YEAR, 1)
                }
                Log.d(TAG, "Preset custom time=${selected.time} for noteId=$noteId blockId=$blockId")
                scheduleReminder(selected.timeInMillis)
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            is24h
        ).show()
    }

    private fun scheduleReminder(timeMillis: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val reminderId = withContext(Dispatchers.IO) {
                    reminderUseCases.scheduleAtEpoch(noteId, timeMillis, blockId)
                }

                val appContext = requireContext().applicationContext
                val db = AppDatabase.getInstance(appContext)
                val blocksRepo = BlocksRepository(db.blockDao(), db.noteDao(), db.blockLinkDao())

                val timeFmt = DateFormat.getTimeFormat(appContext)
                val dateFmt = DateFormat.getDateFormat(appContext)
                val sameDay = isSameDay(timeMillis)
                val whenText = if (sameDay) {
                    timeFmt.format(Date(timeMillis))
                } else {
                    "${dateFmt.format(Date(timeMillis))} ${timeFmt.format(Date(timeMillis))}"
                }

                val body = "⏰ Rappel : $whenText"
                val newBlockId = withContext(Dispatchers.IO) {
                    blocksRepo.appendText(noteId, body)
                }
                withContext(Dispatchers.IO) {
                    db.reminderDao().attachBlock(reminderId, newBlockId)
                }
            }.onSuccess {
                notifySuccess(timeMillis)
                dismiss()
            }.onFailure { error ->
                handleFailure(error)
            }
        }
    }

    private fun isSameDay(t: Long): Boolean {
        val now = Calendar.getInstance()
        val tgt = Calendar.getInstance().apply { timeInMillis = t }
        return now.get(Calendar.YEAR) == tgt.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == tgt.get(Calendar.DAY_OF_YEAR)
    }

    private fun notifySuccess(timeMillis: Long) {
        val ctx = context ?: return
        val timeFormat = DateFormat.getTimeFormat(ctx)
        val dateFormat = DateFormat.getDateFormat(ctx)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val includeDate = now.get(Calendar.YEAR) != target.get(Calendar.YEAR) ||
            now.get(Calendar.DAY_OF_YEAR) != target.get(Calendar.DAY_OF_YEAR)
        val timeText = timeFormat.format(Date(timeMillis))
        val formatted = if (includeDate) {
            ctx.getString(R.string.reminder_created, "${dateFormat.format(Date(timeMillis))} $timeText")
        } else {
            ctx.getString(R.string.reminder_created, timeText)
        }
        view?.let {
            Snackbar.make(it, formatted, Snackbar.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(ctx, formatted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFailure(error: Throwable) {
        val ctx = context ?: return
        if (error is IllegalArgumentException) {
            Toast.makeText(ctx, getString(R.string.reminder_error_schedule), Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "Failed to schedule reminder", error)
            Toast.makeText(ctx, getString(R.string.reminder_error_schedule), Toast.LENGTH_SHORT).show()
        }
    }
}
