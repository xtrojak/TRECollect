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

    fun setCompletedFormIds(completedIds: Set<String>) {
        completedFormIds = completedIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FormViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_form, parent, false)
        return FormViewHolder(view)
    }

    override fun onBindViewHolder(holder: FormViewHolder, position: Int) {
        holder.bind(getItem(position), completedFormIds.contains(getItem(position).id))
    }

    inner class FormViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val formNameText: TextView = itemView.findViewById(R.id.textFormName)
        private val formDescriptionText: TextView = itemView.findViewById(R.id.textFormDescription)
        private val statusIcon: ImageView = itemView.findViewById(R.id.iconStatus)

        fun bind(form: Form, isCompleted: Boolean) {
            formNameText.text = form.name
            formDescriptionText.text = form.description
            formDescriptionText.visibility = if (form.description != null) View.VISIBLE else View.GONE

            // Visual indication of completion status
            if (isCompleted) {
                statusIcon.setImageResource(android.R.drawable.checkbox_on_background)
                itemView.setBackgroundColor(0xFFE8F5E9.toInt()) // Light green for completed
            } else {
                statusIcon.setImageResource(android.R.drawable.checkbox_off_background)
                itemView.setBackgroundColor(0xFFFFFFFF.toInt()) // White for not completed
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

