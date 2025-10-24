package com.example.openeer.ui.sheets

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderEntity
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
        private const val ARG_REMINDER_ID = "arg_reminder_id"
        private const val ARG_INITIAL_LABEL = "arg_initial_label"
        private const val TAG = "ReminderPicker"
        private const val DEFAULT_RADIUS_METERS = 100
        private const val DEFAULT_COOLDOWN_MINUTES = 30
        private const val PREFS_NAME = "reminder_picker"
        private const val PREF_KEY_DELIVERY = "pref_delivery_mode"

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal const val IMMEDIATE_REPEAT_OFFSET_MILLIS = 1_000L

        fun newInstance(
            noteId: Long,
            blockId: Long? = null,
            initialLabel: String? = null,
        ): BottomSheetReminderPicker {
            val fragment = BottomSheetReminderPicker()
            fragment.arguments = Bundle().apply {
                putLong(ARG_NOTE_ID, noteId)
                if (blockId != null) {
                    putLong(ARG_BLOCK_ID, blockId)
                }
                if (!initialLabel.isNullOrBlank()) {
                    putString(ARG_INITIAL_LABEL, initialLabel)
                }
            }
            return fragment
        }

        fun newInstanceForEdit(reminderId: Long): BottomSheetReminderPicker {
            return BottomSheetReminderPicker().apply {
                arguments = Bundle().apply {
                    putLong(ARG_REMINDER_ID, reminderId)
                }
            }
        }
    }

    private val reminderId: Long?
        get() = arguments?.getLong(ARG_REMINDER_ID)?.takeIf { it > 0L }

    private var noteIdValue: Long? = null

    private val noteId: Long
        get() = noteIdValue ?: editingReminder?.noteId
        ?: requireArguments().getLong(ARG_NOTE_ID)

    private val blockId: Long?
        get() = if (arguments?.containsKey(ARG_BLOCK_ID) == true) {
            arguments?.getLong(ARG_BLOCK_ID)
        } else {
            null
        }

    private val isEditing: Boolean
        get() = reminderId != null

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
    private lateinit var deliveryToggle: MaterialButtonToggleGroup
    private lateinit var timeSection: View
    private lateinit var geoSection: View
    private lateinit var labelInput: TextInputLayout
    private lateinit var radiusInput: TextInputLayout
    private lateinit var cooldownInput: TextInputLayout
    private lateinit var locationPreview: TextView
    private lateinit var planTimeButton: MaterialButton
    private lateinit var planGeoButton: MaterialButton
    private lateinit var everySwitch: MaterialSwitch
    private lateinit var geoTriggerToggle: MaterialButtonToggleGroup
    private lateinit var textWhenSummary: TextView
    private lateinit var radioRepeat: RadioGroup
    private lateinit var spinnerRepeatPreset: AppCompatSpinner
    private lateinit var layoutRepeatCustom: View
    private lateinit var inputRepeatCustom: TextInputLayout
    private lateinit var editRepeatCustom: TextInputEditText
    private lateinit var spinnerRepeatCustomUnit: AppCompatSpinner
    private lateinit var useCurrentLocationButton: MaterialButton

    private var selectedLat: Double? = null
    private var selectedLon: Double? = null
    private var selectedLabel: String? = null
    private var selectedDateTimeMillis: Long? = null
    private var repeatEveryMinutes: Int? = null
    private var repeatSelectionValid: Boolean = true
    private var startingInsideGeofence = false
    private var geoTriggerOnExit = false
    private var selectedDelivery: String = ReminderEntity.DELIVERY_NOTIFICATION
    private var suppressDeliveryListener = false
    private var editingReminder: ReminderEntity? = null

    private var backgroundPermissionDialog: AlertDialog? = null
    private var pendingGeoAction: (() -> Unit)? = null
    private var waitingBgSettingsReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isEditing) {
            noteIdValue = arguments?.getLong(ARG_NOTE_ID)?.takeIf { it > 0L }
        }
    }

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
        startingInsideGeofence = false
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
        deliveryToggle = view.findViewById(R.id.toggleDelivery)
        suppressDeliveryListener = true
        val initialDelivery = if (isEditing) {
            ReminderEntity.DELIVERY_NOTIFICATION
        } else {
            loadPreferredDelivery()
        }
        selectedDelivery = initialDelivery
        deliveryToggle.check(
            if (initialDelivery == ReminderEntity.DELIVERY_ALARM) R.id.btnDeliveryAlarm
            else R.id.btnDeliveryNotification
        )
        suppressDeliveryListener = false
        deliveryToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressDeliveryListener) return@addOnButtonCheckedListener
            selectedDelivery = if (checkedId == R.id.btnDeliveryAlarm) {
                ReminderEntity.DELIVERY_ALARM
            } else {
                ReminderEntity.DELIVERY_NOTIFICATION
            }
            storePreferredDelivery(selectedDelivery)
        }
        timeSection = view.findViewById(R.id.sectionTime)
        geoSection = view.findViewById(R.id.sectionGeo)
        labelInput = view.findViewById(R.id.inputLabel)
        radiusInput = view.findViewById(R.id.inputRadius)
        cooldownInput = view.findViewById(R.id.inputCooldown)
        locationPreview = view.findViewById(R.id.textLocationPreview)
        planTimeButton = view.findViewById(R.id.btnPlanTime)
        planGeoButton = view.findViewById(R.id.btnPlan)
        everySwitch = view.findViewById(R.id.switchEvery)
        geoTriggerToggle = view.findViewById(R.id.toggleGeoTrigger)
        textWhenSummary = view.findViewById(R.id.textWhenSummary)
        radioRepeat = view.findViewById(R.id.radioRepeat)
        spinnerRepeatPreset = view.findViewById(R.id.spinnerRepeatPreset)
        layoutRepeatCustom = view.findViewById(R.id.layoutRepeatCustom)
        inputRepeatCustom = view.findViewById(R.id.inputRepeatCustom)
        editRepeatCustom = view.findViewById(R.id.editRepeatCustom)
        spinnerRepeatCustomUnit = view.findViewById(R.id.spinnerRepeatCustomUnit)
        useCurrentLocationButton = view.findViewById(R.id.btnUseCurrentLocation)

        radiusInput.editText?.setText(DEFAULT_RADIUS_METERS.toString())
        cooldownInput.editText?.setText(DEFAULT_COOLDOWN_MINUTES.toString())

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateSections(checkedId)
        }
        toggleGroup.check(R.id.btnModeTime)

        geoTriggerToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            geoTriggerOnExit = checkedId == R.id.btnGeoTriggerExit
        }
        geoTriggerToggle.check(R.id.btnGeoTriggerEnter)

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

        useCurrentLocationButton.setOnClickListener {
            handleUseCurrentLocation()
        }

        planGeoButton.setOnClickListener {
            attemptScheduleGeoReminderWithPermissions()
        }

        planTimeButton.setOnClickListener {
            attemptScheduleTimeReminder()
        }

        updatePrimaryButtonsLabel()

        if (isEditing) {
            reminderId?.let { loadReminderForEdit(it) }
        } else {
            val requestedLabel = arguments?.getString(ARG_INITIAL_LABEL)
            if (!requestedLabel.isNullOrBlank()) {
                labelInput.editText?.setText(requestedLabel)
            }
            val baseNoteId = noteIdValue ?: requireArguments().getLong(ARG_NOTE_ID)
            preloadExistingData(baseNoteId)
        }
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

    private fun handleUseCurrentLocation() {
        if (!isAdded) return
        val ctx = requireContext()
        val fineGranted = LocationPerms.hasFine(ctx)
        val coarseGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            Log.d(TAG, "GeoFlow current location → requesting FINE permission")
            LocationPerms.requestFine(this, object : LocationPerms.Callback {
                override fun onResult(granted: Boolean) {
                    Log.d(TAG, "GeoFlow current location permission result=$granted")
                    if (granted) {
                        fetchCurrentLocation()
                    } else {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.reminder_geo_location_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            })
            return
        }

        fetchCurrentLocation()
    }

    private fun fetchCurrentLocation() {
        if (!isAdded) return
        val ctx = requireContext()
        val appContext = ctx.applicationContext
        useCurrentLocationButton.isEnabled = false
        locationPreview.text = getString(R.string.reminder_geo_locating)
        locationPreview.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val place = getOneShotPlace(appContext)
                if (!isAdded) return@launch
                if (place == null) {
                    startingInsideGeofence = false
                    locationPreview.isVisible = false
                    locationPreview.text = null
                    Toast.makeText(
                        ctx,
                        getString(R.string.reminder_geo_location_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                selectedLat = place.lat
                selectedLon = place.lon
                selectedLabel = place.label
                startingInsideGeofence = true
                updateLocationPreview()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to obtain current location", t)
                if (isAdded) {
                    startingInsideGeofence = false
                    locationPreview.isVisible = false
                    locationPreview.text = null
                    Toast.makeText(
                        ctx,
                        getString(R.string.reminder_geo_location_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                if (isAdded) {
                    useCurrentLocationButton.isEnabled = true
                }
            }
        }
    }

    private fun preloadExistingData(targetNoteId: Long) {
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

    private fun loadReminderForEdit(reminderId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val db = obtainDatabase(appContext)
            val reminder = withContext(Dispatchers.IO) { db.reminderDao().getById(reminderId) }
            if (reminder == null) {
                Log.w(TAG, "loadReminderForEdit: reminderId=$reminderId not found")
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
                    val radius = reminder.radius ?: DEFAULT_RADIUS_METERS
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

    private fun applyRepeatSelection(minutes: Int?) {
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

    private fun saveTimeReminder(timeMillis: Long) {
        if (isEditing && editingReminder == null) {
            Log.w(TAG, "saveTimeReminder(): editing reminder not loaded yet")
            return
        }
        if (!updateRepeatEveryMinutes("schedule")) {
            if (radioRepeat.checkedRadioButtonId == R.id.radioRepeatCustom) {
                editRepeatCustom.requestFocus()
            }
            Log.w(TAG, "Aborting scheduleTimeReminder: repeat selection invalid")
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

    private fun attemptScheduleTimeReminder() {
        val timeMillis = resolveTimeReminderTrigger()
        if (timeMillis == null) {
            Toast.makeText(requireContext(), getString(R.string.reminder_time_missing), Toast.LENGTH_SHORT)
                .show()
            return
        }
        saveTimeReminder(timeMillis)
    }

    private fun attemptScheduleGeoReminderWithPermissions() {
        val lat = selectedLat
        val lon = selectedLon
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), getString(R.string.reminder_geo_location_missing), Toast.LENGTH_SHORT)
                .show()
            return
        }

        val action = { saveGeoReminder() }
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

    private fun saveGeoReminder() {
        if (isEditing && editingReminder == null) {
            Log.w(TAG, "saveGeoReminder(): editing reminder not loaded yet")
            return
        }
        val lat = selectedLat
        val lon = selectedLon
        if (lat == null || lon == null) {
            Toast.makeText(requireContext(), getString(R.string.reminder_geo_location_missing), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val cooldownMinutes = resolveCooldownMinutes()
        if (cooldownInput.error != null) {
            return
        }
        if (!isEditing) {
            storePreferredDelivery(selectedDelivery)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val label = currentLabel()
            val radius = currentRadius()
            val locationDescription = buildLocationDescription(lat, lon, radius)
            val every = everySwitch.isChecked
            val transitionLabel = if (geoTriggerOnExit) "EXIT" else "ENTER"
            val coordsLabel = String.format(Locale.US, "%.5f,%.5f", lat, lon)
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = editingReminder
                    if (current == null) {
                        Log.i(
                            TAG,
                            "[GEOFENCE] $transitionLabel programmé (note=$noteId latLon=$coordsLabel every=$every radius=$radius)"
                        )
                        reminderUseCases.scheduleGeofence(
                            noteId = noteId,
                            lat = lat,
                            lon = lon,
                            radiusMeters = radius,
                            every = every,
                            label = label,
                            cooldownMinutes = cooldownMinutes,
                            triggerOnExit = geoTriggerOnExit,
                            startingInside = startingInsideGeofence,
                            delivery = selectedDelivery
                        )
                    } else {
                        Log.i(
                            TAG,
                            "[GEOFENCE] $transitionLabel mis à jour (reminder=${current.id} note=${current.noteId} latLon=$coordsLabel every=$every radius=$radius)"
                        )
                        reminderUseCases.updateGeofenceReminder(
                            reminderId = current.id,
                            lat = lat,
                            lon = lon,
                            radius = radius,
                            every = every,
                            disarmedUntilExit = geoTriggerOnExit,
                            cooldownMinutes = cooldownMinutes,
                            label = label,
                            delivery = selectedDelivery
                        )
                    }
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

    private fun loadPreferredDelivery(): String {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY_DELIVERY, ReminderEntity.DELIVERY_NOTIFICATION)
        return if (stored == ReminderEntity.DELIVERY_ALARM) {
            ReminderEntity.DELIVERY_ALARM
        } else {
            ReminderEntity.DELIVERY_NOTIFICATION
        }
    }

    private fun storePreferredDelivery(value: String) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_DELIVERY, value).apply()
    }

    private fun currentLabel(): String? {
        return labelInput.editText?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun currentRadius(): Int {
        val text = radiusInput.editText?.text?.toString()?.trim()
        val parsed = text?.toIntOrNull()
        return parsed?.takeIf { it > 0 } ?: DEFAULT_RADIUS_METERS
    }

    private fun resolveCooldownMinutes(): Int? {
        val text = cooldownInput.editText?.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            cooldownInput.error = null
            return null
        }
        val parsed = text.toIntOrNull()
        return if (parsed == null || parsed < 0) {
            cooldownInput.error = getString(R.string.reminder_geo_cooldown_error)
            null
        } else {
            cooldownInput.error = null
            parsed
        }
    }

    private fun geoTriggerLabelLong(): String {
        val resId = if (geoTriggerOnExit) {
            R.string.reminder_geo_trigger_exit
        } else {
            R.string.reminder_geo_trigger_enter
        }
        return getString(resId)
    }

    private fun buildLocationDescription(lat: Double, lon: Double, radius: Int): String {
        val label = selectedLabel?.takeIf { it.isNotBlank() }
        val base = if (label != null) {
            getString(R.string.reminder_geo_desc_fmt, label, radius)
        } else {
            val coords = String.format(Locale.US, "%.5f, %.5f", lat, lon)
            "📍 $coords · ~${radius}m"
        }
        return "$base • ${geoTriggerLabelLong()}"
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

    private fun updatePrimaryButtonsLabel() {
        if (!::planTimeButton.isInitialized || !::planGeoButton.isInitialized) return
        val textRes = if (isEditing) R.string.reminder_save else R.string.reminder_plan
        planTimeButton.text = getString(textRes)
        planGeoButton.text = getString(textRes)
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

    private fun updatePlanTimeButtonState() {
        if (!::planTimeButton.isInitialized) return
        planTimeButton.isEnabled = selectedDateTimeMillis != null ||
            (repeatEveryMinutes != null && repeatSelectionValid)
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

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun resolveTimeReminderTrigger(): Long? {
        selectedDateTimeMillis?.let { return it }
        if (repeatEveryMinutes != null && repeatSelectionValid) {
            val baseNow = nowProvider()
            return baseNow + IMMEDIATE_REPEAT_OFFSET_MILLIS
        }
        return null
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
