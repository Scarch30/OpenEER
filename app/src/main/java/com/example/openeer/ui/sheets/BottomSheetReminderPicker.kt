package com.example.openeer.ui.sheets

import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.library.MapFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
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

    private val reminderUseCases by lazy(LazyThreadSafetyMode.NONE) {
        val appContext = requireContext().applicationContext
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ReminderUseCases(appContext, AppDatabase.getInstance(appContext), alarmManager)
    }

    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var timeSection: View
    private lateinit var geoSection: View
    private lateinit var labelInput: TextInputLayout
    private lateinit var radiusInput: TextInputLayout
    private lateinit var locationPreview: TextView
    private lateinit var everySwitch: MaterialSwitch

    private var selectedLat: Double? = null
    private var selectedLon: Double? = null
    private var selectedLabel: String? = null

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
        everySwitch = view.findViewById(R.id.switchEvery)

        radiusInput.editText?.setText(DEFAULT_RADIUS_METERS.toString())

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateSections(checkedId)
        }
        toggleGroup.check(R.id.btnModeTime)

        view.findViewById<View>(R.id.btnIn10).setOnClickListener {
            val timeMillis = System.currentTimeMillis() + 10 * 60_000L
            Log.d(TAG, "Preset +10 min for noteId=$noteId blockId=$blockId")
            scheduleTimeReminder(timeMillis)
        }

        view.findViewById<View>(R.id.btnIn1h).setOnClickListener {
            val timeMillis = System.currentTimeMillis() + 60 * 60_000L
            Log.d(TAG, "Preset +1h for noteId=$noteId blockId=$blockId")
            scheduleTimeReminder(timeMillis)
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
            scheduleTimeReminder(calendar.timeInMillis)
        }

        view.findViewById<View>(R.id.btnCustom).setOnClickListener {
            showTimePicker()
        }

        view.findViewById<View>(R.id.btnPickLocation).setOnClickListener {
            launchMapPicker()
        }

        view.findViewById<View>(R.id.btnPlan).setOnClickListener {
            attemptScheduleGeoReminderWithPermissions()
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
            val db = AppDatabase.getInstance(appContext)
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
                scheduleTimeReminder(selected.timeInMillis)
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            is24h
        ).show()
    }

    private fun scheduleTimeReminder(timeMillis: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val db = AppDatabase.getInstance(appContext)
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
            val body = buildReminderBody(label, whenText)
            var createdBlockId: Long? = null
            runCatching {
                val blockId = withContext(Dispatchers.IO) { blocksRepo.appendText(noteId, body) }
                createdBlockId = blockId
                val reminderId = withContext(Dispatchers.IO) {
                    reminderUseCases.scheduleAtEpoch(noteId, timeMillis, blockId)
                }
                withContext(Dispatchers.IO) {
                    db.reminderDao().attachBlock(reminderId, blockId)
                }
                ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
            }.onSuccess {
                notifySuccess(whenText)
                dismiss()
            }.onFailure { error ->
                cleanupCreatedBlock(createdBlockId)
                handleFailure(error)
            }
        }
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
            val db = AppDatabase.getInstance(appContext)
            val blocksRepo = BlocksRepository(
                blockDao = db.blockDao(),
                noteDao = db.noteDao(),
                linkDao = db.blockLinkDao()
            )
            val label = currentLabel()
            val radius = currentRadius()
            val locationDescription = buildLocationDescription(lat, lon, radius)
            val body = buildReminderBody(label, locationDescription)
            val every = everySwitch.isChecked
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
                notifySuccess(locationDescription)
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
            val db = AppDatabase.getInstance(appContext)
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

    private fun buildReminderBody(label: String?, whenPart: String): String {
        return buildString {
            append("⏰ ")
            if (!label.isNullOrBlank()) {
                append(label.trim())
                append(" — ")
            }
            append(whenPart)
        }
    }

    private fun isSameDay(t: Long): Boolean {
        val now = Calendar.getInstance()
        val tgt = Calendar.getInstance().apply { timeInMillis = t }
        return now.get(Calendar.YEAR) == tgt.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == tgt.get(Calendar.DAY_OF_YEAR)
    }

    private fun notifySuccess(summary: String) {
        val ctx = context ?: return
        val formatted = getString(R.string.reminder_created, summary)
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
}
