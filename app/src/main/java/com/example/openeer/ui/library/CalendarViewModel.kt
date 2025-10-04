package com.example.openeer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CalendarViewModel(
    private val noteDao: NoteDao
) : ViewModel() {

    enum class Mode { MONTH, WEEK }

    private val cal: Calendar = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY }

    private val _mode = MutableStateFlow(Mode.MONTH)
    val mode: StateFlow<Mode> = _mode

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _monthDays = MutableStateFlow<List<DayCell>>(emptyList())
    val monthDays: StateFlow<List<DayCell>> = _monthDays

    private val _weekDays = MutableStateFlow<List<DayRow>>(emptyList())
    val weekDays: StateFlow<List<DayRow>> = _weekDays

    fun setMode(m: Mode) {
        if (_mode.value != m) {
            _mode.value = m
            refresh()
        }
    }

    fun gotoPrev() {
        when (_mode.value) {
            Mode.MONTH -> cal.add(Calendar.MONTH, -1)
            Mode.WEEK -> cal.add(Calendar.WEEK_OF_YEAR, -1)
        }
        refresh()
    }

    fun gotoNext() {
        when (_mode.value) {
            Mode.MONTH -> cal.add(Calendar.MONTH, 1)
            Mode.WEEK -> cal.add(Calendar.WEEK_OF_YEAR, 1)
        }
        refresh()
    }

    fun refresh() {
        when (_mode.value) {
            Mode.MONTH -> buildMonth()
            Mode.WEEK -> buildWeek()
        }
    }

    private fun buildMonth() {
        val monthStart = startOfMonth(cal)
        val monthEnd = endOfMonth(cal)

        _title.value = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            .format(cal.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        viewModelScope.launch {
            val notes = noteDao.getByUpdatedBetween(monthStart, monthEnd)
            val byDay = notes.groupBy { dayKey(it.updatedAt) }

            val firstGridDay = startOfCalendarGrid(monthStart, cal.firstDayOfWeek)
            val days = ArrayList<DayCell>(42)

            val todayKey = dayKey(System.currentTimeMillis())

            var cursor = firstGridDay
            repeat(42) {
                val dayStart = startOfDay(cursor)
                val dayEnd = endOfDay(cursor)
                val key = dayKey(dayStart)
                val count = byDay[key]?.size ?: 0
                val inCurrent = inSameMonth(cursor, cal)
                val labelNum = Calendar.getInstance().apply { timeInMillis = dayStart }
                    .get(Calendar.DAY_OF_MONTH)
                days.add(
                    DayCell(
                        startMs = dayStart,
                        endMs = dayEnd,
                        label = "$labelNum",
                        labelFull = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
                            .format(java.util.Date(dayStart)),
                        count = count,
                        isCurrentMonth = inCurrent,
                        isToday = (key == todayKey)
                    )
                )
                cursor += DAY_MS
            }
            _monthDays.value = days
        }
    }

    private fun buildWeek() {
        val weekStart = startOfWeek(cal)
        val weekEnd = endOfWeek(cal)

        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        _title.value = "Semaine ${fmt.format(java.util.Date(weekStart))} – ${fmt.format(java.util.Date(weekEnd))}"

        viewModelScope.launch {
            val notes = noteDao.getByUpdatedBetween(weekStart, weekEnd)
            val byDay = notes.groupBy { dayKey(it.updatedAt) }

            val out = ArrayList<DayRow>(7)
            var cursor = weekStart
            repeat(7) {
                val dayStart = startOfDay(cursor)
                val key = dayKey(dayStart)
                val dayNotes = byDay[key].orEmpty()
                out.add(
                    DayRow(
                        startMs = dayStart,
                        endMs = endOfDay(cursor),
                        label = SimpleDateFormat("EEE d", Locale.getDefault())
                            .format(java.util.Date(dayStart)),
                        labelFull = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
                            .format(java.util.Date(dayStart)),
                        count = dayNotes.size,
                        preview = dayNotes.take(2).map { it.title ?: it.body.take(30) }
                    )
                )
                cursor += DAY_MS
            }
            _weekDays.value = out
        }
    }

    // --- Helpers time ---
    private fun dayKey(ms: Long): Long = startOfDay(ms)

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun endOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 23)
        c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 59)
        c.set(Calendar.MILLISECOND, 999)
        return c.timeInMillis
    }

    private fun startOfWeek(ref: Calendar): Long {
        val c = (ref.clone() as Calendar)
        c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
        return startOfDay(c.timeInMillis)
    }

    private fun endOfWeek(ref: Calendar): Long {
        val c = (ref.clone() as Calendar)
        c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
        c.add(Calendar.DAY_OF_YEAR, 6)
        return endOfDay(c.timeInMillis)
    }

    private fun startOfMonth(ref: Calendar): Long {
        val c = (ref.clone() as Calendar)
        c.set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay(c.timeInMillis)
    }

    private fun endOfMonth(ref: Calendar): Long {
        val c = (ref.clone() as Calendar)
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        return endOfDay(c.timeInMillis)
    }

    private fun inSameMonth(dayMs: Long, ref: Calendar): Boolean {
        val c = Calendar.getInstance()
        c.timeInMillis = dayMs
        return c.get(Calendar.YEAR) == ref.get(Calendar.YEAR) &&
                c.get(Calendar.MONTH) == ref.get(Calendar.MONTH)
    }

    private fun startOfCalendarGrid(monthStart: Long, firstDayOfWeek: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = monthStart
        val dayOfWeek = c.get(Calendar.DAY_OF_WEEK)
        var delta = (7 + (dayOfWeek - firstDayOfWeek)) % 7
        c.add(Calendar.DAY_OF_YEAR, -delta)
        return startOfDay(c.timeInMillis)
    }

    data class DayCell(
        val startMs: Long,
        val endMs: Long,
        val label: String,
        val labelFull: String,
        val count: Int,
        val isCurrentMonth: Boolean,
        val isToday: Boolean
    )

    data class DayRow(
        val startMs: Long,
        val endMs: Long,
        val label: String,
        val labelFull: String,
        val count: Int,
        val preview: List<String>
    )

    companion object {
        fun create(noteDao: NoteDao) = CalendarViewModel(noteDao)
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
