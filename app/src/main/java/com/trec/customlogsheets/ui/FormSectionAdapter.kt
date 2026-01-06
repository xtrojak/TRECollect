package com.trec.customlogsheets.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.Form

class FormSectionAdapter(
    private val onFormClick: (Form) -> Unit
) : RecyclerView.Adapter<FormSectionAdapter.SectionViewHolder>() {

    private var sections: List<String> = emptyList()
    private var formsBySection: Map<String, List<Form>> = emptyMap()
    private var completedFormIds: Set<String> = emptySet()

    fun setData(sections: List<String>, formsBySection: Map<String, List<Form>>, completedFormIds: Set<String>) {
        this.sections = sections
        this.formsBySection = formsBySection
        this.completedFormIds = completedFormIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_form_section, parent, false)
        return SectionViewHolder(view, onFormClick)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        val section = sections[position]
        val forms = formsBySection[section] ?: emptyList()
        holder.bind(section, forms, completedFormIds)
    }

    override fun getItemCount(): Int = sections.size

    class SectionViewHolder(
        itemView: android.view.View,
        private val onFormClick: (Form) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val sectionTitleText: android.widget.TextView = itemView.findViewById(R.id.textSectionTitle)
        private val formsRecyclerView: RecyclerView = itemView.findViewById(R.id.recyclerViewForms)
        private val formAdapter = FormAdapter(onFormClick)

        init {
            formsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            formsRecyclerView.adapter = formAdapter
            formsRecyclerView.isNestedScrollingEnabled = false // Disable nested scrolling for better performance
        }

        fun bind(section: String, forms: List<Form>, completedFormIds: Set<String>) {
            sectionTitleText.text = section
            formAdapter.submitList(forms)
            formAdapter.setCompletedFormIds(completedFormIds)
        }
    }
}

