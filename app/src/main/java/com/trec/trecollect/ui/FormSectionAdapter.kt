package com.trec.trecollect.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trec.trecollect.R
import com.trec.trecollect.data.Form
import com.trec.trecollect.util.AppLogger

class FormSectionAdapter(
    private val onFormClick: (Form) -> Unit,
    private val onAddDynamicForm: ((Form) -> Unit)? = null,
    private val onDeleteDynamicForm: ((Form, Int) -> Unit)? = null
) : RecyclerView.Adapter<FormSectionAdapter.SectionViewHolder>() {

    private var sections: List<String> = emptyList()
    private var formsBySection: Map<String, List<FormListItem>> = emptyMap()
    private var completedFormIds: Set<String> = emptySet()
    private var draftFormIds: Set<String> = emptySet()
    private var canAddDynamicForm: ((Form) -> Boolean)? = null
    
    val sectionsList: List<String>
        get() = sections
    
    val currentFormsBySection: Map<String, List<FormListItem>>
        get() = formsBySection


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_form_section, parent, false)
        return SectionViewHolder(view, onFormClick, onAddDynamicForm, onDeleteDynamicForm)
    }

    private var baseFormsBySection: Map<String, List<Form>> = emptyMap()
    
    fun setData(sections: List<String>, formsBySection: Map<String, List<FormListItem>>, completedFormIds: Set<String>, draftFormIds: Set<String> = emptySet(), canAddDynamicForm: ((Form) -> Boolean)? = null, baseFormsBySection: Map<String, List<Form>> = emptyMap()) {
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
        val formListItems = formsBySection[section] ?: emptyList()
        val baseForms = baseFormsBySection[section] ?: emptyList()
        holder.bind(section, formListItems, completedFormIds, draftFormIds, canAddDynamicForm, baseForms)
    }

    override fun getItemCount(): Int = sections.size

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
        private var currentCanAddCallback: ((Form) -> Boolean)? = null
        private var currentLastDynamicForm: Form? = null

        init {
            formsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            formsRecyclerView.adapter = formAdapter
            formsRecyclerView.isNestedScrollingEnabled = false // Disable nested scrolling for better performance
        }

        fun bind(section: String, formListItems: List<FormListItem>, completedFormIds: Set<String>, draftFormIds: Set<String>, canAddDynamicForm: ((Form) -> Boolean)? = null, baseForms: List<Form> = emptyList()) {
            // Hide section title if section name is empty
            if (section.isEmpty()) {
                sectionTitleText.visibility = android.view.View.GONE
            } else {
                sectionTitleText.visibility = android.view.View.VISIBLE
            sectionTitleText.text = section
            }
            
            // Set all data before notifying the adapter
            // Order matters: set baseFormsList first, then submitList, then status sets
            formAdapter.setBaseFormsList(baseForms)
            formAdapter.setCanAddDynamicForm(canAddDynamicForm)
            formAdapter.submitList(formListItems)
            // After submitList, the adapter will be notified automatically via DiffUtil
            // Then we update the status sets which will trigger another notification
            formAdapter.setCompletedFormIds(completedFormIds)
            formAdapter.setDraftFormIds(draftFormIds)
            
            // Store the callback for later updates
            currentCanAddCallback = canAddDynamicForm
            
            // Hide section-level add button - buttons are now in the list as FormListItem.AddButtonItem
            buttonAddDynamicForm.visibility = android.view.View.GONE
            buttonAddDynamicForm.setOnClickListener(null)
            currentLastDynamicForm = null
            
            // Store section name for debugging
            this.sectionName = section

            // Update cache of max instance number per dynamic form
            cachedMaxInstanceByFormId.clear()
            for (item in formListItems) {
                if (item is FormListItem.FormItem && item.form.isDynamic && item.form.name.contains(" #")) {
                    val n = Regex("#(\\d+)$").find(item.form.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    val id = item.form.id
                    cachedMaxInstanceByFormId[id] = maxOf(cachedMaxInstanceByFormId[id] ?: 0, n)
                }
            }
        }
        
        /**
         * Updates the status of a specific form instance (for draft deletion without full refresh)
         * The card remains visible but becomes empty
         */
        fun updateFormStatus(form: Form, subIndex: Int, instanceIndex: Int, isCompleted: Boolean, isDraft: Boolean) {
            AppLogger.d("FormSectionAdapter", "updateFormStatus called: form=${form.id}, subIndex=$subIndex, instanceIndex=$instanceIndex, isCompleted=$isCompleted, isDraft=$isDraft")
            
            // Calculate the form key for the specific instance being updated
            val formKey = "${form.id}_${instanceIndex}_${subIndex}"
            AppLogger.d("FormSectionAdapter", "Form key: $formKey")
            
            // Update status in adapter - this updates the status sets
            // IMPORTANT: Do this FIRST before checking other instances
            when {
                isCompleted -> formAdapter.changeStatusToCompleted(formKey)
                isDraft -> formAdapter.changeStatusToDraft(formKey)
                else -> formAdapter.clearFormStatus(formKey)
            }
            
            // Find and notify all items in this dynamic group to rebind
            // Each item will recalculate its own form key and check the updated status sets
            val currentList = formAdapter.currentList
            val baseFormName = form.name.substringBefore(" #")
            
            // Find all instances of this dynamic form (only FormItem, skip AddButtonItem)
            val startIndex = currentList.indexOfFirst { item ->
                item is FormListItem.FormItem && item.form.isDynamic && item.form.id == form.id && item.form.name.contains(" #")
            }
            val endIndex = currentList.indexOfLast { item ->
                item is FormListItem.FormItem && item.form.isDynamic && item.form.id == form.id && item.form.name.contains(" #")
            }
            
            AppLogger.d("FormSectionAdapter", "Found dynamic form instances: startIndex=$startIndex, endIndex=$endIndex, total items=${currentList.size}")
            
            // We loop over all instances of this dynamic form for two reasons. (1) Status is keyed by
            // formKey; after updating one instance, other instances might still be wrongly in completed/draft sets
            // (e.g. stale or shared state), so we fix any empty instance incorrectly marked as draft. (2) We
            // rebind the whole range so that delete/add button states and card appearance stay in sync across
            // the group. A single notifyItemChanged for the updated index would not refresh sibling instances.
            if (startIndex in 0..endIndex) {
                val baseForms = formAdapter.baseFormsList
                
                // Get fresh status sets after the update (already done above)
                val currentCompleted = formAdapter.completedFormIds
                val currentDraft = formAdapter.draftFormIds
                
                AppLogger.d("FormSectionAdapter", "After status update - Completed: $currentCompleted, Draft: $currentDraft")
                
                // Check all instances and ensure empty ones are not marked as draft
                for (i in startIndex..endIndex) {
                    val item = currentList[i]
                    if (item !is FormListItem.FormItem) continue
                    val instanceForm = item.form
                    if (instanceForm.isDynamic && instanceForm.id == form.id && instanceForm.name.contains(" #")) {
                        val instanceFormKey = formKeyForDynamicInstance(instanceForm, baseForms)
                        if (instanceFormKey != null) {
                            // Check if this instance is in the status sets
                            val isInCompleted = currentCompleted.contains(instanceFormKey)
                            val isInDraft = currentDraft.contains(instanceFormKey)
                            
                            AppLogger.d("FormSectionAdapter", "Checking instance at index $i: formKey=$instanceFormKey, isInCompleted=$isInCompleted, isInDraft=$isInDraft, name=${instanceForm.name}")
                            
                            // If this instance is not in completed or draft sets, it's empty - explicitly clear it from draft set
                            if (!isInCompleted && !isInDraft) {
                                // This is an empty instance - explicitly ensure it's not in the draft set
                                if (currentDraft.contains(instanceFormKey)) {
                                    AppLogger.w("FormSectionAdapter", "Found empty instance incorrectly marked as draft: $instanceFormKey, removing from draft set")
                                    formAdapter.clearFormStatus(instanceFormKey)
                                } else {
                                    AppLogger.d("FormSectionAdapter", "Empty instance $instanceFormKey is correctly not in draft set")
                                }
                            } else if (isInDraft && !isInCompleted) {
                                // This instance is marked as draft - verify it should be
                                AppLogger.d("FormSectionAdapter", "Instance $instanceFormKey is correctly marked as draft")
                            }
                        }
                    }
                }
                
                // Log all form keys after cleanup for debugging
                AppLogger.d("FormSectionAdapter", "Status sets after update - Completed: ${formAdapter.completedFormIds}, Draft: ${formAdapter.draftFormIds}")
                
                // Use Handler.post to ensure status sets are fully updated before rebinding
                // This prevents empty cards from showing as drafts due to timing issues
                Handler(Looper.getMainLooper()).post {
                    // Notify all items in this dynamic group to rebind (to update button states, delete button states, etc.)
                    val count = endIndex - startIndex + 1
                    AppLogger.d("FormSectionAdapter", "Notifying $count items to rebind (from $startIndex to $endIndex)")
                    formAdapter.notifyItemRangeChanged(startIndex, count)
                    
                    // Also notify the add button for this dynamic form group if it exists
                    // Find the add button that comes after this dynamic group
                    val adapterList = formAdapter.currentList
                    if (endIndex + 1 < adapterList.size) {
                        val nextItem = adapterList[endIndex + 1]
                        if (nextItem is FormListItem.AddButtonItem && nextItem.baseForm.id == form.id) {
                            AppLogger.d("FormSectionAdapter", "Notifying add button at index ${endIndex + 1} to update enabled state")
                            formAdapter.notifyItemChanged(endIndex + 1)
                        }
                    }
                }
            } else {
                AppLogger.w("FormSectionAdapter", "Could not find dynamic form instances to update")
            }
        }
        
        /**
         * Updates the add button state for a dynamic form group
         * Also checks if any instance in the current list is empty
         */
        fun updateAddButtonState(baseForm: Form, canAddDynamicForm: ((Form) -> Boolean)?) {
            // Update stored callback
            currentCanAddCallback = canAddDynamicForm
            currentLastDynamicForm = baseForm
            
            val canAddFromCallback = canAddDynamicForm?.invoke(baseForm) ?: false
            
            // Check if any instance of this dynamic form group in the current list is empty
            val currentList = formAdapter.currentList
            val completedFormIds = formAdapter.completedFormIds
            val draftFormIds = formAdapter.draftFormIds
            val baseForms = formAdapter.baseFormsList
            
            val hasEmptyInstance = currentList.any { item ->
                if (item !is FormListItem.FormItem) return@any false
                val form = item.form
                if (form.isDynamic && form.id == baseForm.id && form.name.contains(" #")) {
                    val formKey = formKeyForDynamicInstance(form, baseForms)
                    formKey != null && !completedFormIds.contains(formKey) && !draftFormIds.contains(formKey)
                } else {
                    false
                }
            }
            
            val canAdd = canAddFromCallback && !hasEmptyInstance
            buttonAddDynamicForm.isEnabled = canAdd
            
            // Update button text if needed
            if (baseForm.dynamicButtonName != null) {
                buttonAddDynamicForm.text = baseForm.dynamicButtonName
            }
        }
        
        private var sectionName: String = ""

        /** Cached max instance number per base form id. Avoids recalculating from list every time. */
        private val cachedMaxInstanceByFormId: MutableMap<String, Int> = mutableMapOf()

        /**
         * Finds the list index of a dynamic form instance by form id and instance name.
         * @return Index or -1 if not found.
         */
        private fun indexOfDynamicInstance(currentList: List<FormListItem>, formId: String, instanceName: String): Int =
            currentList.indexOfFirst { item ->
                item is FormListItem.FormItem && item.form.isDynamic && item.form.id == formId && item.form.name == instanceName
            }

        /**
         * Renumbers remaining dynamic form instances in place from fromIndex.
         * Used after removing an instance so displayed names stay 1, 2, 3, ...
         * @param startNumber First number to use for the instance at fromIndex.
         */
        private fun renumberDynamicInstancesFrom(currentList: MutableList<FormListItem>, baseFormName: String, formId: String, fromIndex: Int, startNumber: Int) {
            var newInstanceNumber = startNumber
            for (i in fromIndex until currentList.size) {
                val item = currentList[i]
                if (item is FormListItem.FormItem && item.form.isDynamic && item.form.id == formId && item.form.name.contains(" #")) {
                    currentList[i] = FormListItem.FormItem(
                        item.form.copy(name = "$baseFormName #$newInstanceNumber", isDynamic = true)
                    )
                    newInstanceNumber++
                } else if (item is FormListItem.FormItem && item.form.id != formId) break
                else if (item is FormListItem.AddButtonItem && item.baseForm.id != formId) break
            }
        }

        /**
         * Finds the last dynamic form instance index and the add-button index after it.
         * @return Pair(lastInstanceIndex, addButtonIndex); addButtonIndex is -1 if not found.
         */
        private fun findLastInstanceAndAddButtonIndex(currentList: List<FormListItem>, baseForm: Form): Pair<Int, Int> {
            val lastInstanceIndex = currentList.indexOfLast { item ->
                item is FormListItem.FormItem && item.form.isDynamic && item.form.id == baseForm.id && item.form.name.contains(" #")
            }
            val addButtonIndex = if (lastInstanceIndex >= 0 && lastInstanceIndex < currentList.size - 1) {
                val nextItem = currentList[lastInstanceIndex + 1]
                if (nextItem is FormListItem.AddButtonItem && nextItem.baseForm.id == baseForm.id) lastInstanceIndex + 1 else -1
            } else -1
            return Pair(lastInstanceIndex, addButtonIndex)
        }

        /**
         * Computes the formKey for a dynamic form instance.
         * Used for status lookups; repeated pattern extracted into this helper.
         */
        private fun formKeyForDynamicInstance(form: Form, baseForms: List<Form>): String? {
            val subIndex = Regex("#(\\d+)$").find(form.name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: return null
            val baseFormName = form.name.substringBefore(" #")
            val baseFormPosition = baseForms.indexOfFirst { it.id == form.id && it.name == baseFormName }.takeIf { it >= 0 } ?: 0
            var instanceIndex = 0
            for (j in 0 until baseFormPosition) {
                if (baseForms[j].id == form.id) instanceIndex++
            }
            return "${form.id}_${instanceIndex}_$subIndex"
        }

        fun addDynamicFormInstance(baseForm: Form, subIndex: Int) {
            // Get current list
            val currentList = formAdapter.currentList.toMutableList()
            val (lastInstanceIndex, addButtonIndex) = findLastInstanceAndAddButtonIndex(currentList, baseForm)

            if (lastInstanceIndex >= 0) {
                // Next instance number: use cached max when available, else compute from list
                val maxNumber = cachedMaxInstanceByFormId[baseForm.id] ?: run {
                    currentList.filterIsInstance<FormListItem.FormItem>()
                        .filter { it.form.isDynamic && it.form.id == baseForm.id && it.form.name.contains(" #") }
                        .mapNotNull { Regex("#(\\d+)$").find(it.form.name)?.groupValues?.get(1)?.toIntOrNull() }
                        .maxOrNull() ?: 0
                }
                // maxNumber is at most subIndex+1 in practice; next number is simply maxNumber+1
                val newInstanceNumber = maxNumber + 1
                
                val newInstance = baseForm.copy(
                    name = "${baseForm.name} #$newInstanceNumber",
                    isDynamic = true
                )
                // Insert before the add button if it exists, otherwise after the last instance
                val insertIndex = if (addButtonIndex >= 0) addButtonIndex else lastInstanceIndex + 1
                currentList.add(insertIndex, FormListItem.FormItem(newInstance))
                
                // Store the old last instance index before submitting
                val oldLastInstanceIndex = lastInstanceIndex
                
                // Submit the new list - DiffUtil will handle rebinding
                cachedMaxInstanceByFormId[baseForm.id] = newInstanceNumber
                formAdapter.submitList(currentList) {
                    // This callback runs after DiffUtil has finished dispatching updates.
                    // Notify the old last instance and the new one to ensure both are rebound
                    val notifyStart = if (addButtonIndex in 0 until oldLastInstanceIndex) {
                        addButtonIndex
                    } else {
                        oldLastInstanceIndex
                    }
                    val notifyCount = if (addButtonIndex >= 0) {
                        // Include the button in the range if it exists
                        if (addButtonIndex < oldLastInstanceIndex) {
                            (oldLastInstanceIndex + 1) - addButtonIndex + 1 // +1 for new instance
                        } else {
                            (addButtonIndex + 1) - oldLastInstanceIndex + 1 // +1 for new instance
                        }
                    } else {
                        2 // old last instance + new instance
                    }
                    formAdapter.notifyItemRangeChanged(notifyStart, notifyCount)
                    
                    // Also explicitly notify the add button if it exists to update its enabled state
                    if (addButtonIndex >= 0) {
                        formAdapter.notifyItemChanged(addButtonIndex)
                    }
                }
            } else {
                AppLogger.w("FormSectionAdapter", "Could not find last instance of form ${baseForm.id} in section $sectionName. Current list: ${currentList.map { when(it) { is FormListItem.FormItem -> "${it.form.name} (${it.form.id})"; is FormListItem.AddButtonItem -> "AddButton(${it.baseForm.id})"; is FormListItem.DividerItem -> "Divider" } }}")
            }
        }
        
        fun deleteDynamicFormInstance(form: Form, subIndex: Int) {
            val currentList = formAdapter.currentList.toMutableList()
            val instanceNumber = subIndex + 1
            val baseFormName = form.name.substringBefore(" #")
            val instanceName = "$baseFormName #$instanceNumber"
            val indexToDelete = indexOfDynamicInstance(currentList, form.id, instanceName)

            if (indexToDelete >= 0) {
                currentList.removeAt(indexToDelete)
                renumberDynamicInstancesFrom(currentList, baseFormName, form.id, indexToDelete, instanceNumber)

                // Submit the new list - DiffUtil will handle rebinding
                cachedMaxInstanceByFormId.remove(form.id)
                formAdapter.submitList(currentList) {
                    // After deletion, we need to rebind the new last instance (if any)
                    // Find the new last instance of this dynamic form group
                    val newLastInstanceIndex = currentList.indexOfLast { item ->
                        item is FormListItem.FormItem && item.form.isDynamic && item.form.id == form.id && item.form.name.contains(" #")
                    }
                    
                    if (newLastInstanceIndex >= 0) {
                        // Notify the new last instance to rebind so it shows the add button
                        formAdapter.notifyItemChanged(newLastInstanceIndex)
                    }
                    
                    // Also notify all renumbered instances to update their display
                    val renumberedStart = indexToDelete
                    val renumberedEnd = if (newLastInstanceIndex >= 0) {
                        newLastInstanceIndex
                    } else {
                        // Find where this dynamic form group ends
                        var endIndex = indexToDelete
                        for (idx in indexToDelete until currentList.size) {
                            val item = currentList[idx]
                            if (item is FormListItem.FormItem && item.form.isDynamic && item.form.id == form.id && item.form.name.contains(" #")) {
                                endIndex = idx
                            } else {
                                break
                            }
                        }
                        endIndex
                    }
                    
                    if (renumberedEnd >= renumberedStart) {
                        formAdapter.notifyItemRangeChanged(renumberedStart, renumberedEnd - renumberedStart + 1)
                    }
                }
            } else {
                AppLogger.w("FormSectionAdapter", "Could not find instance to delete: $instanceName in section $sectionName")
            }
        }
    }
}

