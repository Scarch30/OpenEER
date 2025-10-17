package com.example.openeer.ui.sheets

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.domain.ReminderUseCases
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

class ReminderListSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"

        fun newInstance(noteId: Long) = ReminderListSheet().apply {
            arguments = bundleOf(ARG_NOTE_ID to noteId)
        }

        const val ACTION_REMINDERS_CHANGED = "com.example.openeer.REMINDERS_CHANGED"
        const val EXTRA_NOTE_ID = "extra_note_id"

        fun notifyChangedBroadcast(ctx: Context, noteId: Long) {
            ctx.sendBroadcast(Intent(ACTION_REMINDERS_CHANGED).apply {
                setPackage(ctx.packageName)
                putExtra(EXTRA_NOTE_ID, noteId)
            })
        }
    }

    private val noteId: Long
        get() = requireArguments().getLong(ARG_NOTE_ID)

    private lateinit var reminderList: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: ReminderAdapter

    private val reminderChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val targetId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L) ?: return
            if (targetId == noteId) {
                reloadReminders()
            }
        }
    }

    private var reminderReceiverRegistered = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.sheet_reminder_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reminderList = view.findViewById(R.id.listReminders)
        emptyView = view.findViewById(R.id.emptyState)

        adapter = ReminderAdapter(
            onSnooze = { reminder, minutes -> handleSnooze(reminder, minutes) },
            onCancel = { reminder -> handleCancel(reminder) }
        )
        reminderList.adapter = adapter

        reloadReminders()

        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                registerReceiver()
            }

            override fun onStop(owner: LifecycleOwner) {
                unregisterReceiver()
            }
        })
    }

    private fun registerReceiver() {
        if (reminderReceiverRegistered) return
        val ctx = requireContext()
        ContextCompat.registerReceiver(
            ctx,
            reminderChangedReceiver,
            IntentFilter(ACTION_REMINDERS_CHANGED),
            RECEIVER_NOT_EXPORTED
        )
        reminderReceiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!reminderReceiverRegistered) return
        val ctx = runCatching { requireContext() }.getOrNull() ?: return
        runCatching { ctx.unregisterReceiver(reminderChangedReceiver) }
        reminderReceiverRegistered = false
    }

    private fun reloadReminders() {
        viewLifecycleOwner.lifecycleScope.launch {
            val appCtx = requireContext().applicationContext
            val items = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(appCtx)
                val reminderDao = db.reminderDao()
                val blockDao = db.blockDao()
                val reminders = reminderDao.listForNoteOrdered(noteId)
                val cache = mutableMapOf<Long, String?>()
                reminders.map { reminder ->
                    val label = reminder.blockId?.let { blockId ->
                        cache.getOrPut(blockId) {
                            blockDao.getById(blockId)?.text
                                ?.lineSequence()
                                ?.firstOrNull()
                                ?.removePrefix("⏰")
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                        }
                    }
                    ReminderItem(reminder, label)
                }
            }
            adapter.submitList(items)
            val isEmpty = items.isEmpty()
            emptyView.isVisible = isEmpty
            reminderList.isVisible = !isEmpty
        }
    }

    private fun handleSnooze(reminder: ReminderEntity, minutes: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appCtx = requireContext().applicationContext
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(appCtx)
                val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val useCases = ReminderUseCases(appCtx, db, alarmManager)
                useCases.snooze(reminder.id, minutes)
            }
            notifyChangedBroadcast(appCtx, noteId)
            reloadReminders()
        }
    }

    private fun handleCancel(reminder: ReminderEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appCtx = requireContext().applicationContext
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(appCtx)
                val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val useCases = ReminderUseCases(appCtx, db, alarmManager)
                useCases.cancel(reminder.id)
                db.reminderDao().update(
                    reminder.copy(
                        status = "CANCELLED",
                        lastFiredAt = System.currentTimeMillis()
                    )
                )
            }
            notifyChangedBroadcast(appCtx, noteId)
            reloadReminders()
        }
    }

    private class ReminderAdapter(
        private val onSnooze: (ReminderEntity, Int) -> Unit,
        private val onCancel: (ReminderEntity) -> Unit
    ) : ListAdapter<ReminderItem, ReminderAdapter.ReminderViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReminderViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val padding = (16 * density).toInt()
            val spacing = (8 * density).toInt()

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }

            val titleView = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            }
            val subtitleView = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                visibility = View.GONE
            }

            val buttonsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, spacing, 0, 0)
            }

            val textButtonStyle = com.google.android.material.R.style.Widget_Material3_Button_TextButton
            val snooze5Button = MaterialButton(ContextThemeWrapper(ctx, textButtonStyle)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = spacing }
                text = ctx.getString(R.string.reminder_action_snooze_5)
                isAllCaps = false
            }
            val snooze60Button = MaterialButton(ContextThemeWrapper(ctx, textButtonStyle)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = spacing }
                text = ctx.getString(R.string.reminder_action_snooze_60)
                isAllCaps = false
            }
            val cancelButton = MaterialButton(ContextThemeWrapper(ctx, textButtonStyle)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = ctx.getString(R.string.reminder_action_cancel)
                isAllCaps = false
            }

            buttonsRow.addView(snooze5Button)
            buttonsRow.addView(snooze60Button)
            buttonsRow.addView(cancelButton)

            container.addView(titleView)
            container.addView(subtitleView)
            container.addView(buttonsRow)

            return ReminderViewHolder(
                container,
                titleView,
                subtitleView,
                snooze5Button,
                snooze60Button,
                cancelButton,
                buttonsRow
            )
        }

        override fun onBindViewHolder(holder: ReminderViewHolder, position: Int) {
            holder.bind(getItem(position), onSnooze, onCancel)
        }

        class ReminderViewHolder(
            itemView: View,
            private val titleView: TextView,
            private val subtitleView: TextView,
            private val snooze5Button: MaterialButton,
            private val snooze60Button: MaterialButton,
            private val cancelButton: MaterialButton,
            private val buttonsRow: LinearLayout
        ) : RecyclerView.ViewHolder(itemView) {

            fun bind(
                item: ReminderItem,
                onSnooze: (ReminderEntity, Int) -> Unit,
                onCancel: (ReminderEntity) -> Unit
            ) {
                val ctx = itemView.context
                titleView.text = buildTitle(ctx, item)

                // Subtitle optional for future use
                subtitleView.visibility = View.GONE

                val isActive = item.entity.status == "ACTIVE"
                buttonsRow.alpha = if (isActive) 1f else 0.6f
                snooze5Button.isEnabled = isActive
                snooze60Button.isEnabled = isActive
                cancelButton.isEnabled = isActive

                snooze5Button.setOnClickListener { onSnooze(item.entity, 5) }
                snooze60Button.setOnClickListener { onSnooze(item.entity, 60) }
                cancelButton.setOnClickListener { onCancel(item.entity) }
            }

            private fun buildTitle(ctx: Context, item: ReminderItem): String {
                val builder = StringBuilder()
                val blockLabel = item.blockLabel
                if (!blockLabel.isNullOrBlank()) {
                    builder.append(blockLabel)
                } else {
                    val entity = item.entity
                    val lat = entity.lat
                    val lon = entity.lon
                    if (lat != null && lon != null) {
                        builder.append(String.format(Locale.US, "📍 %.5f, %.5f", lat, lon))
                        entity.radius?.takeIf { it > 0 }?.let { radius ->
                            builder.append(" · ~").append(radius).append("m")
                        }
                    } else {
                        builder.append("⏰ ")
                        val triggerDate = Date(entity.nextTriggerAt)
                        builder.append(DateFormat.getTimeFormat(ctx).format(triggerDate))
                        if (!DateUtils.isToday(entity.nextTriggerAt)) {
                            builder.append(" · ")
                            builder.append(DateFormat.getMediumDateFormat(ctx).format(triggerDate))
                        }
                    }
                }
                builder.append("  • ")
                builder.append(item.entity.status)
                return builder.toString()
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ReminderItem>() {
                override fun areItemsTheSame(oldItem: ReminderItem, newItem: ReminderItem): Boolean =
                    oldItem.entity.id == newItem.entity.id

                override fun areContentsTheSame(oldItem: ReminderItem, newItem: ReminderItem): Boolean =
                    oldItem == newItem
            }
        }
    }

    private data class ReminderItem(
        val entity: ReminderEntity,
        val blockLabel: String?,
    )
}
