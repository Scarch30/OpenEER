package com.example.openeer.ui.map

import android.content.Context
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.maps.Style

/**
 * Prépare les icônes et (optionnellement) la couche notes.
 * On ne fait AUCUNE logique métier ici, juste du style.
 */
object MapIcons {

    /** Ajoute les bitmaps des icônes si absents. */
    fun ensureDefaultIcons(style: Style, context: Context) {
        if (style.getImage(MapStyleIds.ICON_SINGLE) == null)
            style.addImage(MapStyleIds.ICON_SINGLE, MapRenderers.makeDot(context, 22,  com.example.openeer.R.color.purple_500))

        if (style.getImage(MapStyleIds.ICON_FEW) == null)
            style.addImage(MapStyleIds.ICON_FEW, MapRenderers.makeDot(context, 22, android.R.color.holo_blue_light))

        if (style.getImage(MapStyleIds.ICON_MANY) == null)
            style.addImage(MapStyleIds.ICON_MANY, MapRenderers.makeDot(context, 22, android.R.color.holo_red_light))

        if (style.getImage(MapStyleIds.ICON_HERE) == null)
            style.addImage(MapStyleIds.ICON_HERE, MapRenderers.makeDot(context, 24, com.example.openeer.R.color.teal_200))

        if (style.getImage(MapStyleIds.ICON_SELECTION) == null)
            style.addImage(MapStyleIds.ICON_SELECTION, MapRenderers.makeDot(context, 18, com.example.openeer.R.color.map_pin_selection))
    }

    /**
     * (Optionnel) assure la présence de la source & du layer pour les notes.
     * Appelle-la si tu veux centraliser aussi la création du layer.
     */
    fun ensureNotesSourceAndLayer(style: Style) {
        if (style.getSource(MapStyleIds.SRC_NOTES) == null) {
            style.addSource(GeoJsonSource(MapStyleIds.SRC_NOTES))
        }
        if (style.getLayer(MapStyleIds.LYR_NOTES) == null) {
            style.addLayer(
                SymbolLayer(MapStyleIds.LYR_NOTES, MapStyleIds.SRC_NOTES).withProperties(
                    iconImage("{icon}"),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    textField("{count}"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP)
                )
            )
        }
    }
}
