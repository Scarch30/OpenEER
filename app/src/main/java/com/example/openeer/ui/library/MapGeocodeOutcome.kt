package com.example.openeer.ui.library

import android.location.Address

internal sealed interface GeocodeOutcome {
    data class Success(val addresses: List<Address>) : GeocodeOutcome
    data object Unavailable : GeocodeOutcome
    data class Failure(val throwable: Throwable) : GeocodeOutcome
}
