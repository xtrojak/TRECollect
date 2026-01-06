package com.trec.customlogsheets.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.SiteStatus

class SamplingSiteAdapter(
    private val onItemClick: (SamplingSite) -> Unit
) : ListAdapter<SamplingSite, SamplingSiteAdapter.SiteViewHolder>(SiteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SiteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sampling_site, parent, false)
        return SiteViewHolder(view)
    }

    override fun onBindViewHolder(holder: SiteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SiteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val siteNameText: TextView = itemView.findViewById(R.id.textViewSiteName)

        fun bind(site: SamplingSite) {
            siteNameText.text = site.name
            
            itemView.setOnClickListener {
                onItemClick(site)
            }
        }
    }

    class SiteDiffCallback : DiffUtil.ItemCallback<SamplingSite>() {
        override fun areItemsTheSame(oldItem: SamplingSite, newItem: SamplingSite): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SamplingSite, newItem: SamplingSite): Boolean {
            return oldItem == newItem
        }
    }
}

