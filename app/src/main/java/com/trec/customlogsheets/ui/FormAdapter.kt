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
import com.trec.customlogsheets.util.AppLogger

class FormAdapter(
    private val onFormClick: (Form) -> Unit,
    private val onDeleteDynamicForm: ((Form, Int) -> Unit)? = null,
    private val onAddDynamicForm: ((Form) -> Unit)? = null
) : ListAdapter<FormListItem, RecyclerView.ViewHolder>(FormListItemDiffCallback()) {
    
    companion object {
        private const val VIEW_TYPE_FORM = 0
        private const val VIEW_TYPE_ADD_BUTTON = 1
    }

    private var _completedFormIds: Set<String> = emptySet()
    private var _draftFormIds: Set<String> = emptySet()
    private var _baseFormsList: List<Form> = emptyList() // Base forms (without dynamic instances) for calculating instanceIndex
    private var canAddDynamicForm: ((Form) -> Boolean)? = null
    
    // Getters for FormSectionAdapter to access these properties
    val completedFormIds: Set<String> get() = _completedFormIds
    val draftFormIds: Set<String> get() = _draftFormIds
    val baseFormsList: List<Form> get() = _baseFormsList

    fun setCompletedFormIds(completedIds: Set<String>) {
        _completedFormIds = completedIds
        notifyDataSetChanged()
    }
    
    fun setDraftFormIds(draftIds: Set<String>) {
        _draftFormIds = draftIds
        notifyDataSetChanged()
    }

    fun setBaseFormsList(baseForms: List<Form>) {
        _baseFormsList = baseForms
        // Don't call notifyDataSetChanged() here - it will be called by submitList() or setCompletedFormIds/setDraftFormIds
    }
    
    fun setCanAddDynamicForm(callback: ((Form) -> Boolean)?) {
        canAddDynamicForm = callback
        notifyDataSetChanged()
    }

    /**
     * Updates the status of a specific form instance without full refresh
     * Note: This only updates the status sets. The caller should call notifyItemChanged/notifyItemRangeChanged
     * to trigger a rebind with the updated status.
     */
    fun updateFormStatus(formKey: String, isCompleted: Boolean, isDraft: Boolean) {
        val currentCompleted = _completedFormIds.toMutableSet()
        val currentDraft = _draftFormIds.toMutableSet()
        
        AppLogger.d("FormAdapter", "updateFormStatus: formKey=$formKey, isCompleted=$isCompleted, isDraft=$isDraft")
        AppLogger.d("FormAdapter", "Before update - Completed: $currentCompleted, Draft: $currentDraft")
        
        if (isCompleted) {
            currentCompleted.add(formKey)
            currentDraft.remove(formKey)
        } else if (isDraft) {
            currentDraft.add(formKey)
            currentCompleted.remove(formKey)
        } else {
            // Neither completed nor draft - remove from both sets (empty state)
            currentCompleted.remove(formKey)
            currentDraft.remove(formKey)
        }
        
        _completedFormIds = currentCompleted
        _draftFormIds = currentDraft
        
        AppLogger.d("FormAdapter", "After update - Completed: $_completedFormIds, Draft: $_draftFormIds")
        // Don't call notifyDataSetChanged() here - let the caller decide what to notify
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is FormListItem.FormItem -> VIEW_TYPE_FORM
            is FormListItem.AddButtonItem -> VIEW_TYPE_ADD_BUTTON
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_FORM -> {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_form, parent, false)
                FormViewHolder(view)
            }
            VIEW_TYPE_ADD_BUTTON -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_add_button, parent, false)
                AddButtonViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is FormListItem.FormItem -> {
                val form = item.form
                (holder as FormViewHolder).bindForm(form)
            }
            is FormListItem.AddButtonItem -> {
                (holder as AddButtonViewHolder).bind(item.baseForm)
            }
        }
    }
    
    private fun FormViewHolder.bindForm(form: Form) {
        
        // Check if this is a dynamic form instance (name contains " #")
        val isDynamicInstance = form.isDynamic && form.name.contains(" #")
        val subIndex = if (isDynamicInstance) {
            Regex("#(\\d+)$").find(form.name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
        } else {
            null
        }
        
        // Find base form position in the base forms list (not expanded list)
        // This must match the logic in setupFormsList() exactly
        val baseFormName = if (isDynamicInstance) {
            form.name.substringBefore(" #")
        } else {
            form.name
        }
        
        // Find the base form in the base forms list
        // This must match the logic in setupFormsList() exactly:
        // baseFormsInSection.indexOfFirst { it.id == form.id && it.name == baseFormName }
        // For regular forms, form.name == baseFormName, so we match by both id and name
        // For dynamic forms, we need to match by id and baseFormName (without the # suffix)
        val baseFormPosition = _baseFormsList.indexOfFirst { 
            it.id == form.id && it.name == baseFormName
        }.takeIf { it >= 0 } ?: 0
        
        // Count how many forms with the same ID appear before this position in base forms list
        // This matches setupFormsList() exactly:
        // for (i in 0 until baseFormPosition) {
        //     if (baseFormsInSection[i].id == form.id) {
        //         instanceIndex++
        //     }
        // }
        var instanceIndex = 0
        for (i in 0 until baseFormPosition) {
            if (_baseFormsList[i].id == form.id) {
                instanceIndex++
            }
        }
        
        // Use composite key: "formId_orderInSection" or "formId_orderInSection_subIndex"
        // This MUST match the key format used in getAllFormStatuses() and setupFormsList()
        val formKey = if (subIndex != null) {
            "${form.id}_${instanceIndex}_${subIndex}"
        } else {
            "${form.id}_${instanceIndex}"
        }
        
        val isCompleted = _completedFormIds.contains(formKey)
        val isDraft = _draftFormIds.contains(formKey)
        
        // Debug logging for all dynamic instances to trace the issue
        if (isDynamicInstance) {
            AppLogger.d("FormAdapter", "Binding form: formKey=$formKey, name=${form.name}, isDraft=$isDraft, isCompleted=$isCompleted")
            AppLogger.d("FormAdapter", "Draft set: ${_draftFormIds}, Completed set: ${_completedFormIds}")
            AppLogger.d("FormAdapter", "Draft set contains $formKey: ${_draftFormIds.contains(formKey)}")
        }
        
        bind(
            form, 
            isCompleted,
            isDraft,
            isDynamicInstance,
            subIndex
        )
    }

    inner class FormViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val formNameText: TextView = itemView.findViewById(R.id.textFormName)
        private val formDescriptionText: TextView = itemView.findViewById(R.id.textFormDescription)
        private val statusIcon: ImageView = itemView.findViewById(R.id.iconStatus)
        private val buttonDeleteDynamic: android.widget.ImageButton = itemView.findViewById(R.id.buttonDeleteDynamic)
        private val cardView: com.google.android.material.card.MaterialCardView = itemView as com.google.android.material.card.MaterialCardView

        fun bind(
            form: Form, 
            isCompleted: Boolean, 
            isDraft: Boolean, 
            isDynamicInstance: Boolean = false, 
            subIndex: Int? = null
        ) {
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
            // Use setCardBackgroundColor for MaterialCardView instead of setBackgroundColor
            if (isCompleted) {
                statusIcon.setImageResource(android.R.drawable.checkbox_on_background)
                cardView.setCardBackgroundColor(0xFFE8F5E9.toInt()) // Light green for completed
            } else {
                if (isDraft) {
                    // Show draft icon (edit icon or similar)
                    statusIcon.setImageResource(android.R.drawable.ic_menu_edit)
                    // Light yellow/orange background for drafts
                    cardView.setCardBackgroundColor(0xFFFFF3E0.toInt()) // Light orange for drafts
                } else {
                    statusIcon.setImageResource(android.R.drawable.checkbox_off_background)
                    if (form.mandatory) {
                        cardView.setCardBackgroundColor(0xFFFFEBEE.toInt()) // Light red for mandatory incomplete
                    } else {
                        cardView.setCardBackgroundColor(0xFFFFFFFF.toInt()) // White for not completed
                    }
                }
            }

            // Show delete button for dynamic instances
            // Only enable if there's something to delete (draft or submitted)
            if (isDynamicInstance && subIndex != null) {
                buttonDeleteDynamic.visibility = View.VISIBLE
                // Disable button if there's no draft or submitted data
                val hasData = isCompleted || isDraft
                buttonDeleteDynamic.isEnabled = hasData
                buttonDeleteDynamic.alpha = if (hasData) 1.0f else 0.5f
                
                buttonDeleteDynamic.setOnClickListener {
                    if (hasData) {
                        onDeleteDynamicForm?.invoke(form, subIndex)
                    }
                }
            } else {
                buttonDeleteDynamic.visibility = View.GONE
            }
            
            // Add button is now handled at section level, not per card
            
            // Set click handler on the card content area (not the button)
            val cardContent = itemView.findViewById<View>(R.id.cardContent)
            cardContent?.setOnClickListener {
                onFormClick(form)
            }
        }
    }

    inner class AddButtonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val button: com.google.android.material.button.MaterialButton = itemView as com.google.android.material.button.MaterialButton
        
        fun bind(baseForm: Form) {
            button.text = baseForm.dynamicButtonName ?: "Add Form"
            
            // Check if we can add (all instances must be saved)
            val canAddFromCallback = canAddDynamicForm?.invoke(baseForm) ?: false
            
            // Check if any instance of this dynamic form group is empty
            val currentList = this@FormAdapter.currentList
            val baseFormName = baseForm.name
            val baseForms = this@FormAdapter.baseFormsList
            val completedFormIds = this@FormAdapter.completedFormIds
            val draftFormIds = this@FormAdapter.draftFormIds
            
            val hasEmptyInstance = currentList.any { item ->
                when (item) {
                    is FormListItem.FormItem -> {
                        val form = item.form
                        if (form.isDynamic && form.id == baseForm.id && form.name.contains(" #")) {
                            val formBaseName = form.name.substringBefore(" #")
                            // Match by base name (without # suffix)
                            if (formBaseName == baseFormName) {
                                val fSubIndex = Regex("#(\\d+)$").find(form.name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
                                if (fSubIndex != null) {
                                    val baseFormPosition = baseForms.indexOfFirst { it.id == form.id && it.name == baseFormName }.takeIf { it >= 0 } ?: 0
                                    var instanceIdx = 0
                                    for (i in 0 until baseFormPosition) {
                                        if (baseForms[i].id == form.id) instanceIdx++
                                    }
                                    val formKey = "${form.id}_${instanceIdx}_${fSubIndex}"
                                    val isEmpty = !completedFormIds.contains(formKey) && !draftFormIds.contains(formKey)
                                    if (isEmpty) {
                                        AppLogger.d("FormAdapter", "Found empty instance: $formKey for form ${form.name}")
                                    }
                                    isEmpty
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                    is FormListItem.AddButtonItem -> false
                }
            }
            
            val canAdd = canAddFromCallback && !hasEmptyInstance
            
            // Log for debugging
            AppLogger.d("FormAdapter", "Add button for ${baseForm.name}: canAddFromCallback=$canAddFromCallback, hasEmptyInstance=$hasEmptyInstance, canAdd=$canAdd")
            AppLogger.d("FormAdapter", "Completed: $completedFormIds, Draft: $draftFormIds")
            
            // Ensure button is disabled if there are empty instances
            button.isEnabled = canAdd
            
            // Visual feedback - make button appear disabled even if enabled state doesn't work
            if (!canAdd) {
                button.alpha = 0.5f
            } else {
                button.alpha = 1.0f
            }
            
            button.setOnClickListener {
                if (canAdd && onAddDynamicForm != null) {
                    onAddDynamicForm.invoke(baseForm)
                }
            }
        }
    }

    class FormListItemDiffCallback : DiffUtil.ItemCallback<FormListItem>() {
        override fun areItemsTheSame(oldItem: FormListItem, newItem: FormListItem): Boolean {
            return when {
                oldItem is FormListItem.FormItem && newItem is FormListItem.FormItem -> {
                    oldItem.form.id == newItem.form.id
                }
                oldItem is FormListItem.AddButtonItem && newItem is FormListItem.AddButtonItem -> {
                    oldItem.baseForm.id == newItem.baseForm.id
                }
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: FormListItem, newItem: FormListItem): Boolean {
            return oldItem == newItem
        }
    }
}

