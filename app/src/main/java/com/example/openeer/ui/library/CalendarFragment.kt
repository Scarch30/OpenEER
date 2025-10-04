package com.example.openeer.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.AppDatabase
import com.example.openeer.databinding.FragmentCalendarBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class CalendarFragment : Fragment() {

    private var _b: FragmentCalendarBinding? = null
    private val b get() = _b!!

    private lateinit var vm: CalendarViewModel
    private lateinit var monthAdapter: MonthDayAdapter
    private lateinit var weekAdapter: WeekDayAdapter

    // --- State persistant (jour sélectionné + mode) ---
    private var selectedStartMs: Long? = null
    private var initialMode: CalendarViewModel.Mode? = null

    // petit anti-rebond sur scroll post-soumission
    private var pendingScrollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedStartMs = savedInstanceState?.getLong(KEY_SELECTED_START)?.takeIf { it != 0L }
        savedInstanceState?.getString(KEY_MODE)?.let {
            initialMode = runCatching { CalendarViewModel.Mode.valueOf(it) }.getOrNull()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentCalendarBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.get(requireContext().applicationContext)
        vm = CalendarViewModel.create(db.noteDao())

        b.recycler.setHasFixedSize(true)

        // Header nav
        b.btnPrev.setOnClickListener { vm.gotoPrev() }
        b.btnNext.setOnClickListener { vm.gotoNext() }

        // Mode spinner
        val modes = listOf("Mois", "Semaine")
        b.spinnerMode.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)
        b.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                vm.setMode(if (position == 0) CalendarViewModel.Mode.MONTH else CalendarViewModel.Mode.WEEK)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Adapters
        monthAdapter = MonthDayAdapter { day ->
            // mémorise la sélection
            selectedStartMs = day.startMs
            // Ouvre la liste des notes du jour
            val intent = Intent(requireContext(), DayNotesActivity::class.java)
            intent.putExtra("startMs", day.startMs)
            intent.putExtra("endMs", day.endMs)
            intent.putExtra("title", day.labelFull)
            startActivity(intent)
        }
        weekAdapter = WeekDayAdapter { day ->
            selectedStartMs = day.startMs
            val intent = Intent(requireContext(), DayNotesActivity::class.java)
            intent.putExtra("startMs", day.startMs)
            intent.putExtra("endMs", day.endMs)
            intent.putExtra("title", day.labelFull)
            startActivity(intent)
        }

        // Collect VM
        viewLifecycleOwner.lifecycleScope.launch {
            vm.title.collectLatest { b.txtTitle.text = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.mode.collectLatest { mode ->
                // répercute dans le spinner si nécessaire (évite les boucles)
                val wantedIndex = if (mode == CalendarViewModel.Mode.MONTH) 0 else 1
                if (b.spinnerMode.selectedItemPosition != wantedIndex) {
                    b.spinnerMode.setSelection(wantedIndex, false)
                }
                crossfade {
                    if (mode == CalendarViewModel.Mode.MONTH) {
                        b.recycler.layoutManager = GridLayoutManager(requireContext(), 7)
                        b.recycler.adapter = monthAdapter
                        b.weekHeader.isVisible = true
                    } else {
                        b.recycler.layoutManager = LinearLayoutManager(requireContext())
                        b.recycler.adapter = weekAdapter
                        b.weekHeader.isVisible = false
                    }
                }
                // après bascule, on redéclenche un scroll doux
                scheduleScrollToSelectionOrToday()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.monthDays.collectLatest { list ->
                monthAdapter.submit(list)
                // quand les items arrivent, on se positionne
                scheduleScrollToSelectionOrToday()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.weekDays.collectLatest { list ->
                weekAdapter.submit(list)
                scheduleScrollToSelectionOrToday()
            }
        }

        // Applique le mode initial si fourni (sinon reste sur le défaut)
        initialMode?.let { vm.setMode(it) }

        vm.refresh()
    }

    override fun onResume() {
        super.onResume()
        // Revenir vers la sélection courante (ou aujourd’hui) quand on revient de l’Activity “Notes du jour”
        scheduleScrollToSelectionOrToday()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedStartMs?.let { outState.putLong(KEY_SELECTED_START, it) }
        outState.putString(KEY_MODE, vm.mode.value.name)
    }

    private fun scheduleScrollToSelectionOrToday() {
        pendingScrollJob?.cancel()
        pendingScrollJob = viewLifecycleOwner.lifecycleScope.launch {
            // laisse un petit temps à RecyclerView pour poser ses enfants
            delay(60)
            val mode = vm.mode.value
            when (mode) {
                CalendarViewModel.Mode.MONTH -> {
                    val lm = (b.recycler.layoutManager as? GridLayoutManager) ?: return@launch
                    // on récupère la dernière liste via l'adapter (passée dans submit)
                    // -> on va calculer la position cible
                    val listField = MonthDayAdapter::class.java.getDeclaredField("items").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val items = listField.get(monthAdapter) as List<CalendarViewModel.DayCell>
                    if (items.isEmpty()) return@launch
                    val targetMs = selectedStartMs ?: dayStartOf(System.currentTimeMillis())
                    val pos = items.indexOfFirst { it.startMs == targetMs }
                        .takeIf { it >= 0 }
                        ?: items.indexOfFirst { it.isToday }
                            .takeIf { it >= 0 }
                        ?: -1
                    if (pos >= 0) smoothCenterGridPosition(lm, pos)
                }
                CalendarViewModel.Mode.WEEK -> {
                    val lm = (b.recycler.layoutManager as? LinearLayoutManager) ?: return@launch
                    val listField = WeekDayAdapter::class.java.getDeclaredField("items").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val items = listField.get(weekAdapter) as List<CalendarViewModel.DayRow>
                    if (items.isEmpty()) return@launch
                    val targetMs = selectedStartMs ?: dayStartOf(System.currentTimeMillis())
                    val pos = items.indexOfFirst { it.startMs == targetMs }.takeIf { it >= 0 } ?: 0
                    smoothScrollTo(lm, pos)
                }
            }
        }
    }

    private fun smoothCenterGridPosition(lm: GridLayoutManager, position: Int) {
        // essaie de centrer verticalement la ligne contenant la position
        val view = lm.findViewByPosition(position)
        if (view != null) {
            // déjà posé, on peut ajuster sans scroller
            return
        }
        b.recycler.smoothScrollToPosition(positionCoerced(position, lm.itemCount))
    }

    private fun smoothScrollTo(lm: LinearLayoutManager, position: Int) {
        b.recycler.smoothScrollToPosition(positionCoerced(position, lm.itemCount))
    }

    private fun positionCoerced(position: Int, count: Int): Int {
        return when {
            count <= 0 -> 0
            position < 0 -> 0
            position >= count -> count - 1
            else -> position
        }
    }

    private fun crossfade(block: () -> Unit) {
        b.recycler.animate().alpha(0f).setDuration(90).withEndAction {
            block()
            b.recycler.animate().alpha(1f).setDuration(120).start()
        }.start()
    }

    private fun dayStartOf(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        fun newInstance() = CalendarFragment()

        private const val KEY_SELECTED_START = "selected_start_ms"
        private const val KEY_MODE = "mode"
    }
}
