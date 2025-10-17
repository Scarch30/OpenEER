package com.example.openeer.ui.panel.blocks

import android.app.AlarmManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.domain.ReminderUseCases
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STATUS_ACTIVE = "ACTIVE"
private const val STATUS_CANCELLED = "CANCELLED"
private const val STATUS_DONE = "DONE"

object BlockRenderers {
    fun createUnsupportedBlockView(context: Context, block: BlockEntity, margin: Int): View {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = block.type.name
                setPadding(padding, padding, padding, padding)
            })
        }
    }

    fun createTextBlockView(
        context: Context,
        block: BlockEntity,
        margin: Int,
        lifecycleOwner: LifecycleOwner,
    ): View {
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val spacing = (8 * density).toInt()

        val textView = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 16f
        }

        val cancelButton = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = spacing }
            text = context.getString(R.string.reminder_cancel_action)
            isVisible = false
            isAllCaps = false
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(padding, padding, padding, padding)
            addView(textView)
            addView(cancelButton)
        }

        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(inner)
        }

        var currentReminder: ReminderEntity? = null

        fun render(reminder: ReminderEntity?) {
            currentReminder = reminder
            val raw = block.text?.trim().orEmpty()
            val displayBase = when {
                reminder != null && raw.startsWith("⏰") -> raw
                reminder != null && raw.isNotBlank() -> "⏰ $raw"
                reminder != null -> "⏰"
                else -> raw
            }
            val finalText = if (reminder?.status == STATUS_CANCELLED && !displayBase.contains("(annulé)")) {
                if (displayBase.isBlank()) "(annulé)" else "$displayBase (annulé)"
            } else {
                displayBase
            }
            textView.text = finalText

            when (reminder?.status) {
                STATUS_ACTIVE -> {
                    cancelButton.isVisible = true
                    cancelButton.isEnabled = true
                    card.alpha = 1f
                }
                STATUS_CANCELLED, STATUS_DONE -> {
                    cancelButton.isVisible = false
                    card.alpha = 0.6f
                }
                else -> {
                    cancelButton.isVisible = false
                    card.alpha = 1f
                }
            }
        }

        render(null)

        val scope = lifecycleOwner.lifecycleScope
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)

        scope.launch {
            val reminder = withContext(Dispatchers.IO) { db.reminderDao().byBlockId(block.id) }
            render(reminder)
        }

        cancelButton.setOnClickListener {
            val active = currentReminder
            if (active == null || active.status != STATUS_ACTIVE) return@setOnClickListener
            cancelButton.isEnabled = false
            scope.launch {
                val result = runCatching {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val useCases = ReminderUseCases(appContext, db, alarmManager)
                    useCases.cancel(active.id)
                    val cancelled = active.copy(
                        status = STATUS_CANCELLED,
                        lastFiredAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) { db.reminderDao().update(cancelled) }
                    cancelled
                }
                result.onSuccess { cancelled ->
                    render(cancelled)
                    Toast.makeText(context, R.string.reminder_cancelled, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    cancelButton.isEnabled = true
                    Toast.makeText(context, R.string.reminder_error_schedule, Toast.LENGTH_SHORT).show()
                }
            }
        }

        return card
    }
}
