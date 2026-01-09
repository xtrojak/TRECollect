package com.trec.customlogsheets.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.SiteStatus
import com.trec.customlogsheets.data.UploadStatus

class SamplingSiteAdapter(
    private val onItemClick: (SamplingSite) -> Unit,
    private val onUploadClick: ((SamplingSite) -> Unit)? = null
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
        private val uploadStatusCheckBox: CheckBox = itemView.findViewById(R.id.checkBoxUploadStatus)
        private val uploadButton: ImageButton = itemView.findViewById(R.id.buttonUpload)

        fun bind(site: SamplingSite) {
            siteNameText.text = site.name
            
            // Show upload status checkbox and upload button only for finished sites
            if (site.status == SiteStatus.FINISHED) {
                uploadStatusCheckBox.visibility = View.VISIBLE
                uploadButton.visibility = View.VISIBLE
                
                // Set checkbox checked state based on upload status
                uploadStatusCheckBox.isChecked = site.uploadStatus == UploadStatus.UPLOADED
                
                // Prevent checkbox from getting focus/ripple on item click
                uploadStatusCheckBox.isFocusable = false
                uploadStatusCheckBox.isClickable = false
                
                // Set upload button click listener
                uploadButton.setOnClickListener {
                    onUploadClick?.invoke(site)
                }
            } else {
                uploadStatusCheckBox.visibility = View.GONE
                uploadButton.visibility = View.GONE
            }
            
            // Allow navigation to details for all sites
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

