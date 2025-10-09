package com.example.openeer.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.example.openeer.R
import com.example.openeer.databinding.ActivityLibraryBinding

class LibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityLibraryBinding

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
            R.id.action_map -> {
                startActivity(MapActivity.newBrowseIntent(this))
                true
            }
            R.id.action_merge_history -> { showMergeHistory(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun handleIntent(_intent: Intent, allowDefault: Boolean) {
        if (allowDefault) {
            showList()
        }
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
