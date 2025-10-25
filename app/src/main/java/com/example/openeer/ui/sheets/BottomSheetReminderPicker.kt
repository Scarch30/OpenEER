package com.example.openeer.ui.sheets

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.view.isVisible
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.library.MapFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import kotlin.LazyThreadSafetyMode

class BottomSheetReminderPicker : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val ARG_REMINDER_ID = "arg_reminder_id"
        private const val ARG_INITIAL_LABEL = "arg_initial_label"
        internal const val TAG = "ReminderPicker"
        internal const val DEFAULT_RADIUS_METERS = 100
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

    internal var noteIdValue: Long? = null

    internal val noteId: Long
        get() = noteIdValue ?: editingReminder?.noteId
        ?: requireArguments().getLong(ARG_NOTE_ID)

    internal val blockId: Long?
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

    internal val reminderUseCases by lazy(LazyThreadSafetyMode.NONE) {
        reminderUseCasesFactory?.invoke() ?: run {
            val appContext = requireContext().applicationContext
            val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            ReminderUseCases(appContext, obtainDatabase(appContext), alarmManager)
        }
    }

    internal lateinit var toggleGroup: MaterialButtonToggleGroup
    internal lateinit var deliveryToggle: MaterialButtonToggleGroup
    internal lateinit var timeSection: View
    internal lateinit var geoSection: View
    internal lateinit var labelInput: TextInputLayout
    internal lateinit var radiusInput: TextInputLayout
    internal lateinit var cooldownInput: TextInputLayout
    internal lateinit var locationPreview: TextView
    internal lateinit var planTimeButton: MaterialButton
    internal lateinit var planGeoButton: MaterialButton
    internal lateinit var everySwitch: MaterialSwitch
    internal lateinit var geoTriggerToggle: MaterialButtonToggleGroup
    internal lateinit var textWhenSummary: TextView
    internal lateinit var radioRepeat: RadioGroup
    internal lateinit var spinnerRepeatPreset: AppCompatSpinner
    internal lateinit var layoutRepeatCustom: View
    internal lateinit var inputRepeatCustom: TextInputLayout
    internal lateinit var editRepeatCustom: TextInputEditText
    internal lateinit var spinnerRepeatCustomUnit: AppCompatSpinner
    internal lateinit var useCurrentLocationButton: MaterialButton

    internal var selectedLat: Double? = null
    internal var selectedLon: Double? = null
    internal var selectedLabel: String? = null
    internal var selectedDateTimeMillis: Long? = null
    internal var repeatEveryMinutes: Int? = null
    internal var repeatSelectionValid: Boolean = true
    internal var startingInsideGeofence = false
    internal var geoTriggerOnExit = false
    internal var selectedDelivery: String = ReminderEntity.DELIVERY_NOTIFICATION
    internal var suppressDeliveryListener = false
    internal var editingReminder: ReminderEntity? = null

    internal var backgroundPermissionDialog: AlertDialog? = null
    internal var pendingGeoAction: (() -> Unit)? = null
    internal var waitingBgSettingsReturn = false

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

    internal fun loadPreferredDelivery(): String {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY_DELIVERY, ReminderEntity.DELIVERY_NOTIFICATION)
        return if (stored == ReminderEntity.DELIVERY_ALARM) {
            ReminderEntity.DELIVERY_ALARM
        } else {
            ReminderEntity.DELIVERY_NOTIFICATION
        }
    }

    internal fun storePreferredDelivery(value: String) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_DELIVERY, value).apply()
    }

    internal fun currentLabel(): String? {
        return labelInput.editText?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun currentRadius(): Int {
        val text = radiusInput.editText?.text?.toString()?.trim()
        val parsed = text?.toIntOrNull()
        return parsed?.takeIf { it > 0 } ?: DEFAULT_RADIUS_METERS
    }

    internal fun obtainDatabase(context: Context): AppDatabase {
        return databaseProvider?.invoke() ?: AppDatabase.getInstance(context)
    }

    internal fun notifySuccess(summary: String, recurring: Boolean) {
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

    internal fun updatePrimaryButtonsLabel() {
        if (!::planTimeButton.isInitialized || !::planGeoButton.isInitialized) return
        val textRes = if (isEditing) R.string.reminder_save else R.string.reminder_plan
        planTimeButton.text = getString(textRes)
        planGeoButton.text = getString(textRes)
    }

    internal fun handleFailure(error: Throwable) {
        val ctx = context ?: return
        if (error is IllegalArgumentException) {
            Toast.makeText(ctx, getString(R.string.reminder_error_schedule), Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "Failed to schedule reminder", error)
            Toast.makeText(ctx, getString(R.string.reminder_error_schedule), Toast.LENGTH_SHORT).show()
        }
    }

}
