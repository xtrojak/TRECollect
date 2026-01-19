package com.trec.customlogsheets.data

import android.content.Context

data class Form(
    val id: String,
    val name: String,
    val section: String,
    val description: String? = null,
    val mandatory: Boolean = false
)

object PredefinedForms {
    private var cachedForms: List<Form>? = null
    private var cachedContext: Context? = null
    
    /**
     * Clears the forms cache (should be called when team/subteam changes)
     */
    fun clearCache() {
        cachedForms = null
        cachedContext = null
    }
    
    /**
     * Loads forms from downloaded logsheet configurations
     */
    fun getForms(context: Context): List<Form> {
        // Cache forms per context to avoid reloading
        if (cachedForms != null && cachedContext == context) {
            return cachedForms!!
        }
        
        val configs = FormConfigLoader.load(context)
        // Convert FormConfig to Form
        val forms = configs.map { config ->
            Form(
                id = config.id,
                name = config.name,
                section = config.section,
                description = config.description,
                mandatory = config.mandatory
            )
        }
        
        cachedForms = forms
        cachedContext = context
        return forms
    }
    
    fun getSections(context: Context): List<String> {
        return getForms(context).map { it.section }.distinct()
    }
    
    fun getFormsBySection(context: Context, section: String): List<Form> {
        return getForms(context).filter { it.section == section }
    }
    
    fun getFormConfig(context: Context, formId: String): FormConfig? {
        return FormConfigLoader.load(context).firstOrNull { it.id == formId }
    }
    
    fun getMandatoryForms(context: Context): List<Form> {
        return getForms(context).filter { it.mandatory }
    }
}

