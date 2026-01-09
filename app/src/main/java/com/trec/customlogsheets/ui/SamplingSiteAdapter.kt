package com.trec.customlogsheets.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.SamplingSite
import com.trec.customlogsheets.data.SiteStatus
import com.trec.customlogsheets.data.UploadStatus

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
        private val uploadStatusIcon: ImageView = itemView.findViewById(R.id.imageViewUploadStatus)

        fun bind(site: SamplingSite) {
            siteNameText.text = site.name
            
            // Show upload status icon only for finished sites
            com.trec.customlogsheets.util.AppLogger.d("SamplingSiteAdapter", "Binding site: name='${site.name}', status=${site.status}, uploadStatus=${site.uploadStatus}")
            
            if (site.status == SiteStatus.FINISHED) {
                uploadStatusIcon.visibility = View.VISIBLE
                // Clear any previous color filter
                uploadStatusIcon.clearColorFilter()
                
                when (site.uploadStatus) {
                    UploadStatus.UPLOADED -> {
                        // Green cloud icon - use a more visible icon
                        uploadStatusIcon.setImageResource(android.R.drawable.ic_menu_upload)
                        val greenColor = ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                        uploadStatusIcon.setColorFilter(greenColor, android.graphics.PorterDuff.Mode.SRC_IN)
                        uploadStatusIcon.contentDescription = "Uploaded"
                        com.trec.customlogsheets.util.AppLogger.d("SamplingSiteAdapter", "Set UPLOADED icon (green) for: ${site.name}")
                    }
                    UploadStatus.UPLOAD_FAILED -> {
                        // Red crossed cloud icon - use a more visible icon
                        uploadStatusIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        val redColor = ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                        uploadStatusIcon.setColorFilter(redColor, android.graphics.PorterDuff.Mode.SRC_IN)
                        uploadStatusIcon.contentDescription = "Upload failed"
                        com.trec.customlogsheets.util.AppLogger.d("SamplingSiteAdapter", "Set UPLOAD_FAILED icon (red) for: ${site.name}")
                    }
                    UploadStatus.UPLOADING -> {
                        // Yellow/orange cloud icon (uploading)
                        uploadStatusIcon.setImageResource(android.R.drawable.ic_menu_upload)
                        val orangeColor = ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark)
                        uploadStatusIcon.setColorFilter(orangeColor, android.graphics.PorterDuff.Mode.SRC_IN)
                        uploadStatusIcon.contentDescription = "Uploading"
                        com.trec.customlogsheets.util.AppLogger.d("SamplingSiteAdapter", "Set UPLOADING icon (orange) for: ${site.name}")
                    }
                    com.trec.customlogsheets.data.UploadStatus.NOT_UPLOADED -> {
                        // Gray cloud icon
                        uploadStatusIcon.setImageResource(android.R.drawable.ic_menu_upload)
                        val grayColor = ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
                        uploadStatusIcon.setColorFilter(grayColor, android.graphics.PorterDuff.Mode.SRC_IN)
                        uploadStatusIcon.contentDescription = "Not uploaded"
                        com.trec.customlogsheets.util.AppLogger.d("SamplingSiteAdapter", "Set NOT_UPLOADED icon (gray) for: ${site.name}")
                    }
                }
                com.trec.customlogsheets.util.AppLogger.d("SamplingSiteAdapter", "Set icon visibility to VISIBLE for finished site: ${site.name}, uploadStatus=${site.uploadStatus}")
            } else {
                uploadStatusIcon.visibility = View.GONE
                com.trec.customlogsheets.util.AppLogger.d("SamplingSiteAdapter", "Set icon visibility to GONE for ongoing site: ${site.name}")
            }
            
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

