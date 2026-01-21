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
) : ListAdapter<Form, FormAdapter.FormViewHolder>(FormDiffCallback()) {

    private var completedFormIds: Set<String> = emptySet()
    private var draftFormIds: Set<String> = emptySet()
    private var baseFormsList: List<Form> = emptyList() // Base forms (without dynamic instances) for calculating instanceIndex
    private var canAddDynamicForm: ((Form) -> Boolean)? = null

    fun setCompletedFormIds(completedIds: Set<String>) {
        completedFormIds = completedIds
        notifyDataSetChanged()
    }
    
    fun setDraftFormIds(draftIds: Set<String>) {
        draftFormIds = draftIds
        notifyDataSetChanged()
    }
    
    fun setBaseFormsList(baseForms: List<Form>) {
        baseFormsList = baseForms
        // Don't call notifyDataSetChanged() here - it will be called by submitList() or setCompletedFormIds/setDraftFormIds
    }
    
    fun setCanAddDynamicForm(callback: ((Form) -> Boolean)?) {
        canAddDynamicForm = callback
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FormViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_form, parent, false)
        return FormViewHolder(view)
    }

    override fun onBindViewHolder(holder: FormViewHolder, position: Int) {
        val form = getItem(position)
        
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
        val baseFormPosition = baseFormsList.indexOfFirst { 
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
            if (baseFormsList[i].id == form.id) {
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
        
        // OPTIMIZATION: Check if this is the last instance by only checking the next item (O(1) instead of O(n))
        // This is the last instance if the next item is not part of the same dynamic form group
        val isLastInstance = if (isDynamicInstance) {
            val nextPosition = position + 1
            if (nextPosition < itemCount) {
                val nextForm = getItem(nextPosition)
                // If next form is not an instance of the same dynamic form group, this is the last instance
                val nextIsSameGroup = nextForm.isDynamic && 
                    nextForm.id == form.id && 
                    nextForm.name.contains(" #") &&
                    nextForm.name.substringBefore(" #") == baseFormName
                !nextIsSameGroup
            } else {
                true // Last item in list
            }
        } else {
            false
        }
        
        // Get base form for the add button - use baseFormName we already calculated
        val baseForm = if (isDynamicInstance) {
            // Try to find by id and name first
            val found = baseFormsList.firstOrNull { it.id == form.id && it.name == baseFormName }
                // Fallback: find by id and section if name doesn't match
                ?: baseFormsList.firstOrNull { it.id == form.id && it.section == form.section && it.isDynamic }
            AppLogger.d("FormAdapter", "Looking for baseForm: formId=${form.id}, baseFormName=$baseFormName, found=${found?.id}, baseFormsList size=${baseFormsList.size}")
            found
        } else {
            null
        }
        
        holder.bind(
            form, 
            completedFormIds.contains(formKey),
            draftFormIds.contains(formKey),
            isDynamicInstance,
            subIndex,
            isLastInstance,
            baseForm,
            onAddDynamicForm
        )
    }

    inner class FormViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val formNameText: TextView = itemView.findViewById(R.id.textFormName)
        private val formDescriptionText: TextView = itemView.findViewById(R.id.textFormDescription)
        private val statusIcon: ImageView = itemView.findViewById(R.id.iconStatus)
        private val buttonDeleteDynamic: android.widget.ImageButton = itemView.findViewById(R.id.buttonDeleteDynamic)
        private val buttonAddDynamicForm: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.buttonAddDynamicForm)

        fun bind(
            form: Form, 
            isCompleted: Boolean, 
            isDraft: Boolean, 
            isDynamicInstance: Boolean = false, 
            subIndex: Int? = null,
            isLastInstance: Boolean = false,
            baseForm: Form? = null,
            onAddDynamicForm: ((Form) -> Unit)? = null
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

            // Show delete button for dynamic instances
            if (isDynamicInstance && subIndex != null) {
                buttonDeleteDynamic.visibility = View.VISIBLE
                buttonDeleteDynamic.setOnClickListener {
                    onDeleteDynamicForm?.invoke(form, subIndex)
                }
            } else {
                buttonDeleteDynamic.visibility = View.GONE
            }
            
            // Show add button for the last instance of each dynamic form
            if (isLastInstance && baseForm != null && baseForm.isDynamic && baseForm.dynamicButtonName != null) {
                buttonAddDynamicForm.visibility = View.VISIBLE
                buttonAddDynamicForm.text = baseForm.dynamicButtonName
                
                // Enable/disable based on whether all instances are saved AND the latest instance is saved
                // Check if the current form (latest instance) is saved
                val currentFormSubIndex = subIndex
                val isCurrentFormSaved = if (currentFormSubIndex != null) {
                    // Check if this instance has a draft or submitted file
                    isCompleted || isDraft
                } else {
                    true // Not a dynamic instance, shouldn't happen here
                }
                
                // Also check if all previous instances are saved
                val canAddAllSaved = canAddDynamicForm?.invoke(baseForm) ?: true
                
                // Can add only if all instances are saved AND the current (latest) instance is saved
                val canAdd = canAddAllSaved && isCurrentFormSaved
                
                buttonAddDynamicForm.isEnabled = canAdd
                
                // Clear any existing listeners first
                buttonAddDynamicForm.setOnClickListener(null)
                buttonAddDynamicForm.setOnTouchListener(null)
                
                buttonAddDynamicForm.setOnClickListener { v ->
                    // Stop event propagation to prevent card click
                    v?.parent?.requestDisallowInterceptTouchEvent(true)
                    if (canAdd && onAddDynamicForm != null) {
                        onAddDynamicForm.invoke(baseForm)
                    }
                }
            } else {
                buttonAddDynamicForm.visibility = View.GONE
                buttonAddDynamicForm.setOnClickListener(null)
            }
            
            // Set click handler on the card content area (not the button)
            val cardContent = itemView.findViewById<View>(R.id.cardContent)
            cardContent?.setOnClickListener {
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

