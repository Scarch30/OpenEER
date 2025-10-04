package com.example.openeer.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.databinding.ItemDayCellBinding

class MonthDayAdapter(
    private val onDayClick: (CalendarViewModel.DayCell) -> Unit
) : RecyclerView.Adapter<MonthDayAdapter.VH>() {

    private val items = ArrayList<CalendarViewModel.DayCell>()
    private var selectedStart: Long? = null

    fun submit(new: List<CalendarViewModel.DayCell>) {
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDayCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val b: ItemDayCellBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(d: CalendarViewModel.DayCell) {
            b.txtDay.text = d.label
            // Badge compteur
            if (d.count > 0) {
                b.badge.visibility = View.VISIBLE
                b.badge.text = d.count.toString()
            } else {
                b.badge.visibility = View.INVISIBLE
            }

            // Gris hors mois
            val alpha = if (d.isCurrentMonth) 1.0f else 0.35f
            b.root.alpha = alpha

            // Contour "aujourd’hui"
            b.card.strokeWidth = if (d.isToday) 3 else 0
            b.card.strokeColor = ContextCompat.getColor(
                b.root.context,
                R.color.purple_200 // adapte si besoin
            )

            // Sélection visuelle
            val isSelected = (selectedStart == d.startMs)
            b.selection.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

            b.root.setOnClickListener {
                selectedStart = d.startMs
                notifyDataSetChanged()
                onDayClick(d)
            }
        }
    }
}
