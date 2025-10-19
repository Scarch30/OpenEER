package com.example.openeer.ui.sheets

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class BottomSheetReminderPickerTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        Locale.setDefault(Locale.FRANCE)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Settings.System.putString(appContext.contentResolver, Settings.System.TIME_12_24, "24")
    }

    @Test
    fun selectedDateTimeUpdatesSummaryForPresetsAndCustom() {
        val baseNow = calendarBase().timeInMillis
        withPicker(baseNow) { fragment ->
            val summary = fragment.requireView().findViewById<TextView>(R.id.textWhenSummary)
            val ctx = fragment.requireContext()
            assertEquals(
                ctx.getString(R.string.reminder_when_summary_placeholder),
                summary.text.toString()
            )

            val repeatOnce = ctx.getString(R.string.reminder_repeat_summary_once)

            val plus10 = baseNow + 10 * 60_000L
            fragment.setSelectedDateTime(plus10, "test_10")
            assertEquals(
                buildSummary(ctx, baseNow, plus10, repeatOnce),
                summary.text.toString()
            )

            val plus1h = baseNow + 60 * 60_000L
            fragment.setSelectedDateTime(plus1h, "test_1h")
            assertEquals(
                buildSummary(ctx, baseNow, plus1h, repeatOnce),
                summary.text.toString()
            )

            val tomorrow = newCalendar(baseNow).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            fragment.setSelectedDateTime(tomorrow, "test_tomorrow")
            assertEquals(
                buildSummary(ctx, baseNow, tomorrow, repeatOnce),
                summary.text.toString()
            )

            val custom = newCalendar(baseNow).apply {
                add(Calendar.DAY_OF_YEAR, 3)
                set(Calendar.HOUR_OF_DAY, 14)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            fragment.setSelectedDateTime(custom, "test_custom")
            assertEquals(
                buildSummary(ctx, baseNow, custom, repeatOnce),
                summary.text.toString()
            )
        }
    }

    @Test
    fun repeatModesUpdateSummaryAndShowErrors() {
        val baseNow = calendarBase().timeInMillis
        val trigger = baseNow + 45 * 60_000L
        withPicker(baseNow) { fragment ->
            val root = fragment.requireView()
            val summary = root.findViewById<TextView>(R.id.textWhenSummary)
            val radioRepeat = root.findViewById<RadioGroup>(R.id.radioRepeat)
            val spinnerPreset = root.findViewById<AppCompatSpinner>(R.id.spinnerRepeatPreset)
            val inputLayout = root.findViewById<TextInputLayout>(R.id.inputRepeatCustom)
            val editCustom = root.findViewById<TextInputEditText>(R.id.editRepeatCustom)
            val ctx = fragment.requireContext()

            fragment.setSelectedDateTime(trigger, "initial")

            radioRepeat.check(R.id.radioRepeatPreset)
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            spinnerPreset.setSelection(0)
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            fragment.updateRepeatEveryMinutes("preset10")
            fragment.setSelectedDateTime(trigger, "preset10_apply")
            val every10 = ctx.getString(R.string.reminder_repeat_every_10_minutes)
            assertEquals(
                buildSummary(ctx, baseNow, trigger, every10),
                summary.text.toString()
            )

            spinnerPreset.setSelection(1)
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            fragment.updateRepeatEveryMinutes("preset3h")
            fragment.setSelectedDateTime(trigger, "preset3h_apply")
            val every3h = ctx.getString(R.string.reminder_repeat_every_3_hours)
            assertEquals(
                buildSummary(ctx, baseNow, trigger, every3h),
                summary.text.toString()
            )

            spinnerPreset.setSelection(2)
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            fragment.updateRepeatEveryMinutes("presetDaily")
            fragment.setSelectedDateTime(trigger, "presetDaily_apply")
            val everyDay = ctx.getString(R.string.reminder_repeat_every_day)
            assertEquals(
                buildSummary(ctx, baseNow, trigger, everyDay),
                summary.text.toString()
            )

            radioRepeat.check(R.id.radioRepeatCustom)
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            editCustom.setText("2")
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            fragment.updateRepeatEveryMinutes("customValid")
            fragment.setSelectedDateTime(trigger, "customValid_apply")
            val everyTwoDays = ctx.getString(R.string.reminder_repeat_every_days, 2)
            assertEquals(
                buildSummary(ctx, baseNow, trigger, everyTwoDays),
                summary.text.toString()
            )

            editCustom.setText("0")
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            fragment.updateRepeatEveryMinutes("customInvalid")
            val errorText = ctx.getString(R.string.reminder_repeat_custom_error)
            assertEquals(errorText, inputLayout.error)
            val repeatOnce = ctx.getString(R.string.reminder_repeat_summary_once)
            assertEquals(
                buildSummary(ctx, baseNow, trigger, repeatOnce),
                summary.text.toString()
            )

            editCustom.setText("3")
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            fragment.updateRepeatEveryMinutes("customValidAgain")
            radioRepeat.check(R.id.radioRepeatNever)
            ShadowLooper.runUiThreadTasksIncludingDelayed()
            fragment.updateRepeatEveryMinutes("never")
            fragment.setSelectedDateTime(trigger, "never_apply")
            assertNull(inputLayout.error)
            assertEquals(
                buildSummary(ctx, baseNow, trigger, repeatOnce),
                summary.text.toString()
            )
        }
    }

    private fun withPicker(baseNow: Long, block: (BottomSheetReminderPicker) -> Unit) {
        val args = Bundle().apply { putLong("arg_note_id", 42L) }
        val db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scenario: FragmentScenario<BottomSheetReminderPicker> =
            launchFragmentInContainer(
                fragmentArgs = args,
                themeResId = com.google.android.material.R.style.Theme_Material3_DayNight
            ) {
                BottomSheetReminderPicker().apply {
                    arguments = args
                    databaseProvider = { db }
                    nowProvider = { baseNow }
                    calendarFactory = { newCalendar(baseNow) }
                }
            }
        try {
            scenario.onFragment { fragment ->
                block(fragment)
            }
        } finally {
            scenario.close()
            db.close()
        }
    }

    private fun buildSummary(
        ctx: Context,
        baseNow: Long,
        whenMillis: Long,
        repeatLabel: String
    ): String {
        val timeFmt = DateFormat.getTimeFormat(ctx)
        val dateFmt = DateFormat.getDateFormat(ctx)
        val sameDay = isSameDay(baseNow, whenMillis)
        val whenText = if (sameDay) {
            timeFmt.format(Date(whenMillis))
        } else {
            "${dateFmt.format(Date(whenMillis))} ${timeFmt.format(Date(whenMillis))}"
        }
        return ctx.getString(R.string.reminder_when_summary_time_repeat, whenText, repeatLabel)
    }

    private fun isSameDay(baseNow: Long, target: Long): Boolean {
        val base = newCalendar(baseNow)
        val tgt = newCalendar(baseNow).apply { timeInMillis = target }
        return base.get(Calendar.YEAR) == tgt.get(Calendar.YEAR) &&
            base.get(Calendar.DAY_OF_YEAR) == tgt.get(Calendar.DAY_OF_YEAR)
    }

    private fun newCalendar(base: Long): Calendar {
        return Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"), Locale.FRANCE).apply {
            timeInMillis = base
        }
    }

    private fun calendarBase(): Calendar {
        return Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"), Locale.FRANCE).apply {
            set(Calendar.YEAR, 2024)
            set(Calendar.MONTH, Calendar.MAY)
            set(Calendar.DAY_OF_MONTH, 10)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
