package com.example.openeer.ui.sheets

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.favorites.FavoriteEntity

class FavoritePlaceAdapter(
    private val onFavoriteClicked: (FavoriteEntity) -> Unit
) : ListAdapter<FavoriteEntity, FavoritePlaceAdapter.FavoriteViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        return FavoriteViewHolder(view, onFavoriteClicked)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FavoriteViewHolder(
        private val textView: TextView,
        private val onFavoriteClicked: (FavoriteEntity) -> Unit
    ) : RecyclerView.ViewHolder(textView) {

        fun bind(favorite: FavoriteEntity) {
            textView.text = favorite.displayName
            itemView.setOnClickListener { onFavoriteClicked(favorite) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FavoriteEntity>() {
            override fun areItemsTheSame(oldItem: FavoriteEntity, newItem: FavoriteEntity): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: FavoriteEntity, newItem: FavoriteEntity): Boolean {
                return oldItem == newItem
            }
        }
    }
}
