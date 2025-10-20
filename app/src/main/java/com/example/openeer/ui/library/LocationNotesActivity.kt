package com.example.openeer.ui.library

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.data.AppDatabase
import com.example.openeer.databinding.ActivityDayNotesBinding
import com.example.openeer.ui.NotesAdapter
import com.example.openeer.ui.sheets.ReminderListSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.round

class LocationNotesActivity : AppCompatActivity() {

    private lateinit var b: ActivityDayNotesBinding
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // on réutilise le layout de DayNotes (toolbar + recycler simples)
        b = ActivityDayNotesBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Notes à proximité"
        supportActionBar?.title = title

        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)

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
            val list = withContext(Dispatchers.IO) {
                // filtre “proche” : même arrondi à 3 décimales
                val all = dao.getAllWithLocation()
                val key = keyFor(lat, lon)
                all.filter { it.lat != null && it.lon != null && keyFor(it.lat!!, it.lon!!) == key }
            }
            adapter.submitList(list)
        }
    }

    private fun keyFor(lat: Double, lon: Double): String {
        fun r(x: Double) = round(x * 1000.0) / 1000.0
        return "${r(lat)};${r(lon)}"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_TITLE = "title"
    }
}
