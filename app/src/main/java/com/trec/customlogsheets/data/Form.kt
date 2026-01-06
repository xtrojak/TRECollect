package com.trec.customlogsheets.data

data class Form(
    val id: String,
    val name: String,
    val section: String,
    val description: String? = null
)

object PredefinedForms {
    val forms = listOf(
        // Site Information Section
        Form("site_info", "Site Information", "Site Information", "Basic site details and location"),
        Form("site_conditions", "Site Conditions", "Site Information", "Environmental conditions at the site"),
        
        // Sampling Section
        Form("sampling_protocol", "Sampling Protocol", "Sampling", "Protocol used for sampling"),
        Form("sample_collection", "Sample Collection", "Sampling", "Details of collected samples"),
        Form("sample_preservation", "Sample Preservation", "Sampling", "How samples were preserved"),
        
        // Documentation Section
        Form("photographs", "Photographs", "Documentation", "Site and sample photographs"),
        Form("notes", "Field Notes", "Documentation", "Additional field observations"),
        
        // Quality Control Section
        Form("qc_checks", "Quality Control Checks", "Quality Control", "QC procedures performed"),
        Form("chain_of_custody", "Chain of Custody", "Quality Control", "Sample handling documentation")
    )
    
    val sections = forms.map { it.section }.distinct()
    
    fun getFormsBySection(section: String): List<Form> {
        return forms.filter { it.section == section }
    }
}

