package com.example.openeer.ui.library

import android.location.Address
import android.location.Geocoder
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.Place
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MapFragment.initializeSearchUi(root: View) {
    searchInput = root.findViewById(R.id.searchInput)
    searchAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf())
    searchInput.setAdapter(searchAdapter)

    searchInput.addTextChangedListener(afterTextChanged = { text ->
        val query = text?.toString()?.trim().orEmpty()
        searchJob?.cancel()
        searchExecutionJob?.cancel()
        if (query.length < 3) {
            searchAdapter?.clear()
            searchResults = emptyList()
            clearSearchFeedback()
            searchInput.dismissDropDown()
            return@addTextChangedListener
        }
        clearSearchFeedback()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(250)
            fetchSuggestions(query)
        }
    })

    searchInput.setOnEditorActionListener(TextView.OnEditorActionListener { textView, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_SEARCH) return@OnEditorActionListener false
        val query = textView.text?.toString()?.trim().orEmpty()
        if (query.length < 3) {
            searchInput.dismissDropDown()
            return@OnEditorActionListener true
        }
        launchDirectSearch(query)
        true
    })

    searchInput.setOnItemClickListener { _, _, position, _ ->
        val addr = searchResults.getOrNull(position) ?: return@setOnItemClickListener
        val lat = addr.latitude
        val lon = addr.longitude
        val label = addr.getAddressLine(0)
            ?: addr.featureName
            ?: String.format(Locale.US, "%.5f, %.5f", lat, lon)
        clearSearchFeedback()
        searchInput.dismissDropDown()
        showSelectionFromSearch(
            Place(
                lat = lat,
                lon = lon,
                label = label,
                accuracyM = null
            )
        )
    }
}

private suspend fun MapFragment.fetchSuggestions(query: String) {
    when (val outcome = geocode(query, 8)) {
        is GeocodeOutcome.Success -> {
            val results = outcome.addresses
            val labels = updateSuggestionAdapter(results)
            if (labels.isNotEmpty()) {
                clearSearchFeedback()
                searchInput.showDropDown()
            } else {
                showSearchHelper(R.string.map_search_no_results)
                searchInput.dismissDropDown()
            }
        }
        GeocodeOutcome.Unavailable -> {
            updateSuggestionAdapter(emptyList())
            showSearchError(R.string.map_search_unavailable)
            searchInput.dismissDropDown()
        }
        is GeocodeOutcome.Failure -> {
            updateSuggestionAdapter(emptyList())
            showSearchError(R.string.map_search_error)
            searchInput.dismissDropDown()
        }
    }
}

private fun MapFragment.launchDirectSearch(query: String) {
    searchExecutionJob?.cancel()
    searchExecutionJob = viewLifecycleOwner.lifecycleScope.launch {
        when (val outcome = geocode(query, 8)) {
            is GeocodeOutcome.Success -> {
                val results = outcome.addresses
                if (results.isEmpty()) {
                    updateSuggestionAdapter(emptyList())
                    showSearchHelper(R.string.map_search_no_results)
                    searchInput.dismissDropDown()
                    return@launch
                }
                val labels = updateSuggestionAdapter(results)
                clearSearchFeedback()
                searchInput.dismissDropDown()
                val addr = results.first()
                val lat = addr.latitude
                val lon = addr.longitude
                val label = addr.getAddressLine(0)
                    ?: addr.featureName
                    ?: String.format(Locale.US, "%.5f, %.5f", lat, lon)
                if (labels.isNotEmpty()) {
                    searchInput.setText(label, false)
                }
                showSelectionFromSearch(
                    Place(
                        lat = lat,
                        lon = lon,
                        label = label,
                        accuracyM = null
                    )
                )
            }
            GeocodeOutcome.Unavailable -> {
                updateSuggestionAdapter(emptyList())
                showSearchError(R.string.map_search_unavailable)
                searchInput.dismissDropDown()
            }
            is GeocodeOutcome.Failure -> {
                updateSuggestionAdapter(emptyList())
                showSearchError(R.string.map_search_error)
                searchInput.dismissDropDown()
            }
        }
    }
}

@Suppress("DEPRECATION")
private suspend fun MapFragment.geocode(query: String, maxResults: Int): GeocodeOutcome {
    if (!Geocoder.isPresent()) {
        Log.w(MapFragment.TAG, "Geocoder unavailable on this device")
        return GeocodeOutcome.Unavailable
    }
    val ctx = requireContext().applicationContext
    return withContext(Dispatchers.IO) {
        val geocoder = Geocoder(ctx, Locale.getDefault())
        runCatching {
            geocoder.getFromLocationName(query, maxResults)
        }.fold(
            onSuccess = { GeocodeOutcome.Success(it.orEmpty()) },
            onFailure = {
                Log.w(MapFragment.TAG, "Geocoder failed for \"$query\"", it)
                GeocodeOutcome.Failure(it)
            }
        )
    }
}

private fun MapFragment.updateSuggestionAdapter(results: List<Address>): List<String> {
    searchResults = results
    val labels = results.map {
        it.getAddressLine(0) ?: it.featureName ?: "${it.latitude}, ${it.longitude}"
    }
    searchAdapter?.apply {
        clear()
        addAll(labels)
        notifyDataSetChanged()
    }
    return labels
}

private fun MapFragment.showSearchError(@StringRes resId: Int) {
    binding.searchContainer.error = getString(resId)
    binding.searchContainer.helperText = null
}

private fun MapFragment.showSearchHelper(@StringRes resId: Int) {
    binding.searchContainer.helperText = getString(resId)
    binding.searchContainer.error = null
}

internal fun MapFragment.clearSearchFeedback() {
    binding.searchContainer.error = null
    binding.searchContainer.helperText = null
}
