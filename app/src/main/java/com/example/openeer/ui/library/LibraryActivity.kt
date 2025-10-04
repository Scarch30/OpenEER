package com.example.openeer.ui.library

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.example.openeer.databinding.ActivityLibraryBinding

class LibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityLibraryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.title = "Bibliothèque"

        if (savedInstanceState == null) {
            showList()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_library, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_list -> { showList(); true }
            R.id.action_calendar -> { showCalendar(); true }
            R.id.action_map -> { showMap(); true } // ✅
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showMap() {
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, MapFragment.newInstance(), "maplibre") // ✅
            .commit()
    }



    private fun showList() {
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, LibraryFragment.newInstance(), "list")
            .commit()
    }

    private fun showCalendar() {
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, CalendarFragment.newInstance(), "calendar")
            .commit()
    }
}
