package com.example.openeer.ui.sheets

import android.app.AlarmManager
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.favorites.FavoriteEntity
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.domain.favorites.FavoritesRepository
import com.example.openeer.domain.favorites.FavoritesService
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "FavoritesSheet"

        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) == null) {
                FavoritesSheet().show(fm, TAG)
            }
        }
    }

    private val appContext: Context by lazy { requireContext().applicationContext }
    private val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    private val favoritesService: FavoritesService by lazy {
        val repository = FavoritesRepository(database.favoriteDao())
        FavoritesService(
            repository = repository,
            currentLocationProvider = {
                val place = getOneShotPlace(appContext)
                place?.let { it.lat to it.lon }
            }
        )
    }

    private lateinit var listView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var listContainer: View
    private lateinit var detailContainer: View
    private lateinit var detailTitle: MaterialTextView
    private lateinit var detailCoordinates: MaterialTextView
    private lateinit var inputNameLayout: TextInputLayout
    private lateinit var inputRadiusLayout: TextInputLayout
    private lateinit var inputCooldownLayout: TextInputLayout
    private lateinit var inputName: TextInputEditText
    private lateinit var inputRadius: TextInputEditText
    private lateinit var inputCooldown: TextInputEditText
    private lateinit var switchEveryTime: SwitchMaterial
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReposition: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var btnBack: MaterialButton

    private val adapter = FavoritesAdapter { favorite ->
        showDetail(favorite)
    }

    private var selectedFavorite: FavoriteEntity? = null
    private var isDetailMode: Boolean = false
    private var permissionCallbackFavoriteId: Long? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).also { dialog ->
            dialog.setOnShowListener { di ->
                val bottomSheet =
                    (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                        ?: return@setOnShowListener
                BottomSheetBehavior.from(bottomSheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_favorites, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView = view.findViewById(R.id.listFavorites)
        emptyView = view.findViewById(R.id.emptyView)
        listContainer = view.findViewById(R.id.listContainer)
        detailContainer = view.findViewById(R.id.detailContainer)
        detailTitle = view.findViewById(R.id.detailTitle)
        detailCoordinates = view.findViewById(R.id.detailCoordinates)
        inputNameLayout = view.findViewById(R.id.inputNameLayout)
        inputRadiusLayout = view.findViewById(R.id.inputRadiusLayout)
        inputCooldownLayout = view.findViewById(R.id.inputCooldownLayout)
        inputName = view.findViewById(R.id.inputName)
        inputRadius = view.findViewById(R.id.inputRadius)
        inputCooldown = view.findViewById(R.id.inputCooldown)
        switchEveryTime = view.findViewById(R.id.switchEveryTime)
        btnSave = view.findViewById(R.id.btnSave)
        btnReposition = view.findViewById(R.id.btnReposition)
        btnDelete = view.findViewById(R.id.btnDelete)
        btnBack = view.findViewById(R.id.btnBack)

        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.adapter = adapter
        listView.addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))

        btnBack.setOnClickListener { showList() }
        btnSave.setOnClickListener { onSaveClicked() }
        btnReposition.setOnClickListener { onRepositionClicked() }
        btnDelete.setOnClickListener { onDeleteClicked() }

        loadFavorites()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationPerms.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun loadFavorites() {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val favorites = withContext(Dispatchers.IO) { favoritesService.getAll() }
            adapter.submitList(favorites)
            listView.isVisible = favorites.isNotEmpty()
            emptyView.isVisible = favorites.isEmpty()
            if (isDetailMode) {
                val id = selectedFavorite?.id
                val refreshed = favorites.firstOrNull { it.id == id }
                if (refreshed != null) {
                    bindDetail(refreshed)
                } else {
                    showList()
                }
            }
        }
    }

    private fun showList() {
        isDetailMode = false
        selectedFavorite = null
        inputNameLayout.error = null
        inputRadiusLayout.error = null
        inputCooldownLayout.error = null
        detailContainer.isVisible = false
        listContainer.isVisible = true
    }

    private fun showDetail(favorite: FavoriteEntity) {
        isDetailMode = true
        bindDetail(favorite)
        listContainer.isVisible = false
        detailContainer.isVisible = true
    }

    private fun bindDetail(favorite: FavoriteEntity) {
        selectedFavorite = favorite
        inputNameLayout.error = null
        inputRadiusLayout.error = null
        inputCooldownLayout.error = null
        detailTitle.text = favorite.displayName
        detailCoordinates.text = getString(
            R.string.favorites_coordinates_format,
            favorite.lat,
            favorite.lon
        )
        if (inputName.text?.toString()?.trim() != favorite.displayName) {
            inputName.setText(favorite.displayName)
            inputName.setSelection(favorite.displayName.length)
        }
        val radiusText = favorite.defaultRadiusMeters.toString()
        if (inputRadius.text?.toString() != radiusText) {
            inputRadius.setText(radiusText)
        }
        val cooldownText = favorite.defaultCooldownMinutes.toString()
        if (inputCooldown.text?.toString() != cooldownText) {
            inputCooldown.setText(cooldownText)
        }
        switchEveryTime.isChecked = favorite.defaultEveryTime
    }

    private fun onSaveClicked() {
        val favorite = selectedFavorite ?: return
        val name = inputName.text?.toString()?.trim().orEmpty()
        val radiusValue = inputRadius.text?.toString()?.trim().orEmpty()
        val cooldownValue = inputCooldown.text?.toString()?.trim().orEmpty()

        var hasError = false
        if (name.isEmpty()) {
            inputNameLayout.error = getString(R.string.favorites_validation_name_required)
            hasError = true
        } else {
            inputNameLayout.error = null
        }

        val radius = radiusValue.toIntOrNull()
        if (radius == null || radius <= 0) {
            inputRadiusLayout.error = getString(R.string.favorites_validation_radius_invalid)
            hasError = true
        } else {
            inputRadiusLayout.error = null
        }

        val cooldown = cooldownValue.toIntOrNull()
        if (cooldown == null || cooldown < 0) {
            inputCooldownLayout.error = getString(R.string.favorites_validation_cooldown_invalid)
            hasError = true
        } else {
            inputCooldownLayout.error = null
        }

        if (hasError) return

        val everyTime = switchEveryTime.isChecked
        setButtonsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    favoritesService.updateFavorite(
                        id = favorite.id,
                        displayName = name,
                        defaultRadiusMeters = radius,
                        defaultCooldownMinutes = cooldown,
                        defaultEveryTime = everyTime
                    )
                }
                showToast(R.string.favorites_toast_saved)
                loadFavorites()
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun onRepositionClicked() {
        val favorite = selectedFavorite ?: return
        val ctx = context ?: return
        if (!LocationPerms.hasFine(ctx)) {
            permissionCallbackFavoriteId = favorite.id
            LocationPerms.requestFine(this, object : LocationPerms.Callback {
                override fun onResult(granted: Boolean) {
                    val pendingId = permissionCallbackFavoriteId
                    permissionCallbackFavoriteId = null
                    if (!granted) {
                        showToast(R.string.favorites_toast_permission_required)
                        return
                    }
                    if (pendingId != null) {
                        adapter.currentList.firstOrNull { it.id == pendingId }?.let { favored ->
                            proceedReposition(favored)
                        }
                    }
                }
            })
            return
        }
        proceedReposition(favorite)
    }

    private fun proceedReposition(favorite: FavoriteEntity) {
        setButtonsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            val updated = try {
                withContext(Dispatchers.IO) {
                    favoritesService.repositionToCurrentLocation(favorite.id)
                    favoritesService.getAll().firstOrNull { it.id == favorite.id }
                }
            } finally {
                setButtonsEnabled(true)
            }
            if (updated != null && (updated.lat != favorite.lat || updated.lon != favorite.lon)) {
                showToast(R.string.favorites_toast_reposition_success)
                bindDetail(updated)
                loadFavorites()
                refreshGeofencesIfAvailable()
            } else {
                showToast(R.string.favorites_toast_reposition_failed)
            }
        }
    }

    private fun onDeleteClicked() {
        val favorite = selectedFavorite ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.favorites_delete_confirm_title)
            .setMessage(R.string.favorites_delete_confirm_message)
            .setPositiveButton(R.string.favorites_delete_confirm_positive) { _, _ ->
                deleteFavorite(favorite.id)
            }
            .setNegativeButton(R.string.favorites_delete_confirm_negative, null)
            .show()
    }

    private fun deleteFavorite(favoriteId: Long) {
        setButtonsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    favoritesService.deleteFavorite(favoriteId)
                }
                showToast(R.string.favorites_toast_deleted)
                showList()
                loadFavorites()
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnSave.isEnabled = enabled
        btnReposition.isEnabled = enabled
        btnDelete.isEnabled = enabled
    }

    private fun showToast(@StringRes resId: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), getString(resId), Toast.LENGTH_SHORT).show()
    }

    private suspend fun refreshGeofencesIfAvailable() = withContext(Dispatchers.IO) {
        runCatching {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@runCatching
            ReminderUseCases(appContext, database, alarmManager).restoreGeofences()
        }
    }

    private class FavoritesAdapter(
        private val onItemClick: (FavoriteEntity) -> Unit
    ) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

        private val items = mutableListOf<FavoriteEntity>()

        fun submitList(newList: List<FavoriteEntity>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_entry, parent, false)
            return ViewHolder(view, onItemClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        val currentList: List<FavoriteEntity>
            get() = items.toList()

        class ViewHolder(
            itemView: View,
            private val onClick: (FavoriteEntity) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val title: MaterialTextView = itemView.findViewById(R.id.title)
            private val subtitle: MaterialTextView = itemView.findViewById(R.id.subtitle)

            fun bind(favorite: FavoriteEntity) {
                title.text = favorite.displayName
                subtitle.text = itemView.context.getString(
                    R.string.favorites_coordinates_format,
                    favorite.lat,
                    favorite.lon
                )
                itemView.setOnClickListener { onClick(favorite) }
            }
        }
    }
}
