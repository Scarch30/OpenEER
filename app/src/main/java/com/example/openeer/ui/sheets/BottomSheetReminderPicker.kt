package com.example.openeer.ui.sheets

import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.library.MapFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.LazyThreadSafetyMode

class BottomSheetReminderPicker : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val TAG = "ReminderPicker"
        private const val DEFAULT_RADIUS_METERS = 100

        fun newInstance(noteId: Long, blockId: Long? = null): BottomSheetReminderPicker {
            val fragment = BottomSheetReminderPicker()
            fragment.arguments = Bundle().apply {
                putLong(ARG_NOTE_ID, noteId)
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

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var reminderUseCasesFactory: (() -> ReminderUseCases)? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var databaseProvider: (() -> AppDatabase)? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var calendarFactory: () -> Calendar = { Calendar.getInstance() }

    private val reminderUseCases by lazy(LazyThreadSafetyMode.NONE) {
        reminderUseCasesFactory?.invoke() ?: run {
            val appContext = requireContext().applicationContext
            val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            ReminderUseCases(appContext, obtainDatabase(appContext), alarmManager)
        }
    }

    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var timeSection: View
    private lateinit var geoSection: View
    private lateinit var labelInput: TextInputLayout
    private lateinit var radiusInput: TextInputLayout
    private lateinit var locationPreview: TextView
    private lateinit var planTimeButton: MaterialButton
    private lateinit var everySwitch: MaterialSwitch
    private lateinit var textWhenSummary: TextView
    private lateinit var radioRepeat: RadioGroup
    private lateinit var spinnerRepeatPreset: AppCompatSpinner
    private lateinit var layoutRepeatCustom: View
    private lateinit var inputRepeatCustom: TextInputLayout
    private lateinit var editRepeatCustom: TextInputEditText
    private lateinit var spinnerRepeatCustomUnit: AppCompatSpinner

    private var selectedLat: Double? = null
    private var selectedLon: Double? = null
    private var selectedLabel: String? = null
    private var selectedDateTimeMillis: Long? = null
    private var repeatEveryMinutes: Int? = null
    private var repeatSelectionValid: Boolean = true

    private var backgroundPermissionDialog: AlertDialog? = null
    private var pendingGeoAction: (() -> Unit)? = null
    private var waitingBgSettingsReturn = false

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val lat = data.getDoubleExtra(MapFragment.RESULT_PICK_LOCATION_LAT, Double.NaN)
        val lon = data.getDoubleExtra(MapFragment.RESULT_PICK_LOCATION_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return@registerForActivityResult
        selectedLat = lat
        selectedLon = lon
        selectedLabel = data.getStringExtra(MapFragment.RESULT_PICK_LOCATION_LABEL)
        updateLocationPreview()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_bottom_reminder_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toggleGroup = view.findViewById(R.id.toggleMode)
        timeSection = view.findViewById(R.id.sectionTime)
        geoSection = view.findViewById(R.id.sectionGeo)
        labelInput = view.findViewById(R.id.inputLabel)
        radiusInput = view.findViewById(R.id.inputRadius)
        locationPreview = view.findViewById(R.id.textLocationPreview)
        planTimeButton = view.findViewById(R.id.btnPlanTime)
        everySwitch = view.findViewById(R.id.switchEvery)
        textWhenSummary = view.findViewById(R.id.textWhenSummary)
        radioRepeat = view.findViewById(R.id.radioRepeat)
        spinnerRepeatPreset = view.findViewById(R.id.spinnerRepeatPreset)
        layoutRepeatCustom = view.findViewById(R.id.layoutRepeatCustom)
        inputRepeatCustom = view.findViewById(R.id.inputRepeatCustom)
        editRepeatCustom = view.findViewById(R.id.editRepeatCustom)
        spinnerRepeatCustomUnit = view.findViewById(R.id.spinnerRepeatCustomUnit)

        radiusInput.editText?.setText(DEFAULT_RADIUS_METERS.toString())

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateSections(checkedId)
        }
        toggleGroup.check(R.id.btnModeTime)

        setupRepeatControls()
        updateWhenSummary()
        updatePlanTimeButtonState()

        view.findViewById<View>(R.id.btnIn10).setOnClickListener {
            val timeMillis = nowProvider() + 10 * 60_000L
            Log.d(TAG, "Preset +10 min for noteId=$noteId blockId=$blockId")
            setSelectedDateTime(timeMillis, "preset_10m")
        }

        view.findViewById<View>(R.id.btnIn1h).setOnClickListener {
            val timeMillis = nowProvider() + 60 * 60_000L
            Log.d(TAG, "Preset +1h for noteId=$noteId blockId=$blockId")
            setSelectedDateTime(timeMillis, "preset_1h")
        }

        view.findViewById<View>(R.id.btnTomorrow9).setOnClickListener {
            val calendar = calendarFactory().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            Log.d(TAG, "Preset tomorrow 9AM for noteId=$noteId blockId=$blockId")
            setSelectedDateTime(calendar.timeInMillis, "preset_tomorrow9")
        }

        view.findViewById<View>(R.id.btnCustom).setOnClickListener {
            showTimePicker()
        }

        view.findViewById<View>(R.id.btnOtherDateTime).setOnClickListener {
            showDateThenTimePicker()
        }

        view.findViewById<View>(R.id.btnPickLocation).setOnClickListener {
            launchMapPicker()
        }

        view.findViewById<View>(R.id.btnPlan).setOnClickListener {
            attemptScheduleGeoReminderWithPermissions()
        }

        planTimeButton.setOnClickListener {
            attemptScheduleTimeReminder()
        }

        preloadExistingData()
    }

    private fun updateSections(checkedId: Int) {
        val isTime = checkedId == R.id.btnModeTime
        timeSection.isVisible = isTime
        geoSection.isVisible = !isTime
    }

    private fun launchMapPicker() {
        val ctx = requireContext()
        val intent = MapActivity.newPickLocationIntent(ctx, noteId)
        mapPickerLauncher.launch(intent)
    }

    private fun preloadExistingData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val db = obtainDatabase(appContext)
            val noteDao = db.noteDao()
            val blockDao = db.blockDao()
            val note = withContext(Dispatchers.IO) { noteDao.getByIdOnce(noteId) }
            if (note != null) {
                val lat = note.lat
                val lon = note.lon
                if (lat != null && lon != null) {
                    selectedLat = lat
                    selectedLon = lon
                    selectedLabel = note.placeLabel
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
                    if (maybeLabel.isNotEmpty()) {
                        labelInput.editText?.setText(maybeLabel)
                    }
                }
            }
        }
    }

    private fun showTimePicker() {
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
                Log.d(TAG, "Preset custom time=${selected.time} for noteId=$noteId blockId=$blockId")
                setSelectedDateTime(selected.timeInMillis, "time_picker")
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            is24h
        ).show()
    }

    private fun showDateThenTimePicker() {
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
                            TAG,
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

    private fun scheduleTimeReminder(timeMillis: Long) {
        if (!updateRepeatEveryMinutes("schedule")) {
            if (radioRepeat.checkedRadioButtonId == R.id.radioRepeatCustom) {
                editRepeatCustom.requestFocus()
            }
            Log.w(TAG, "Aborting scheduleTimeReminder: repeat selection invalid")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val db = obtainDatabase(appContext)
            val blocksRepo = BlocksRepository(
                blockDao = db.blockDao(),
                noteDao = db.noteDao(),
                linkDao = db.blockLinkDao()
            )
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
            val body = buildReminderBody(label, whenText, repeatText)
            var createdBlockId: Long? = null
            runCatching {
                val blockId = withContext(Dispatchers.IO) { blocksRepo.appendText(noteId, body) }
                createdBlockId = blockId
                val reminderId = withContext(Dispatchers.IO) {
                    reminderUseCases.scheduleAtEpoch(noteId, timeMillis, blockId, repeatEveryMinutes)
                }
                withContext(Dispatchers.IO) {
                    db.reminderDao().attachBlock(reminderId, blockId)
                }
                ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
            }.onSuccess {
                notifySuccess(whenWithRepeat, repeatEveryMinutes != null)
                dismiss()
            }.onFailure { error ->
                cleanupCreatedBlock(createdBlockId)
                handleFailure(error)
            }
        }
    }

    private fun attemptScheduleTimeReminder() {
        val timeMillis = selectedDateTimeMillis
        if (timeMillis == null) {
            Toast.makeText(requireContext(), getString(R.string.reminder_time_missing), Toast.LENGTH_SHORT)
                .show()
            return
        }
        scheduleTimeReminder(timeMillis)
    }

    private fun attemptScheduleGeoReminderWithPermissions() {
        val lat = selectedLat
        val lon = selectedLon
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), getString(R.string.reminder_geo_location_missing), Toast.LENGTH_SHORT)
                .show()
            return
        }

        val action = { scheduleGeoReminder() }
        ensureGeofencePermissions(action)
    }

    private fun ensureGeofencePermissions(onReady: () -> Unit) {
        val ctx = requireContext().applicationContext
        LocationPerms.dump(ctx)

        if (!LocationPerms.hasFine(ctx)) {
            Log.d(TAG, "GeoFlow ensureForeground → requesting FINE")
            val retry = { ensureGeofencePermissions(onReady) }
            pendingGeoAction = retry
            LocationPerms.requestFine(this, object : LocationPerms.Callback {
                override fun onResult(granted: Boolean) {
                    Log.d(TAG, "GeoFlow ensureForeground → $granted")
                    if (granted) {
                        retry()
                    } else {
                        pendingGeoAction = null
                        Log.w(TAG, "GeoFlow aborted: FINE denied")
                    }
                }
            })
            return
        }

        if (LocationPerms.requiresBackground(ctx) && !LocationPerms.hasBackground(ctx)) {
            Log.d(TAG, "GeoFlow ensureBackground → missing BG, preparing staged flow")
            val retry = { ensureGeofencePermissions(onReady) }
            pendingGeoAction = retry
            showBackgroundPermissionDialog(
                onAccept = {
                    if (LocationPerms.mustOpenSettingsForBackground()) {
                        Log.d(TAG, "GeoFlow ensureBackground → launching Settings")
                        waitingBgSettingsReturn = true
                        LocationPerms.launchSettingsForBackground(this)
                    } else {
                        Log.d(TAG, "GeoFlow ensureBackground → direct requestPermissions(BG) API29")
                        LocationPerms.requestBackground(this, object : LocationPerms.Callback {
                            override fun onResult(granted: Boolean) {
                                Log.d(TAG, "GeoFlow ensureBackground (API29) → $granted")
                                if (granted) {
                                    retry()
                                } else {
                                    pendingGeoAction = null
                                    Log.w(TAG, "GeoFlow aborted: BG denied")
                                }
                            }
                        })
                    }
                },
                onCancel = {
                    Log.w(TAG, "GeoFlow cancelled by user at BG rationale")
                    pendingGeoAction = null
                }
            )
            return
        }

        pendingGeoAction = null
        onReady()
    }

    private fun scheduleGeoReminder() {
        val lat = selectedLat
        val lon = selectedLon
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), getString(R.string.reminder_geo_location_missing), Toast.LENGTH_SHORT)
                .show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val db = obtainDatabase(appContext)
            val blocksRepo = BlocksRepository(
                blockDao = db.blockDao(),
                noteDao = db.noteDao(),
                linkDao = db.blockLinkDao()
            )
            val label = currentLabel()
            val radius = currentRadius()
            val locationDescription = buildLocationDescription(lat, lon, radius)
            val every = everySwitch.isChecked
            val repeatPart = if (every) getString(R.string.reminder_geo_every) else null
            val body = buildReminderBody(label, locationDescription, repeatPart)
            var createdBlockId: Long? = null
            runCatching {
                val blockId = withContext(Dispatchers.IO) { blocksRepo.appendText(noteId, body) }
                createdBlockId = blockId
                val reminderId = withContext(Dispatchers.IO) {
                    reminderUseCases.scheduleGeofence(
                        noteId = noteId,
                        lat = lat,
                        lon = lon,
                        radiusMeters = radius,
                        every = every,
                        blockId = blockId
                    )
                }
                withContext(Dispatchers.IO) {
                    db.reminderDao().attachBlock(reminderId, blockId)
                }
                ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
            }.onSuccess {
                val successSummary = if (every) {
                    "$locationDescription • ${getString(R.string.reminder_geo_every)}"
                } else {
                    locationDescription
                }
                notifySuccess(successSummary, every)
                dismiss()
            }.onFailure { error ->
                cleanupCreatedBlock(createdBlockId)
                handleFailure(error)
            }
        }
    }

    private fun showBackgroundPermissionDialog(onAccept: () -> Unit, onCancel: () -> Unit) {
        if (!isAdded) return
        backgroundPermissionDialog?.dismiss()

        val positiveRes = if (Build.VERSION.SDK_INT >= 30) {
            R.string.map_background_location_positive_settings
        } else {
            R.string.map_background_location_positive_request
        }

        // Message plus clair et informel
        val message = buildString {
            appendLine("Pour que le rappel s’affiche même quand l’application est fermée, il faut autoriser la localisation en arrière-plan.")
            appendLine()
            appendLine("Cliquez sur « Autorisations » → « Position » → « Toujours autoriser ».")
            appendLine()
            append("Cela permet au rappel de se déclencher automatiquement quand vous arriverez à l’endroit choisi.")
        }

        backgroundPermissionDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autoriser la position en arrière-plan")
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ ->
                Log.d(TAG, "GeoFlow: background permission dialog positive")
                onAccept()
            }
            .setNegativeButton("Annuler") { _, _ ->
                Log.d(TAG, "GeoFlow: background permission dialog negative")
                onCancel()
            }
            .setOnCancelListener {
                Log.d(TAG, "GeoFlow: background permission dialog canceled")
                onCancel()
            }
            .setOnDismissListener { backgroundPermissionDialog = null }
            .show()
    }


    override fun onResume() {
        super.onResume()
        if (waitingBgSettingsReturn) {
            waitingBgSettingsReturn = false
            val ctx = requireContext().applicationContext
            val hasBg = !LocationPerms.requiresBackground(ctx) || LocationPerms.hasBackground(ctx)
            Log.d(TAG, "GeoFlow onResume ← from Settings, hasBG=$hasBg")
            val action = pendingGeoAction
            pendingGeoAction = null
            if (hasBg) {
                action?.invoke()
            } else {
                Log.w(TAG, "GeoFlow onResume: BG still missing, not scheduling geofence")
            }
        }
    }

    override fun onDestroyView() {
        backgroundPermissionDialog?.dismiss()
        backgroundPermissionDialog = null
        super.onDestroyView()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationPerms.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun cleanupCreatedBlock(blockId: Long?) {
        if (blockId == null) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val appContext = context?.applicationContext ?: return@launch
            val db = obtainDatabase(appContext)
            val entity = db.blockDao().getById(blockId)
            if (entity != null) {
                db.blockDao().delete(entity)
            }
        }
    }

    private fun currentLabel(): String? {
        return labelInput.editText?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun currentRadius(): Int {
        val text = radiusInput.editText?.text?.toString()?.trim()
        val parsed = text?.toIntOrNull()
        return parsed?.takeIf { it > 0 } ?: DEFAULT_RADIUS_METERS
    }

    private fun buildLocationDescription(lat: Double, lon: Double, radius: Int): String {
        val label = selectedLabel?.takeIf { it.isNotBlank() }
        return if (label != null) {
            getString(R.string.reminder_geo_desc_fmt, label, radius)
        } else {
            val coords = String.format(Locale.US, "%.5f, %.5f", lat, lon)
            "📍 $coords · ~${radius}m"
        }
    }

    private fun buildReminderBody(label: String?, whenPart: String, repeatPart: String? = null): String {
        return buildString {
            append("⏰ ")
            if (!label.isNullOrBlank()) {
                append(label.trim())
                append(" — ")
            }
            append(whenPart)
            if (!repeatPart.isNullOrBlank()) {
                append(" • ")
                append(repeatPart)
            }
        }
    }

    private fun isSameDay(t: Long): Boolean {
        val now = calendarFactory()
        val tgt = calendarFactory().apply { timeInMillis = t }
        return now.get(Calendar.YEAR) == tgt.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == tgt.get(Calendar.DAY_OF_YEAR)
    }

    private fun obtainDatabase(context: Context): AppDatabase {
        return databaseProvider?.invoke() ?: AppDatabase.getInstance(context)
    }

    private fun notifySuccess(summary: String, recurring: Boolean) {
        val ctx = context ?: return
        val messageRes = if (recurring) {
            R.string.reminder_created_recurring
        } else {
            R.string.reminder_created_once
        }
        val formatted = getString(messageRes, summary)
        view?.let {
            Snackbar.make(it, formatted, Snackbar.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(ctx, formatted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLocationPreview() {
        val lat = selectedLat
        val lon = selectedLon
        if (lat == null || lon == null) {
            locationPreview.isVisible = false
            locationPreview.text = null
            return
        }
        val coords = String.format(Locale.US, "%.5f, %.5f", lat, lon)
        val label = selectedLabel?.takeIf { it.isNotBlank() }
        locationPreview.text = if (label != null) "$label\n$coords" else coords
        locationPreview.isVisible = true
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

    private fun setupRepeatControls() {
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

    private fun updateRepeatControlsVisibility(checkedId: Int) {
        spinnerRepeatPreset.isVisible = checkedId == R.id.radioRepeatPreset
        val isCustom = checkedId == R.id.radioRepeatCustom
        layoutRepeatCustom.isVisible = isCustom
        if (!isCustom) {
            inputRepeatCustom.error = null
        }
    }

    private fun updateWhenSummary() {
        if (!::textWhenSummary.isInitialized) return
        val timeMillis = selectedDateTimeMillis
        if (timeMillis == null) {
            textWhenSummary.setText(R.string.reminder_when_summary_placeholder)
            updatePlanTimeButtonState()
            return
        }
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
        textWhenSummary.text = getString(R.string.reminder_when_summary_time_repeat, whenText, repeatLabel)
        updatePlanTimeButtonState()
    }

    private fun updatePlanTimeButtonState() {
        if (!::planTimeButton.isInitialized) return
        planTimeButton.isEnabled = selectedDateTimeMillis != null
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun setSelectedDateTime(timeMillis: Long, reason: String) {
        if (selectedDateTimeMillis != timeMillis) {
            Log.d(TAG, "Selected date/time updated ($reason) -> $timeMillis")
        }
        selectedDateTimeMillis = timeMillis
        updateWhenSummary()
        updatePlanTimeButtonState()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun updateRepeatEveryMinutes(reason: String): Boolean {
        val result = computeRepeatEveryMinutes()
        val previousMinutes = repeatEveryMinutes
        val previousValid = repeatSelectionValid
        repeatEveryMinutes = result.minutes
        repeatSelectionValid = result.valid
        when {
            !result.valid -> if (previousValid || previousMinutes != result.minutes) {
                Log.d(TAG, "Repeat interval invalid ($reason)")
            }
            previousMinutes != result.minutes || !previousValid -> {
                val display = result.minutes?.let { "$it min" } ?: "none"
                Log.d(TAG, "Repeat interval ($reason) → $display")
            }
        }
        updateWhenSummary()
        return result.valid
    }

    private fun computeRepeatEveryMinutes(): RepeatComputationResult {
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

    private fun computeCustomRepeatMinutes(): RepeatComputationResult {
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

    private fun formatRepeatSummaryLabel(minutes: Int?): String {
        return when (minutes) {
            null -> getString(R.string.reminder_repeat_summary_once)
            RepeatPreset.TEN_MINUTES.minutes -> getString(R.string.reminder_repeat_every_10_minutes)
            RepeatPreset.THREE_HOURS.minutes -> getString(R.string.reminder_repeat_every_3_hours)
            RepeatPreset.DAILY.minutes -> getString(R.string.reminder_repeat_every_day)
            else -> formatGenericRepeatLabel(minutes)
        }
    }

    private fun formatRepeatDescription(minutes: Int?): String? {
        return when (minutes) {
            null -> null
            RepeatPreset.TEN_MINUTES.minutes -> getString(R.string.reminder_repeat_every_10_minutes)
            RepeatPreset.THREE_HOURS.minutes -> getString(R.string.reminder_repeat_every_3_hours)
            RepeatPreset.DAILY.minutes -> getString(R.string.reminder_repeat_every_day)
            else -> formatGenericRepeatLabel(minutes)
        }
    }

    private fun formatGenericRepeatLabel(minutes: Int): String {
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

    private enum class RepeatPreset(val minutes: Int, val labelRes: Int) {
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

    private data class RepeatComputationResult(val minutes: Int?, val valid: Boolean)
}
