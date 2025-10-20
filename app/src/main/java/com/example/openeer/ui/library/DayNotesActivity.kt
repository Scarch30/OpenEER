package com.example.openeer.ui.library

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.data.AppDatabase
import com.example.openeer.databinding.ActivityDayNotesBinding
import com.example.openeer.ui.NotesAdapter
import com.example.openeer.ui.sheets.ReminderListSheet
import kotlinx.coroutines.launch

class DayNotesActivity : AppCompatActivity() {

    private lateinit var b: ActivityDayNotesBinding
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDayNotesBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra("title") ?: "Notes"

        val startMs = intent.getLongExtra("startMs", 0L)
        val endMs = intent.getLongExtra("endMs", 0L)

        adapter = NotesAdapter(
            onClick = {},
            onLongClick = {},
            onReminderClick = { note ->
                ReminderListSheet
                    .newInstance(note.id)
                    .show(supportFragmentManager, "reminder_list")
            }
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        val dao = AppDatabase.get(applicationContext).noteDao()
        lifecycleScope.launch {
            val list = dao.getByDay(startMs, endMs)
            adapter.submitList(list)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
