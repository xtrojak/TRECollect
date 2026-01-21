package com.trec.customlogsheets.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.Form
import com.trec.customlogsheets.util.AppLogger

class FormSectionAdapter(
    private val onFormClick: (Form) -> Unit,
    private val onAddDynamicForm: ((Form) -> Unit)? = null,
    private val onDeleteDynamicForm: ((Form, Int) -> Unit)? = null
) : RecyclerView.Adapter<FormSectionAdapter.SectionViewHolder>() {

    private var sections: List<String> = emptyList()
    private var formsBySection: Map<String, List<Form>> = emptyMap()
    private var completedFormIds: Set<String> = emptySet()
    private var draftFormIds: Set<String> = emptySet()
    private var canAddDynamicForm: ((Form) -> Boolean)? = null
    
    val sectionsList: List<String>
        get() = sections


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_form_section, parent, false)
        return SectionViewHolder(view, onFormClick, onAddDynamicForm, onDeleteDynamicForm)
    }

    private var baseFormsBySection: Map<String, List<Form>> = emptyMap()
    
    fun setData(sections: List<String>, formsBySection: Map<String, List<Form>>, completedFormIds: Set<String>, draftFormIds: Set<String> = emptySet(), canAddDynamicForm: ((Form) -> Boolean)? = null, baseFormsBySection: Map<String, List<Form>> = emptyMap()) {
        this.sections = sections
        this.formsBySection = formsBySection
        this.baseFormsBySection = baseFormsBySection
        this.completedFormIds = completedFormIds
        this.draftFormIds = draftFormIds
        this.canAddDynamicForm = canAddDynamicForm
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        val section = sections[position]
        val forms = formsBySection[section] ?: emptyList()
        val baseForms = baseFormsBySection[section] ?: emptyList()
        holder.bind(section, forms, completedFormIds, draftFormIds, canAddDynamicForm, baseForms)
    }

    override fun getItemCount(): Int = sections.size
    
    fun getSectionForForm(formId: String): String? {
        return formsBySection.entries.firstOrNull { (_, forms) ->
            forms.any { it.id == formId }
        }?.key
    }

    class SectionViewHolder(
        itemView: android.view.View,
        private val onFormClick: (Form) -> Unit,
        private val onAddDynamicForm: ((Form) -> Unit)?,
        private val onDeleteDynamicForm: ((Form, Int) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val sectionTitleText: android.widget.TextView = itemView.findViewById(R.id.textSectionTitle)
        private val formsRecyclerView: RecyclerView = itemView.findViewById(R.id.recyclerViewForms)
        private val buttonAddDynamicForm: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.buttonAddDynamicForm)
        private val formAdapter = FormAdapter(onFormClick, onDeleteDynamicForm, onAddDynamicForm)

        init {
            formsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            formsRecyclerView.adapter = formAdapter
            formsRecyclerView.isNestedScrollingEnabled = false // Disable nested scrolling for better performance
        }

        fun bind(section: String, forms: List<Form>, completedFormIds: Set<String>, draftFormIds: Set<String>, canAddDynamicForm: ((Form) -> Boolean)? = null, baseForms: List<Form> = emptyList()) {
            // Hide section title if section name is empty
            if (section.isEmpty()) {
                sectionTitleText.visibility = android.view.View.GONE
            } else {
                sectionTitleText.visibility = android.view.View.VISIBLE
                sectionTitleText.text = section
            }
            
            // Buttons are now shown in FormAdapter for each dynamic form's last instance
            // Hide the section-level button
            buttonAddDynamicForm.visibility = android.view.View.GONE
            
            // Set all data before notifying the adapter
            // Order matters: set baseFormsList first, then submitList, then status sets
            formAdapter.setBaseFormsList(baseForms)
            formAdapter.setCanAddDynamicForm(canAddDynamicForm)
            formAdapter.submitList(forms)
            // After submitList, the adapter will be notified automatically via DiffUtil
            // Then we update the status sets which will trigger another notification
            formAdapter.setCompletedFormIds(completedFormIds)
            formAdapter.setDraftFormIds(draftFormIds)
            
            // Store section name for debugging
            this.sectionName = section
        }
        
        private var sectionName: String = ""
        
        fun addDynamicFormInstance(baseForm: Form, subIndex: Int) {
            AppLogger.d("FormSectionAdapter", "addDynamicFormInstance called: section=$sectionName, formId=${baseForm.id}, subIndex=$subIndex")
            
            // Get current list
            val currentList = formAdapter.currentList.toMutableList()
            AppLogger.d("FormSectionAdapter", "Current list size: ${currentList.size}, section=$sectionName")
            if (currentList.isNotEmpty()) {
                AppLogger.d("FormSectionAdapter", "First form in list: ${currentList.first().name}, last: ${currentList.last().name}")
            }
            
            // Find the last instance of this dynamic form
            val lastInstanceIndex = currentList.indexOfLast { 
                it.isDynamic && it.id == baseForm.id && it.name.contains(" #")
            }
            
            AppLogger.d("FormSectionAdapter", "Last instance index: $lastInstanceIndex")
            
            if (lastInstanceIndex >= 0) {
                // Insert the new instance right after the last one
                val newInstanceNumber = subIndex + 1
                val newInstance = baseForm.copy(
                    name = "${baseForm.name} #$newInstanceNumber",
                    isDynamic = true
                )
                AppLogger.d("FormSectionAdapter", "Adding new instance: ${newInstance.name} at position ${lastInstanceIndex + 1}")
                currentList.add(lastInstanceIndex + 1, newInstance)
                
                // Store the old last instance index before submitting
                val oldLastInstanceIndex = lastInstanceIndex
                
                // Submit the new list - DiffUtil will handle rebinding
                formAdapter.submitList(currentList)
                
                // Force rebind of the old last instance so its button gets hidden
                // Since submitList is asynchronous, we post the notification to run after the list is updated
                Handler(Looper.getMainLooper()).post {
                    AppLogger.d("FormSectionAdapter", "Notifying old last instance at position $oldLastInstanceIndex to rebind")
                    // Notify the old last instance and the new one to ensure both are rebound
                    formAdapter.notifyItemRangeChanged(oldLastInstanceIndex, 2)
                }
                
                AppLogger.d("FormSectionAdapter", "List updated, new size: ${formAdapter.currentList.size}")
            } else {
                AppLogger.w("FormSectionAdapter", "Could not find last instance of form ${baseForm.id} in section $sectionName. Current list: ${currentList.map { "${it.name} (${it.id})" }}")
            }
        }
    }
}

