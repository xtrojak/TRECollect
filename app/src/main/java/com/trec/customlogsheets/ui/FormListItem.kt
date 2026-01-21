package com.trec.customlogsheets.ui

import com.trec.customlogsheets.data.Form

/**
 * Represents an item in the form list - either a form or an add button for a dynamic form group
 */
sealed class FormListItem {
    data class FormItem(val form: Form) : FormListItem()
    data class AddButtonItem(val baseForm: Form) : FormListItem() // The base form for the dynamic group
}
