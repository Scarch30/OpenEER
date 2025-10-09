package com.example.openeer.ui.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.example.openeer.R
import com.example.openeer.databinding.ActivityLibraryBinding

class LibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityLibraryBinding

    companion object {
        private const val EXTRA_START_DEST = "com.example.openeer.library.EXTRA_START_DEST"
        private const val EXTRA_NOTE_ID = "com.example.openeer.library.EXTRA_NOTE_ID"
        private const val DEST_MAP = "map"

        fun intentForMap(context: Context, noteId: Long? = null): Intent {
            return Intent(context, LibraryActivity::class.java).apply {
                putExtra(EXTRA_START_DEST, DEST_MAP)
                if (noteId != null && noteId > 0) {
                    putExtra(EXTRA_NOTE_ID, noteId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(R.string.library_title)

        supportFragmentManager.addOnBackStackChangedListener {
            updateActionBarForCurrentFragment()
        }

        if (savedInstanceState == null) {
            handleIntent(intent, allowDefault = true)
        } else {
            updateActionBarForCurrentFragment()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, allowDefault = false)
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
            R.id.action_merge_history -> { showMergeHistory(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun showMap(noteId: Long? = null) {
        clearBackStack()
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, MapFragment.newInstance(noteId), "maplibre") // ✅
            .commit()
        updateActionBarForCurrentFragment()
    }

    private fun handleIntent(intent: Intent, allowDefault: Boolean) {
        if (shouldShowMap(intent)) {
            val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L).takeIf { it > 0 }
            val logMessage = if (allowDefault) {
                "Launching map start destination"
            } else {
                "Switching to map via new intent"
            }
            Log.d("MapNav", logMessage)
            showMap(noteId)
            intent.removeExtra(EXTRA_START_DEST)
            intent.removeExtra(EXTRA_NOTE_ID)
        } else if (allowDefault) {
            showList()
        }
    }

    private fun shouldShowMap(intent: Intent): Boolean {
        return intent.getStringExtra(EXTRA_START_DEST) == DEST_MAP
    }



    private fun showList() {
        clearBackStack()
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, LibraryFragment.newInstance(), "list")
            .commit()
        updateActionBarForCurrentFragment()
    }

    private fun showCalendar() {
        clearBackStack()
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, CalendarFragment.newInstance(), "calendar")
            .commit()
        updateActionBarForCurrentFragment()
    }

    private fun showMergeHistory() {
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, MergeHistoryFragment.newInstance(), "merge_history")
            .addToBackStack("merge_history")
            .commit()
        updateActionBarForCurrentFragment()
    }

    private fun clearBackStack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    private fun updateActionBarForCurrentFragment() {
        val hasBackStack = supportFragmentManager.backStackEntryCount > 0
        supportActionBar?.setDisplayHomeAsUpEnabled(hasBackStack)
        val current = supportFragmentManager.findFragmentById(b.container.id)
        val titleRes = if (current is MergeHistoryFragment) {
            R.string.merge_history_title
        } else {
            R.string.library_title
        }
        supportActionBar?.title = getString(titleRes)
    }
}
