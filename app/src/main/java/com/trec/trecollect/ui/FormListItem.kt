package com.trec.trecollect.ui

import com.trec.trecollect.data.Form

/**
 * Represents an item in the form list - either a form, an add button for a dynamic form group, or a divider
 */
sealed class FormListItem {
    data class FormItem(val form: Form) : FormListItem()
    data class AddButtonItem(val baseForm: Form) : FormListItem() // The base form for the dynamic group
    object DividerItem : FormListItem() // Horizontal line divider
}
