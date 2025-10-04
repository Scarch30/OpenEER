package com.example.openeer.ui.library

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.databinding.FragmentLibraryBinding
import com.example.openeer.ui.NotesAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!

    private lateinit var vm: LibraryViewModel
    private lateinit var adapter: NotesAdapter

    private var debounceJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext().applicationContext
        val db = AppDatabase.get(ctx)
        vm = LibraryViewModel.create(db)

        adapter = NotesAdapter(
            onClick = { note -> openNote(note) },
            onLongClick = { _ -> /* no-op pour l’instant */ }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // debounce 250ms
                debounceJob?.cancel()
                debounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(250)
                    val q = s?.toString().orEmpty().trim()
                    // requête vide => remonte toutes les notes
                    vm.search(if (q.isEmpty()) "" else q)
                }
            }
        })

        // Chargement initial : toutes les notes
        vm.search("")

        // Collect UI
        viewLifecycleOwner.lifecycleScope.launch {
            vm.items.collectLatest { list ->
                adapter.submitList(list)
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.loading.collectLatest { loading ->
                b.progress.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openNote(n: Note) {
        // À brancher sur ton écran de détail (intent/fragment) si nécessaire.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object { fun newInstance() = LibraryFragment() }
}
