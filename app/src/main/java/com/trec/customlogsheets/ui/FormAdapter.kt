package com.trec.customlogsheets.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.Form

class FormAdapter(
    private val onFormClick: (Form) -> Unit
) : ListAdapter<Form, FormAdapter.FormViewHolder>(FormDiffCallback()) {

    private var completedFormIds: Set<String> = emptySet()
    private var draftFormIds: Set<String> = emptySet()

    fun setCompletedFormIds(completedIds: Set<String>) {
        completedFormIds = completedIds
        notifyDataSetChanged()
    }
    
    fun setDraftFormIds(draftIds: Set<String>) {
        draftFormIds = draftIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FormViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_form, parent, false)
        return FormViewHolder(view)
    }

    override fun onBindViewHolder(holder: FormViewHolder, position: Int) {
        val form = getItem(position)
        holder.bind(
            form, 
            completedFormIds.contains(form.id),
            draftFormIds.contains(form.id)
        )
    }

    inner class FormViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val formNameText: TextView = itemView.findViewById(R.id.textFormName)
        private val formDescriptionText: TextView = itemView.findViewById(R.id.textFormDescription)
        private val statusIcon: ImageView = itemView.findViewById(R.id.iconStatus)

        fun bind(form: Form, isCompleted: Boolean, isDraft: Boolean) {
            // Show mandatory indicator
            var formNameDisplay = if (form.mandatory) {
                "${form.name} *"
            } else {
                form.name
            }
            
            // Add draft indicator (only if not completed)
            if (isDraft && !isCompleted) {
                formNameDisplay = "$formNameDisplay (Draft)"
            }
            
            formNameText.text = formNameDisplay
            
            // Make mandatory forms visually distinct
            if (form.mandatory) {
                formNameText.setTextColor(0xFFD32F2F.toInt()) // Red for mandatory
                formNameText.textSize = 16.5f // Slightly larger
            } else {
                formNameText.setTextColor(0xFF212121.toInt()) // Default dark gray
                formNameText.textSize = 16f
            }
            
            formDescriptionText.text = form.description
            formDescriptionText.visibility = if (form.description != null) View.VISIBLE else View.GONE

            // Visual indication of completion status
            if (isCompleted) {
                statusIcon.setImageResource(android.R.drawable.checkbox_on_background)
                itemView.setBackgroundColor(0xFFE8F5E9.toInt()) // Light green for completed
            } else {
                if (isDraft) {
                    // Show draft icon (edit icon or similar)
                    statusIcon.setImageResource(android.R.drawable.ic_menu_edit)
                    // Light yellow/orange background for drafts
                    itemView.setBackgroundColor(0xFFFFF3E0.toInt()) // Light orange for drafts
                } else {
                    statusIcon.setImageResource(android.R.drawable.checkbox_off_background)
                    if (form.mandatory) {
                        itemView.setBackgroundColor(0xFFFFEBEE.toInt()) // Light red for mandatory incomplete
                    } else {
                        itemView.setBackgroundColor(0xFFFFFFFF.toInt()) // White for not completed
                    }
                }
            }

            itemView.setOnClickListener {
                onFormClick(form)
            }
        }
    }

    class FormDiffCallback : DiffUtil.ItemCallback<Form>() {
        override fun areItemsTheSame(oldItem: Form, newItem: Form): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Form, newItem: Form): Boolean {
            return oldItem == newItem
        }
    }
}

