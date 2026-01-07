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
     * Loads forms from JSON configuration
     * Falls back to hardcoded list if config loading fails
     */
    fun getForms(context: Context): List<Form> {
        // Cache forms per context to avoid reloading
        if (cachedForms != null && cachedContext == context) {
            return cachedForms!!
        }
        
        val configs = FormConfigLoader.loadFromAssets(context)
        val forms = if (configs.isNotEmpty()) {
            // Convert FormConfig to Form
            configs.map { config ->
                Form(
                    id = config.id,
                    name = config.name,
                    section = config.section,
                    description = config.description,
                    mandatory = config.mandatory
                )
            }
        } else {
            // Fallback to hardcoded list
            listOf(
                Form("site_info", "Site Information", "Site Information", "Basic site details and location", true),
                Form("site_conditions", "Site Conditions", "Site Information", "Environmental conditions at the site", false),
                Form("sampling_protocol", "Sampling Protocol", "Sampling", "Protocol used for sampling", true),
                Form("sample_collection", "Sample Collection", "Sampling", "Details of collected samples", true),
                Form("sample_preservation", "Sample Preservation", "Sampling", "How samples were preserved", false),
                Form("photographs", "Photographs", "Documentation", "Site and sample photographs", false),
                Form("notes", "Field Notes", "Documentation", "Additional field observations", false),
                Form("qc_checks", "Quality Control Checks", "Quality Control", "QC procedures performed", false),
                Form("chain_of_custody", "Chain of Custody", "Quality Control", "Sample handling documentation", false)
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
        return FormConfigLoader.loadFromAssets(context).firstOrNull { it.id == formId }
    }
    
    fun getMandatoryForms(context: Context): List<Form> {
        return getForms(context).filter { it.mandatory }
    }
}

