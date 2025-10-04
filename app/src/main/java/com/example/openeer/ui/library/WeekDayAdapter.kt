package com.example.openeer.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.databinding.ItemWeekDayBinding

class WeekDayAdapter(
    private val onDayClick: (CalendarViewModel.DayRow) -> Unit
) : RecyclerView.Adapter<WeekDayAdapter.VH>() {

    private val items = ArrayList<CalendarViewModel.DayRow>()

    fun submit(new: List<CalendarViewModel.DayRow>) {
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemWeekDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val b: ItemWeekDayBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(d: CalendarViewModel.DayRow) {
            b.txtLabel.text = d.label
            b.txtCount.text = d.count.toString()
            b.txtPreview.text = d.preview.joinToString(" • ")
            b.root.setOnClickListener { onDayClick(d) }
        }
    }
}
