package com.example.openeer.data.reminders

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.openeer.data.Note
import com.example.openeer.data.block.BlockEntity

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["blockId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["blockId"]),
        Index(value = ["status", "nextTriggerAt"])
    ]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val blockId: Long? = null,
    val label: String? = null,
    val type: String,
    val nextTriggerAt: Long,
    val lastFiredAt: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val radius: Int? = null,
    val status: String,
    val cooldownMinutes: Int? = null,
    val repeatEveryMinutes: Int? = null,
    val triggerOnExit: Boolean = false,
    val disarmedUntilExit: Boolean = false,
    val delivery: String = DELIVERY_NOTIFICATION,
    val armedAt: Long? = null
) {

    enum class Transition { ENTER, EXIT }

    fun isExit(): Boolean = disarmedUntilExit || triggerOnExit

    val transition: Transition
        get() = if (isExit()) Transition.EXIT else Transition.ENTER

    companion object {
        const val TYPE_TIME_ONE_SHOT = "TIME_ONE_SHOT"
        const val TYPE_TIME_REPEATING = "TIME_REPEATING"
        const val TYPE_LOC_ONCE = "LOC_ONCE"
        const val TYPE_LOC_EVERY = "LOC_EVERY"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_DONE = "DONE"
        const val STATUS_PAUSED = "PAUSED"

        const val DELIVERY_NOTIFICATION = "NOTIFICATION"
        const val DELIVERY_ALARM = "ALARM"
    }
}
