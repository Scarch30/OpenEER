package com.example.openeer.ui.map

/**
 * Identifiants de style MapLibre centralisés pour éviter les chaînes magiques
 * et faciliter les évolutions de rendu (sources, layers, icônes, etc.).
 */
object MapStyleIds {
    // Sources
    const val SRC_NOTES = "notes-source"

    // Layers
    const val LYR_NOTES = "notes-layer"

    // Icons
    const val ICON_SINGLE = "icon-single"
    const val ICON_FEW = "icon-few"
    const val ICON_MANY = "icon-many"
    const val ICON_HERE = "icon-here"
    const val ICON_SELECTION = "icon-selection"
}
