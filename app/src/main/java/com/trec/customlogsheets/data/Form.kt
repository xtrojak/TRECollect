package com.trec.customlogsheets.data

import android.content.Context

data class Form(
    val id: String,
    val name: String,
    val section: String,
    val description: String? = null,
    val mandatory: Boolean = false,
    val isDynamic: Boolean = false,
    val dynamicButtonName: String? = null
)

object PredefinedForms {
    private var cachedForms: List<Form>? = null
    private var cachedContext: Context? = null
    private var cachedSiteName: String? = null // Cache for site-specific forms
    
    /**
     * Clears the forms cache (should be called when team/subteam changes)
     */
    fun clearCache() {
        cachedForms = null
        cachedContext = null
        cachedSiteName = null
    }
    
    /**
     * Loads forms from downloaded logsheet configurations
     * For site-specific loading, use getFormsForSite() instead
     */
    fun getForms(context: Context): List<Form> {
        // Cache forms per context to avoid reloading (only if not site-specific)
        if (cachedForms != null && cachedContext == context && cachedSiteName == null) {
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
                mandatory = config.mandatory,
                isDynamic = config.isDynamic,
                dynamicButtonName = config.dynamicButtonName
            )
        }
        
        cachedForms = forms
        cachedContext = context
        cachedSiteName = null
        return forms
    }
    
    /**
     * Loads forms for a specific site, using the team config version from site metadata
     * This ensures the site uses the same config version it was created with
     */
    fun getFormsForSite(context: Context, siteName: String): List<Form> {
        // Check cache first
        if (cachedForms != null && cachedContext == context && cachedSiteName == siteName) {
            return cachedForms!!
        }
        
        // Load configs for this specific site (uses pinned team config version)
        val configs = FormConfigLoader.loadForSite(context, siteName)
        // Convert FormConfig to Form, filtering out horizontal_line dividers
        val forms = configs
            .filter { it.id != "horizontal_line" } // Exclude dividers from Form list
            .map { config ->
                Form(
                    id = config.id,
                    name = config.name,
                    section = config.section,
                    description = config.description,
                    mandatory = config.mandatory,
                    isDynamic = config.isDynamic,
                    dynamicButtonName = config.dynamicButtonName
                )
            }
        
        cachedForms = forms
        cachedContext = context
        cachedSiteName = siteName
        return forms
    }
    
    fun getSectionsForSite(context: Context, siteName: String): List<String> {
        return getFormsForSite(context, siteName).map { it.section }.distinct()
    }
    
    fun getFormsBySection(context: Context, section: String): List<Form> {
        return getForms(context).filter { it.section == section }
    }
    
    fun getFormsBySectionForSite(context: Context, siteName: String, section: String): List<Form> {
        return getFormsForSite(context, siteName).filter { it.section == section }
    }
    
    fun getFormConfig(context: Context, formId: String): FormConfig? {
        return FormConfigLoader.load(context).firstOrNull { it.id == formId }
    }
    
    fun getFormConfigForSite(context: Context, siteName: String, formId: String, orderInSection: Int? = null): FormConfig? {
        val configs = FormConfigLoader.loadForSite(context, siteName)
        if (orderInSection != null) {
            // Find the specific instance by counting occurrences of the same formId
            var instanceCount = 0
            for (config in configs) {
                if (config.id == formId) {
                    if (instanceCount == orderInSection) {
                        return config
                    }
                    instanceCount++
                }
            }
            // If not found by orderInSection, fall back to first match
        }
        return configs.firstOrNull { it.id == formId }
    }
    
}

