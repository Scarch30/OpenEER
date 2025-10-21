package com.example.openeer.data.favorites

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    indices = [Index(value = ["key"], unique = true)]
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "displayName")
    val displayName: String,
    @ColumnInfo(name = "aliasesJson")
    val aliasesJson: String = "[]",
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    @ColumnInfo(name = "defaultRadiusMeters")
    val defaultRadiusMeters: Int = DEFAULT_RADIUS_METERS,
    @ColumnInfo(name = "defaultCooldownMinutes")
    val defaultCooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
    @ColumnInfo(name = "defaultEveryTime")
    val defaultEveryTime: Boolean = false,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long
) {
    companion object {
        const val DEFAULT_RADIUS_METERS: Int = 100
        const val DEFAULT_COOLDOWN_MINUTES: Int = 30
    }
}
