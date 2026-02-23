package com.trec.trecollect.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isEmpty
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Tasks
import com.trec.trecollect.R
import com.trec.trecollect.data.*
import com.trec.trecollect.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FormEditActivity : AppCompatActivity() {
    private lateinit var siteName: String
    private lateinit var formId: String
    private lateinit var formConfig: FormConfig
    private lateinit var formFileHelper: FormFileHelper
    private var isReadOnly: Boolean = false
    private var orderInSection: Int = 0 // 0-based index of this form instance in its section
    private var subIndex: Int? = null // 0-based sub-index for dynamic forms (null for non-dynamic forms)
    
    private lateinit var containerFields: LinearLayout
    private lateinit var textFormName: TextView
    private lateinit var textFormDescription: TextView
    private lateinit var buttonSaveDraft: MaterialButton
    private lateinit var buttonSubmit: MaterialButton
    
    private val fieldViews = mutableMapOf<String, View>()
    private val fieldValues = mutableMapOf<String, FormFieldValue>()
    private val initialFieldValues = mutableMapOf<String, FormFieldValue>() // Track initial state
    private var currentPhotoFieldId: String? = null
    private var photoFile: File? = null
    private var currentBarcodeFieldId: String? = null
    private var gpsPickerLaunched: Boolean = false
    private val barcodeScanner = BarcodeScanning.getClient()
    private var isFormSaved = false // Track if form was just saved
    private val manuallyEditedCalculatedFields = mutableSetOf<String>() // Track manually edited calculated fields
    
    /** Debounce Tab/Enter from barcode scanners: some devices deliver the key twice, causing two focus moves. */
    private var lastFocusTraversalKeyTimeNs = 0L
    
    /** Undo rapid double focus move (one key triggering two moves on some devices). */
    private var lastFocusChangeTimeNs = 0L
    private var lastFocusedViewInForm: View? = null
    private var isRevertingFocus = false
    
    // Helper to mark form as changed
    private fun markFormChanged() {
        isFormSaved = false
    }
    
    /**
     * Checks if a field is a calculated target field (used as target in logic rules)
     */
    private fun isCalculatedField(fieldId: String): Boolean {
        val isCalculated = formConfig.logic.any { it.targetId == fieldId }
        if (isCalculated) {
            android.util.Log.d("FormEditActivity", "Field '$fieldId' is identified as calculated")
        }
        return isCalculated
    }
    
    /**
     * Shows a confirmation dialog when user tries to edit a calculated field
     */
    private fun showCalculatedFieldEditWarning(fieldId: String, fieldLabel: String, onConfirm: () -> Unit) {
        val fieldView = fieldViews[fieldId]
        val editText = fieldView?.findViewById<TextInputEditText>(R.id.editText)
        
        AlertDialog.Builder(this)
            .setTitle("Calculated Field")
            .setMessage("The field '$fieldLabel' is automatically calculated from other fields. If you edit it manually, your changes will be overwritten when the source fields change.\n\nDo you want to continue editing?")
            .setPositiveButton("Edit Anyway") { _, _ ->
                manuallyEditedCalculatedFields.add(fieldId)
                onConfirm()
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Remove focus if user cancels
                editText?.clearFocus()
            }
            .setOnCancelListener {
                // Also handle if dialog is dismissed (e.g., back button)
                editText?.clearFocus()
            }
            .show()
    }
    
    /**
     * Applies logic rules when a field changes
     * @param changedFieldId The ID of the field that changed (base field ID for logic rules)
     * @param instancePrefix Optional prefix for dynamic field instances (e.g., "dynamicFieldId_instance0_")
     */
    private fun applyLogicRules(changedFieldId: String, instancePrefix: String? = null) {
        // Find all logic rules where the changed field is a source
        val affectedRules = formConfig.logic.filter { rule ->
            rule.sourceIds.contains(changedFieldId)
        }
        
        if (affectedRules.isNotEmpty()) {
            android.util.Log.d("FormEditActivity", "Field '$changedFieldId' changed, affecting ${affectedRules.size} logic rule(s)")
        }
        
        for (rule in affectedRules) {
            android.util.Log.d("FormEditActivity", "Applying logic rule: ${rule.type} -> target '${rule.targetId}'")
            when (rule.type) {
                LogicRule.LogicType.SUM -> {
                    // Calculate sum of all source fields
                    var sum = 0.0
                    for (sourceId in rule.sourceIds) {
                        // Construct the field ID (with instance prefix if this is a dynamic field)
                        val sourceFieldId = if (instancePrefix != null) {
                            "${instancePrefix}${sourceId}"
                        } else {
                            sourceId
                        }
                        
                        val sourceValue = fieldValues[sourceFieldId]?.value ?: ""
                        val numericValue = sourceValue.trim().toDoubleOrNull() ?: 0.0
                        sum += numericValue
                    }
                    
                    // Update target field
                    val targetFieldId = if (instancePrefix != null) {
                        "${instancePrefix}${rule.targetId}"
                    } else {
                        rule.targetId
                    }
                    
                    val targetFieldView = fieldViews[targetFieldId]
                    if (targetFieldView != null) {
                        val targetFieldConfig = formConfig.fields.firstOrNull { it.id == rule.targetId }
                            ?: formConfig.fields.firstOrNull { it.type == FormFieldConfig.FieldType.DYNAMIC }?.subFields?.firstOrNull { it.id == rule.targetId }
                        
                        if (targetFieldConfig != null) {
                            // Format the sum (remove decimal if it's a whole number)
                            val sumString = if (sum % 1.0 == 0.0) {
                                sum.toInt().toString()
                            } else {
                                sum.toString()
                            }
                            
                            // Check if this field was manually edited
                            val editText = targetFieldView.findViewById<TextInputEditText>(R.id.editText)
                            val currentValue = editText?.text?.toString()?.trim() ?: ""
                            val wasManuallyEdited = manuallyEditedCalculatedFields.contains(rule.targetId)
                            
                            // Only overwrite if:
                            // 1. The field was NOT manually edited, OR
                            // 2. The calculated value differs from the current value (meaning source fields changed)
                            if (!wasManuallyEdited || currentValue != sumString) {
                                // Update the field with calculated value
                                editText?.setText(sumString)
                                
                                // Remove from manually edited set since we're overwriting with calculated value
                                manuallyEditedCalculatedFields.remove(rule.targetId)
                                
                                // Update fieldValues
                                fieldValues[targetFieldId] = FormFieldValue(targetFieldId, value = sumString)
                                markFormChanged()
                                
                                android.util.Log.d("FormEditActivity", "Updated calculated field '${rule.targetId}' to value '$sumString'")
                                
                                // If this calculated field is itself a source for other calculated fields,
                                // recursively apply logic rules to update dependent fields
                                applyLogicRules(rule.targetId, instancePrefix)
                            } else {
                                // Field was manually edited and calculated value hasn't changed, preserve manual value
                                android.util.Log.d("FormEditActivity", "Preserving manual value for calculated field '${rule.targetId}': '$currentValue'")
                                // Still update fieldValues to ensure consistency
                                fieldValues[targetFieldId] = FormFieldValue(targetFieldId, value = currentValue)
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Activity result launchers
    private val cameraActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isDestroyed || isFinishing) return@registerForActivityResult
        if (result.resultCode == RESULT_OK && photoFile != null) {
            if (currentPhotoFieldId == "temp_barcode_scan" && currentBarcodeFieldId != null) {
                // Process barcode from photo
                processBarcodeFromPhoto(photoFile!!)
            } else if (currentPhotoFieldId != null) {
                // Save to MediaStore (Photos app)
                val photoUri = savePhotoToMediaStore(photoFile!!)
                if (photoUri != null) {
                    val fileName = photoFile!!.name
                    fieldValues[currentPhotoFieldId!!] = FormFieldValue(
                        currentPhotoFieldId!!,
                        photoFileName = fileName
                    )
                    updatePhotoFieldView(currentPhotoFieldId!!, fileName)
                    markFormChanged()
                }
            }
        }
        if (currentPhotoFieldId != "temp_barcode_scan") {
            currentPhotoFieldId = null
        }
        photoFile = null
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isDestroyed || isFinishing) return@registerForActivityResult
        if (isGranted) {
            if (currentPhotoFieldId == "temp_barcode_scan" && currentBarcodeFieldId != null) {
                capturePhotoForBarcode()
            } else if (currentPhotoFieldId != null) {
                capturePhoto(currentPhotoFieldId!!)
            }
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            currentPhotoFieldId = null
            currentBarcodeFieldId = null
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_GPS_PICKER) {
            gpsPickerLaunched = false
            if (resultCode == RESULT_OK) {
                val fieldId = data?.getStringExtra("fieldId")
                val latitude = data?.getDoubleExtra("latitude", 0.0)
                val longitude = data?.getDoubleExtra("longitude", 0.0)
                
                if (fieldId != null && latitude != null && longitude != null && latitude != 0.0 && longitude != 0.0) {
                    fieldValues[fieldId] = FormFieldValue(
                        fieldId,
                        gpsLatitude = latitude,
                        gpsLongitude = longitude
                    )
                    updateGPSFieldView(fieldId, latitude, longitude)
                    markFormChanged()
                }
            }
        }
    }
    
    private fun updateGPSFieldView(fieldId: String, latitude: Double, longitude: Double) {
        val fieldView = fieldViews[fieldId] ?: return
        val editTextLatitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val editTextLongitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLongitude)
        editTextLatitude?.setText(latitude.toString())
        editTextLongitude?.setText(longitude.toString())
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form_edit)
        
        siteName = intent.getStringExtra("siteName") ?: run {
            finish()
            return
        }
        
        formId = intent.getStringExtra("formId") ?: run {
            finish()
            return
        }
        
        // Get the order in section (0-based index of this specific form instance)
        // If not provided, calculate it (for backward compatibility)
        orderInSection = intent.getIntExtra("orderInSection", -1)
        subIndex = intent.getIntExtra("subIndex", -1).takeIf { it >= 0 }
        
        if (orderInSection < 0) {
            // Calculate it from the form list for this specific site
            val forms = PredefinedForms.getFormsForSite(this, siteName)
            val formConfig = forms.firstOrNull { it.id == formId }
            if (formConfig != null) {
                val formsInSection = PredefinedForms.getFormsBySectionForSite(this, siteName, formConfig.section)
                val positionInSection = formsInSection.indexOfFirst { it.id == formId }
                if (positionInSection >= 0) {
                    // Count how many forms with this ID appear before this position
                    var instanceIndex = 0
                    for (i in 0 until positionInSection) {
                        if (formsInSection[i].id == formId) {
                            instanceIndex++
                        }
                    }
                    orderInSection = instanceIndex
                } else {
                    orderInSection = 0
                }
            } else {
                orderInSection = 0
            }
        }
        
        isReadOnly = intent.getBooleanExtra("isReadOnly", false)
        
        formFileHelper = FormFileHelper(this)
        
        // Load form configuration - we'll reload it after loading existing data if it has a version
        // Use orderInSection to get the correct instance (since same formId can appear multiple times with different titles)
        try {
            formConfig = PredefinedForms.getFormConfigForSite(this, siteName, formId, orderInSection) ?: run {
                android.util.Log.e("FormEditActivity", "Form config not found for formId: $formId in site: $siteName")
                android.util.Log.d("FormEditActivity", "Available forms: ${PredefinedForms.getFormsForSite(this, siteName).map { it.id }}")
                Toast.makeText(this, "Form configuration not found for: $formId", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error loading form config: ${e.message}", e)
            Toast.makeText(this, "Error loading form: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        setupToolbar()
        setupViews()
        loadExistingData()
        
        // After loading existing data, reload form config with the version from XML
        existingFormData?.let { data ->
            if (data.logsheetVersion.isNotEmpty()) {
                try {
                    val versionedConfig = FormConfigLoader.loadFormConfigForVersion(this, formId, data.logsheetVersion, siteName, orderInSection)
                    if (versionedConfig != null) {
                        formConfig = versionedConfig
                        android.util.Log.d("FormEditActivity", "Loaded form config version ${data.logsheetVersion} for formId: $formId")
                    } else {
                        android.util.Log.e("FormEditActivity", "Could not load form config version ${data.logsheetVersion} for formId: $formId")
                        Toast.makeText(this, "Error: Could not load form config version ${data.logsheetVersion}", Toast.LENGTH_LONG).show()
                        finish()
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FormEditActivity", "Error loading form config version ${data.logsheetVersion}: ${e.message}", e)
                    Toast.makeText(this, "Error loading form config: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
            } else {
                android.util.Log.e("FormEditActivity", "Existing form data missing logsheetVersion for formId: $formId")
                Toast.makeText(this, "Error: Form data missing version information", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        
        renderFields()
        setupFocusChangeDebounce()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.form_edit_menu, menu)
        return true
    }
    
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Hide clear form option if read-only
        val clearItem = menu.findItem(R.id.action_clear_form)
        clearItem?.isVisible = !isReadOnly
        return super.onPrepareOptionsMenu(menu)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_form -> {
                if (!isReadOnly) {
                    showClearFormConfirmation()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (isReadOnly) {
            supportActionBar?.title = "${formConfig.name} (Read Only)"
        } else {
            supportActionBar?.title = formConfig.name
        }
        toolbar.setNavigationOnClickListener {
            if (isReadOnly) {
                finish() // No need to check for unsaved changes in read-only mode
            } else {
                handleBackPress()
            }
        }
    }
    
    override fun onBackPressed() {
        if (hasUnsavedChanges()) {
            showSaveChangesDialog()
        } else {
            super.onBackPressed()
        }
    }
    
    private fun handleBackPress() {
        if (hasUnsavedChanges()) {
            showSaveChangesDialog()
        } else {
            super.onBackPressed()
        }
    }
    
    private fun hasUnsavedChanges(): Boolean {
        if (isFormSaved) {
            return false
        }
        
        // Compare current values with initial values
        if (fieldValues.size != initialFieldValues.size) {
            return true
        }
        
        for ((key, value) in fieldValues) {
            val initialValue = initialFieldValues[key]
            if (initialValue == null || !valuesEqual(value, initialValue)) {
                return true
            }
        }
        
        // Check for removed values
        for (key in initialFieldValues.keys) {
            if (!fieldValues.containsKey(key)) {
                return true
            }
        }
        
        return false
    }
    
    private fun valuesEqual(v1: FormFieldValue, v2: FormFieldValue): Boolean {
        // Compare tableData
        val tableData1 = v1.tableData ?: emptyMap()
        val tableData2 = v2.tableData ?: emptyMap()
        if (tableData1.size != tableData2.size) {
            return false
        }
        for ((row, rowData1) in tableData1) {
            val rowData2 = tableData2[row] ?: return false
            if (rowData1.size != rowData2.size) {
                return false
            }
            for ((col, value1) in rowData1) {
                val value2 = rowData2[col] ?: return false
                if (value1 != value2) {
                    return false
                }
            }
        }
        
        return v1.value == v2.value &&
               v1.values == v2.values &&
               v1.gpsLatitude == v2.gpsLatitude &&
               v1.gpsLongitude == v2.gpsLongitude &&
               v1.photoFileName == v2.photoFileName
    }
    
    private fun showSaveChangesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Unsaved Changes")
            .setMessage("You have unsaved changes. What would you like to do?")
            .setPositiveButton("Save Draft") { _, _ ->
                saveForm(isDraft = true)
                finish()
            }
            .setNeutralButton("Discard") { _, _ ->
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showClearFormConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear Form")
            .setMessage("Are you sure you want to clear this form? This will delete all saved data (both draft and submitted versions) and cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                clearForm()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun clearForm() {
        lifecycleScope.launch {
            try {
                // There is at most one saved file per form instance (draft or submitted)
                val deleted = withContext(Dispatchers.IO) {
                    formFileHelper.deleteForm(siteName, formId, orderInSection, subIndex)
                }

                AppLogger.d("FormEditActivity", "Clear form result: deleted=$deleted, site=$siteName, form=$formId, orderInSection=$orderInSection, subIndex=$subIndex")

                // Clear all field values
                fieldValues.clear()
                initialFieldValues.clear()

                // Re-render fields to show empty state
                renderFields()

                withContext(Dispatchers.Main) {
                    if (deleted) {
                Toast.makeText(this@FormEditActivity, "Form cleared", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@FormEditActivity, "Form cleared (no files found to delete)", Toast.LENGTH_SHORT).show()
                    }
                }
                isFormSaved = true // Mark as saved since there are no changes now
            } catch (e: Exception) {
                AppLogger.e("FormEditActivity", "Error clearing form: ${e.message}", e)
                withContext(Dispatchers.Main) {
                Toast.makeText(this@FormEditActivity, "Error clearing form: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun setupViews() {
        containerFields = findViewById(R.id.containerFields)
        textFormName = findViewById(R.id.textFormName)
        textFormDescription = findViewById(R.id.textFormDescription)
        buttonSaveDraft = findViewById(R.id.buttonSaveDraft)
        buttonSubmit = findViewById(R.id.buttonSubmit)
        
        textFormName.text = formConfig.name
        if (formConfig.description != null) {
            textFormDescription.text = formConfig.description
            textFormDescription.visibility = View.VISIBLE
        }
        
        // Hide buttons and disable editing if read-only
        if (isReadOnly) {
            buttonSaveDraft.visibility = View.GONE
            buttonSubmit.visibility = View.GONE
        } else {
            buttonSaveDraft.setOnClickListener {
                saveForm(isDraft = true)
            }
            
            buttonSubmit.setOnClickListener {
                if (validateForm()) {
                    showSubmitConfirmation()
                }
            }
        }
    }
    
    /**
     * Debounce Tab and Enter so a second key within 400ms is ignored.
     * On some devices, barcode scanner input is delivered twice (only in this app), causing two focus moves.
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            val isFocusTraversal = event.keyCode == KeyEvent.KEYCODE_TAB ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isFocusTraversal) {
                val now = System.nanoTime()
                val elapsedMs = (now - lastFocusTraversalKeyTimeNs) / 1_000_000
                if (elapsedMs < FOCUS_TRAVERSAL_DEBOUNCE_MS && lastFocusTraversalKeyTimeNs != 0L) {
                    return true
                }
                lastFocusTraversalKeyTimeNs = now
            }
        }
        return super.dispatchKeyEvent(event)
    }
    
    /** Returns true if [view] is [container] or a descendant of [container]. */
    private fun isDescendantOf(container: ViewGroup, view: View?): Boolean {
        var v: View? = view
        while (v != null) {
            if (v == container) return true
            v = v.parent as? View
        }
        return false
    }
    
    private fun setupFocusChangeDebounce() {
        if (isReadOnly) return
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (isRevertingFocus) {
                isRevertingFocus = false
                lastFocusedViewInForm = newFocus
                lastFocusChangeTimeNs = System.nanoTime()
                return@addOnGlobalFocusChangeListener
            }
            if (newFocus == null || oldFocus == null || !::containerFields.isInitialized) return@addOnGlobalFocusChangeListener
            if (!isDescendantOf(containerFields, newFocus)) return@addOnGlobalFocusChangeListener
            val now = System.nanoTime()
            val elapsedMs = (now - lastFocusChangeTimeNs) / 1_000_000
            if (elapsedMs < FOCUS_CHANGE_DEBOUNCE_MS && lastFocusedViewInForm != null) {
                isRevertingFocus = true
                lastFocusedViewInForm?.requestFocus()
                return@addOnGlobalFocusChangeListener
            }
            lastFocusedViewInForm = newFocus
            lastFocusChangeTimeNs = now
        }
    }
    
    private var existingFormData: FormData? = null
    
    private fun loadExistingData() {
        // OPTIMIZATION: Load form data once without filtering, then check if it's draft or submitted
        // This avoids loading and parsing the same file twice
        val loadedData = formFileHelper.loadFormDataAny(siteName, formId, orderInSection, subIndex)
        
        if (loadedData != null) {
            // Prefer draft over submitted if both exist, but for now we just use what we found
            // (In practice, if a form is submitted, there shouldn't be a draft, and vice versa)
            existingFormData = loadedData
        }
        
        if (existingFormData != null) {
            for (fieldValue in existingFormData!!.fieldValues) {
                fieldValues[fieldValue.fieldId] = fieldValue
                // Store initial state for comparison
                initialFieldValues[fieldValue.fieldId] = fieldValue
                
                // If this is a calculated field with a saved value, mark it as manually edited
                // This preserves manually edited calculated fields when the form is reloaded
                if (isCalculatedField(fieldValue.fieldId) && fieldValue.value != null && fieldValue.value.isNotEmpty()) {
                    manuallyEditedCalculatedFields.add(fieldValue.fieldId)
                    android.util.Log.d("FormEditActivity", "Marking calculated field '${fieldValue.fieldId}' as manually edited (has saved value: '${fieldValue.value}')")
                }
            }
        }
    }
    
    private fun renderFields() {
        containerFields.removeAllViews()
        fieldViews.clear()
        
        try {
            for (fieldConfig in formConfig.fields) {
                try {
                    val fieldView = createFieldView(fieldConfig)
                    containerFields.addView(fieldView)
                    
                    // Section headers and image displays are display-only and don't need to be stored in fieldViews
                    if (fieldConfig.type != FormFieldConfig.FieldType.SECTION && fieldConfig.type != FormFieldConfig.FieldType.IMAGE_DISPLAY) {
                        fieldViews[fieldConfig.id] = fieldView
                        
                        // Load existing value if available
                        val existingValue = fieldValues[fieldConfig.id]
                        if (existingValue != null) {
                            setFieldValue(fieldView, fieldConfig, existingValue)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FormEditActivity", "Error creating field ${fieldConfig.id}: ${e.message}", e)
                    // Create a simple error view instead of crashing
                    val errorView = TextView(this).apply {
                        text = getString(R.string.error_loading_field, fieldConfig.label)
                        setTextColor(android.graphics.Color.RED)
                    }
                    containerFields.addView(errorView)
                }
            }
        
            // Apply prefills and default values if this is a new form (not draft or submitted)
            if (existingFormData == null) {
                // First, apply prefills (they take precedence over default values)
                for ((widgetId, prefillValue) in formConfig.prefills) {
                    val fieldConfig = formConfig.fields.firstOrNull { it.id == widgetId }
                    if (fieldConfig != null && fieldConfig.type != FormFieldConfig.FieldType.SECTION && fieldConfig.type != FormFieldConfig.FieldType.IMAGE_DISPLAY) {
                        val fieldView = fieldViews[widgetId]
                        if (fieldView != null && fieldValues[widgetId] == null) {
                            applyPrefillValue(fieldView, fieldConfig, prefillValue)
                        }
                    }
                }
                
                // Then, apply default values (only if no prefill was applied)
                for (fieldConfig in formConfig.fields) {
                    if (fieldConfig.defaultValue != null && fieldConfig.type != FormFieldConfig.FieldType.SECTION && fieldConfig.type != FormFieldConfig.FieldType.IMAGE_DISPLAY) {
                        val fieldView = fieldViews[fieldConfig.id]
                        // Only apply default if no prefill was applied (fieldValues[fieldConfig.id] is still null)
                        if (fieldView != null && fieldValues[fieldConfig.id] == null) {
                            applyDefaultValue(fieldView, fieldConfig)
                        }
                    }
                }
            }
            
            // Apply logic rules after all fields are loaded to calculate initial target values
            applyAllLogicRules()
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error rendering fields: ${e.message}", e)
            Toast.makeText(this, "Error rendering form fields: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Applies all logic rules to calculate target field values
     * Called after fields are rendered and values are loaded
     * Uses a recursive approach to handle fields that depend on other calculated fields
     * Only calculates fields that don't already have saved values (to preserve manually edited calculated fields)
     */
    private fun applyAllLogicRules() {
        // Track which fields have been calculated to avoid infinite loops
        val calculatedFields = mutableSetOf<String>()
        
        // Helper function to recursively calculate a field and its dependencies
        fun calculateField(rule: LogicRule) {
            // Skip if already calculated (prevents infinite loops)
            if (calculatedFields.contains(rule.targetId)) {
                return
            }
            
            // Skip if this field already has a saved value (preserves manually edited calculated fields)
            // Only calculate if the field doesn't have a value yet (new form)
            val existingValue = fieldValues[rule.targetId]?.value
            if (existingValue != null && existingValue.isNotEmpty()) {
                android.util.Log.d("FormEditActivity", "Skipping calculation for '${rule.targetId}' - already has saved value: '$existingValue'")
                return
            }
            
            // First, ensure all source fields are calculated (if they are also targets)
            for (sourceId in rule.sourceIds) {
                val sourceRule = formConfig.logic.firstOrNull { it.targetId == sourceId }
                if (sourceRule != null && !calculatedFields.contains(sourceId)) {
                    calculateField(sourceRule)
                }
            }
            
            when (rule.type) {
                LogicRule.LogicType.SUM -> {
                    // Calculate sum of all source fields
                    var sum = 0.0
                    for (sourceId in rule.sourceIds) {
                        val sourceValue = fieldValues[sourceId]?.value ?: ""
                        val numericValue = sourceValue.trim().toDoubleOrNull() ?: 0.0
                        sum += numericValue
                    }
                    
                    // Update target field
                    val targetFieldView = fieldViews[rule.targetId]
                    if (targetFieldView != null) {
                        val targetFieldConfig = formConfig.fields.firstOrNull { it.id == rule.targetId }
                            ?: formConfig.fields.firstOrNull { it.type == FormFieldConfig.FieldType.DYNAMIC }?.subFields?.firstOrNull { it.id == rule.targetId }
                        if (targetFieldConfig != null) {
                            // Format the sum (remove decimal if it's a whole number)
                            val sumString = if (sum % 1.0 == 0.0) {
                                sum.toInt().toString()
                            } else {
                                sum.toString()
                            }
                            
                            // Set the value in the field
                            val editText = targetFieldView.findViewById<TextInputEditText>(R.id.editText)
                            editText?.setText(sumString)
                            
                            // Update fieldValues
                            fieldValues[rule.targetId] = FormFieldValue(rule.targetId, value = sumString)
                            
                            // Mark as calculated
                            calculatedFields.add(rule.targetId)
                        }
                    }
                }
            }
        }
        
        // Apply all logic rules
        for (rule in formConfig.logic) {
            calculateField(rule)
        }
    }
    
    private fun createFieldView(fieldConfig: FormFieldConfig): View {
        return try {
            when (fieldConfig.type) {
                FormFieldConfig.FieldType.TEXT -> createTextField(fieldConfig)
                FormFieldConfig.FieldType.TEXTAREA -> createTextAreaField(fieldConfig)
                FormFieldConfig.FieldType.DATE -> createDateField(fieldConfig)
                FormFieldConfig.FieldType.TIME -> createTimeField(fieldConfig)
                FormFieldConfig.FieldType.SELECT -> createSelectField(fieldConfig)
                FormFieldConfig.FieldType.MULTISELECT -> createMultiSelectField(fieldConfig)
                FormFieldConfig.FieldType.SELECT_IMAGE -> createSelectImageField(fieldConfig)
                FormFieldConfig.FieldType.CHECKBOX -> createCheckboxField(fieldConfig)
                FormFieldConfig.FieldType.MULTISELECT_IMAGE -> createMultiSelectImageField(fieldConfig)
                FormFieldConfig.FieldType.GPS -> createGPSField(fieldConfig)
                FormFieldConfig.FieldType.PHOTO -> createPhotoField(fieldConfig)
                FormFieldConfig.FieldType.BARCODE -> createBarcodeField(fieldConfig)
                FormFieldConfig.FieldType.SECTION -> createSectionHeader(fieldConfig)
                FormFieldConfig.FieldType.IMAGE_DISPLAY -> createImageDisplayField(fieldConfig)
                FormFieldConfig.FieldType.TABLE -> createTableField(fieldConfig)
                FormFieldConfig.FieldType.DYNAMIC -> createDynamicField(fieldConfig)
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error creating field view for ${fieldConfig.id}: ${e.message}", e)
            // Return a simple error TextView as fallback
            TextView(this).apply {
                text = getString(R.string.error_label, fieldConfig.label)
                setTextColor(android.graphics.Color.RED)
            }
        }
    }
    
    /**
     * Common setup for fields that use [R.layout.field_text_input]: inflate into [parent], set label/hint and [tag].
     * Returns the [TextInputLayout] and its [TextInputEditText] for the caller to add type-specific behaviour.
     * Used by both top-level field creation and ForSubField (Review #78).
     */
    private fun inflateTextInputFieldInto(fieldConfig: FormFieldConfig, parent: ViewGroup, tag: String): Pair<TextInputLayout, TextInputEditText> {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            parent,
            false
        ) as TextInputLayout
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        } else {
            textInputLayout.hint = fieldConfig.label
        }
        textInputLayout.tag = tag
        return Pair(textInputLayout, editText)
    }
    
    /** Top-level variant: inflates into [containerFields] with tag = [fieldConfig].id. */
    private fun inflateTextInputField(fieldConfig: FormFieldConfig): Pair<TextInputLayout, TextInputEditText> =
        inflateTextInputFieldInto(fieldConfig, containerFields, fieldConfig.id)
    
    /**
     * Common setup for checkbox fields: inflate into [parent], set label and [tag].
     * Used by both top-level and ForSubField (Review #78).
     */
    private fun inflateCheckboxFieldInto(fieldConfig: FormFieldConfig, parent: ViewGroup, tag: String): Pair<LinearLayout, CheckBox> {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(R.layout.field_checkbox, parent, false) as LinearLayout
        val checkbox = container.findViewById<CheckBox>(R.id.checkbox)
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        if (fieldConfig.required) {
            textLabel.text = getString(R.string.label_required, fieldConfig.label)
        } else {
            textLabel.text = fieldConfig.label
        }
        container.tag = tag
        return Pair(container, checkbox)
    }
    
    private fun createSectionHeader(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_section_header,
            containerFields,
            false
        ) as LinearLayout
        
        val sectionTitle = container.findViewById<TextView>(R.id.textSectionTitle)
        sectionTitle.text = fieldConfig.label
        
        // Section headers are not interactive and don't need a tag
        container.tag = null
        
        return container
    }
    
    private fun createImageDisplayField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_image_display,
            containerFields,
            false
        ) as LinearLayout
        
        val imageView = container.findViewById<ImageView>(R.id.imageViewDisplay)
        val textDescription = container.findViewById<TextView>(R.id.textImageDescription)
        
        // Load image from downloaded files
        val imagePath = fieldConfig.imagePath
        if (imagePath != null) {
            try {
                val downloader = LogsheetDownloader(this)
                val imageFile = downloader.getImageFile(imagePath)
                
                if (imageFile != null && imageFile.exists()) {
                    // Load from downloaded file
                    val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                    imageView.setImageBitmap(bitmap)
                } else {
                    android.util.Log.w("FormEditActivity", "Image not found: $imagePath")
                    // Set a placeholder or error icon
                    imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            } catch (e: Exception) {
                android.util.Log.e("FormEditActivity", "Error loading image ${imagePath}: ${e.message}", e)
                // Set a placeholder or error icon
                imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        } else {
            // No image path provided, show placeholder
            imageView.setImageResource(android.R.drawable.ic_menu_report_image)
        }
        
        // Set description/title if provided (using label field)
        if (fieldConfig.label.isNotEmpty()) {
            textDescription.text = fieldConfig.label
            textDescription.visibility = View.VISIBLE
        } else {
            textDescription.visibility = View.GONE
        }
        
        // Image displays are not interactive and don't need a tag
        container.tag = null
        
        return container
    }
    
    private fun createTextField(fieldConfig: FormFieldConfig): View {
        val (textInputLayout, editText) = inflateTextInputField(fieldConfig)
        // Don't set hint on EditText - only on TextInputLayout to avoid overlap
        if (fieldConfig.inputType == "number") {
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        
        // Check if this is a calculated field (target of logic rule)
        val isCalculated = isCalculatedField(fieldConfig.id)
        
        // Add visual indication for calculated fields
        if (isCalculated) {
            // Update hint to indicate it's calculated
            val currentHint = textInputLayout.hint?.toString() ?: fieldConfig.label
            textInputLayout.hint = "$currentHint (Calculated)"
            // Set a subtle background color to indicate it's calculated
            textInputLayout.setBackgroundColor(0xFFF5F5F5.toInt()) // Light gray background
        }
        
        // Handle mask if present
        val mask = fieldConfig.mask
        if (mask != null) {
            // Set mask as placeholder
            editText.hint = mask
            // Initialize mask input helper to prevent colon deletion and limit section lengths
            MaskInputHelper(editText, mask)
        }
        
        // Set up focus listener (combines mask handling and calculated field protection)
        editText.setOnFocusChangeListener { _, hasFocus ->
            // Handle mask initialization if present
            if (mask != null && hasFocus) {
                val currentText = editText.text?.toString() ?: ""
                // Only initialize if field is empty or only contains spaces/colons (no digits)
                val hasDigits = currentText.any { it.isDigit() }
                if (!hasDigits && (currentText.isEmpty() || currentText.all { it == ' ' || it == ':' })) {
                    // Show only colons in their positions
                    val colonsOnly = mask.map { char -> if (char == ':') ':' else ' ' }.joinToString("")
                    editText.setText(colonsOnly)
                    // Move cursor to first editable position
                    val firstEditablePos = mask.indexOfFirst { it != ':' }.takeIf { it >= 0 } ?: 0
                    editText.setSelection(firstEditablePos)
                }
                // If field has digits, keep the current value - don't clear it
            }
            
            // Add protection for calculated fields - show warning on focus if not already manually edited
            if (isCalculated && !isReadOnly && hasFocus && !manuallyEditedCalculatedFields.contains(fieldConfig.id)) {
                // Show warning dialog when user tries to edit calculated field
                // Use post to allow focus to complete first
                editText.post {
                    showCalculatedFieldEditWarning(fieldConfig.id, fieldConfig.label) {
                        // User confirmed - allow editing
                        // The field is now editable, focus will remain
                    }
                    // If user cancels, remove focus (handled in dialog's negative button)
                }
            }
        }
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        } else {
            // Update fieldValues as user types
            var previousValue = editText.text?.toString() ?: ""
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    previousValue = s?.toString() ?: ""
                }
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                
                override fun afterTextChanged(s: android.text.Editable?) {
                    val rawValue = s?.toString() ?: ""
                    // For masked fields, check if value has digits (not just spaces/colons)
                    val value = if (mask != null) {
                        // For masked fields, normalize the value (add leading zeros) before saving
                        if (rawValue.any { it.isDigit() }) {
                            // Extract the actual value (remove spaces, keep colons and digits)
                            val cleanValue = rawValue.filter { it.isDigit() || it == ':' }
                            if (cleanValue.contains(':')) {
                                // Normalize by adding leading zeros
                                normalizeMaskedValue(cleanValue, mask)
                            } else {
                                // No colons yet, just save digits (will be formatted on load)
                                cleanValue
                            }
                        } else {
                            "" // Empty if no digits
                        }
                    } else {
                        rawValue.trim() // For non-masked fields, trim as usual
                    }
                    
                    // Track manual edits to calculated fields
                    if (isCalculated && value != previousValue.trim()) {
                        manuallyEditedCalculatedFields.add(fieldConfig.id)
                    }
                    
                    if (value.isNotEmpty()) {
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = value)
                        markFormChanged()
                    } else {
                        fieldValues.remove(fieldConfig.id)
                        markFormChanged()
                    }
                    
                    // Apply logic rules if this is a numeric field (but skip if this is the target field being manually edited)
                    if (fieldConfig.inputType == "number" && !isCalculated) {
                        applyLogicRules(fieldConfig.id)
                }
                }
            })
        }
        
        return textInputLayout
    }
    
    private fun createTextAreaField(fieldConfig: FormFieldConfig): View {
        val (textInputLayout, editText) = inflateTextInputField(fieldConfig)
        // Don't set hint on EditText - only on TextInputLayout to avoid overlap
        editText.minLines = 3
        editText.maxLines = 5
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        }
        
        // Update fieldValues as user types (only if not read-only)
        if (!isReadOnly) {
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val value = s?.toString()?.trim() ?: ""
                    if (value.isNotEmpty()) {
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = value)
                        markFormChanged()
                    } else {
                        fieldValues.remove(fieldConfig.id)
                        markFormChanged()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        
        return textInputLayout
    }
    
    private fun createDateField(fieldConfig: FormFieldConfig): View {
        val (textInputLayout, editText) = inflateTextInputField(fieldConfig)
        // Don't set hint on EditText - only on TextInputLayout to avoid overlap
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        } else {
            editText.isFocusable = false
            editText.isClickable = true
            editText.setOnClickListener {
                showDatePicker(fieldConfig.id, editText)
            }
        }
        
        return textInputLayout
    }
    
    private fun createTimeField(fieldConfig: FormFieldConfig): View {
        val (textInputLayout, editText) = inflateTextInputField(fieldConfig)
        // Don't set hint on EditText - only on TextInputLayout to avoid overlap
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        } else {
            editText.isFocusable = false
            editText.isClickable = true
            editText.setOnClickListener {
                showTimePicker(fieldConfig.id, editText)
            }
        }
        
        return textInputLayout
    }
    
    private fun createSelectField(fieldConfig: FormFieldConfig): View {
        val (textInputLayout, editText) = inflateTextInputField(fieldConfig)
        // Don't set hint on EditText - only on TextInputLayout to avoid overlap
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        } else {
            editText.isFocusable = false
            editText.isClickable = true
            editText.setOnClickListener {
                showSelectDialog(fieldConfig, editText)
            }
        }
        
        return textInputLayout
    }
    
    private fun createMultiSelectField(fieldConfig: FormFieldConfig): View {
        val (textInputLayout, editText) = inflateTextInputField(fieldConfig)
        // Don't set hint on EditText - only on TextInputLayout to avoid overlap
        
        // Load existing values
        val existingValue = fieldValues[fieldConfig.id]
        if (existingValue?.values != null && existingValue.values.isNotEmpty()) {
            editText.setText(existingValue.values.joinToString(", "))
        }
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        } else {
            editText.isFocusable = false
            editText.isClickable = true
            editText.setOnClickListener {
                showMultiSelectDialog(fieldConfig, editText)
            }
        }
        
        return textInputLayout
    }
    
    private fun showMultiSelectDialog(fieldConfig: FormFieldConfig, editText: TextInputEditText) {
        val options = fieldConfig.options ?: return
        val currentValues = fieldValues[fieldConfig.id]?.values?.toMutableSet() ?: mutableSetOf()
        val checkedItems = BooleanArray(options.size) { i ->
            currentValues.contains(options[i])
        }
        
        AlertDialog.Builder(this)
            .setTitle(fieldConfig.label)
            .setMultiChoiceItems(
                options.toTypedArray(),
                checkedItems
            ) { _, which, isChecked ->
                if (isChecked) {
                    currentValues.add(options[which])
                } else {
                    currentValues.remove(options[which])
                }
            }
            .setPositiveButton("OK") { _, _ ->
                if (currentValues.isNotEmpty()) {
                    editText.setText(currentValues.joinToString(", "))
                    fieldValues[fieldConfig.id] = FormFieldValue(
                        fieldConfig.id,
                        values = currentValues.toList()
                    )
                    markFormChanged()
                } else {
                    editText.setText("")
                    fieldValues.remove(fieldConfig.id)
                    markFormChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun createSelectImageField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_select_image,
            containerFields,
            false
        ) as LinearLayout
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val recyclerView = container.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewOptions)
        val textSelected = container.findViewById<TextView>(R.id.textSelected)
        
        if (fieldConfig.required) {
            textLabel.text = getString(R.string.label_required, fieldConfig.label)
        } else {
            textLabel.text = fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        val imageOptions = fieldConfig.imageOptions ?: emptyList()
        val adapter = ImageOptionAdapter(imageOptions, isMultiSelect = false) { selectedValues ->
            // For single select, take the first (and only) value
            val selectedValue = selectedValues.firstOrNull() ?: return@ImageOptionAdapter
            fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = selectedValue)
            markFormChanged()
        }
        
        // Hide text display
        textSelected.visibility = View.GONE
        
        // Load existing value
        val existingValue = fieldValues[fieldConfig.id]?.value
        if (existingValue != null) {
            adapter.setSelectedValue(existingValue)
        }
        
        recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        recyclerView.adapter = adapter
        
        if (isReadOnly) {
            recyclerView.isEnabled = false
            recyclerView.alpha = 0.6f
        }
        
        fieldViews[fieldConfig.id] = container
        return container
    }
    
    private fun createMultiSelectImageField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_select_image,
            containerFields,
            false
        ) as LinearLayout
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val recyclerView = container.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewOptions)
        val textSelected = container.findViewById<TextView>(R.id.textSelected)
        
        if (fieldConfig.required) {
            textLabel.text = getString(R.string.label_required, fieldConfig.label)
        } else {
            textLabel.text = fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        val imageOptions = fieldConfig.imageOptions ?: emptyList()
        val adapter = ImageOptionAdapter(imageOptions, isMultiSelect = true) { selectedValues ->
            if (selectedValues.isNotEmpty()) {
                fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, values = selectedValues)
            } else {
                fieldValues.remove(fieldConfig.id)
            }
            markFormChanged()
        }
        
        // Hide text display
        textSelected.visibility = View.GONE
        
        // Load existing values
        val existingValues = fieldValues[fieldConfig.id]?.values
        if (existingValues != null && existingValues.isNotEmpty()) {
            adapter.setSelectedValues(existingValues.toSet())
        }
        
        recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        recyclerView.adapter = adapter
        
        if (isReadOnly) {
            recyclerView.isEnabled = false
            recyclerView.alpha = 0.6f
        }
        
        fieldViews[fieldConfig.id] = container
        return container
    }
    
    private fun createCheckboxField(fieldConfig: FormFieldConfig): View {
        val (container, checkbox) = inflateCheckboxFieldInto(fieldConfig, containerFields, fieldConfig.id)
        
        // Disable editing if read-only
        if (isReadOnly) {
            checkbox.isEnabled = false
            checkbox.isFocusable = false
            checkbox.isClickable = false
        } else {
            // Update fieldValues when checkbox state changes
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = "true")
                } else {
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = "false")
                }
                markFormChanged()
            }
        }
        
        fieldViews[fieldConfig.id] = container
        return container
    }
    
    private fun createGPSField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_gps,
            containerFields,
            false
        ) as LinearLayout
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val buttonGetLocation = container.findViewById<MaterialButton>(R.id.buttonGetLocation)
        val editTextLatitude = container.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val editTextLongitude = container.findViewById<TextInputEditText>(R.id.editTextLongitude)
        
        textLabel.text = if (fieldConfig.required) {
            "${fieldConfig.label} *"
        } else {
            fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        // Load existing GPS coordinates
        val existingValue = fieldValues[fieldConfig.id]
        if (existingValue?.gpsLatitude != null && existingValue.gpsLongitude != null) {
            editTextLatitude.setText(existingValue.gpsLatitude.toString())
            editTextLongitude.setText(existingValue.gpsLongitude.toString())
        }
        
        if (isReadOnly) {
            editTextLatitude.setKeyListener(null)
            editTextLatitude.setTextIsSelectable(true)
            editTextLongitude.setKeyListener(null)
            editTextLongitude.setTextIsSelectable(true)
            buttonGetLocation.isEnabled = false
        } else {
            // Update fieldValues as user types
            editTextLatitude.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    updateGPSFieldValue(fieldConfig.id, editTextLatitude, editTextLongitude)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            
            editTextLongitude.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    updateGPSFieldValue(fieldConfig.id, editTextLatitude, editTextLongitude)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            
            buttonGetLocation.setOnClickListener {
                // Get current values from text fields to pass to GPS picker
                val currentLat = editTextLatitude.text?.toString()?.toDoubleOrNull()
                val currentLon = editTextLongitude.text?.toString()?.toDoubleOrNull()
                openGPSPicker(fieldConfig.id, currentLat, currentLon)
            }
        }
        
        fieldViews[fieldConfig.id] = container
        return container
    }
    
    private fun updateGPSFieldValue(fieldId: String, editTextLatitude: TextInputEditText, editTextLongitude: TextInputEditText) {
        val latStr = editTextLatitude.text?.toString()?.trim() ?: ""
        val lonStr = editTextLongitude.text?.toString()?.trim() ?: ""
        
        val latitude = latStr.toDoubleOrNull()
        val longitude = lonStr.toDoubleOrNull()
        
        if (latitude != null && longitude != null) {
            fieldValues[fieldId] = FormFieldValue(
                fieldId,
                gpsLatitude = latitude,
                gpsLongitude = longitude
            )
            markFormChanged()
        } else {
            // Clear if either field is empty or invalid
            if (latStr.isEmpty() && lonStr.isEmpty()) {
                fieldValues.remove(fieldId)
                markFormChanged()
            }
        }
    }
    
    private fun openGPSPicker(fieldId: String, currentLat: Double? = null, currentLon: Double? = null) {
        if (gpsPickerLaunched) return
        gpsPickerLaunched = true
        val intent = Intent(this, GPSPickerActivity::class.java).apply {
            putExtra("fieldId", fieldId)
            // Pass current coordinates if available
            if (currentLat != null && currentLon != null) {
                putExtra("latitude", currentLat)
                putExtra("longitude", currentLon)
            } else {
                // Fallback to existing value in fieldValues
                val existingValue = fieldValues[fieldId]
                if (existingValue?.gpsLatitude != null && existingValue.gpsLongitude != null) {
                    putExtra("latitude", existingValue.gpsLatitude)
                    putExtra("longitude", existingValue.gpsLongitude)
                }
            }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_GPS_PICKER)
    }
    
    companion object {
        private const val REQUEST_CODE_GPS_PICKER = 2001
        private const val FOCUS_TRAVERSAL_DEBOUNCE_MS = 400L
        private const val FOCUS_CHANGE_DEBOUNCE_MS = 120L
    }
    
    /**
     * Builds the form key for a dynamic sub-field. Format: "${dynamicFieldId}_instance${instanceIndex}_${subFieldId}".
     * Used when building or parsing uniqueFieldId (Review #79).
     */
    private fun formKeyForDynamicSubField(dynamicFieldId: String, instanceIndex: Int, subFieldId: String): String =
        "${dynamicFieldId}_instance${instanceIndex}_${subFieldId}"
    
    /**
     * Extracts the dynamic field ID from a dynamic sub-field form key, or null if not in that format.
     */
    private fun dynamicFieldIdFromFormKey(formKey: String): String? =
        formKey.substringBefore("_instance").takeIf { it != formKey }
    
    /**
     * Extracts the instance prefix (e.g. "dynamicFieldId_instance0_") from a form key for logic-rule matching.
     */
    private fun instancePrefixFromFormKey(formKey: String): String? {
        if (!formKey.contains("_instance")) return null
        val lastUnderscore = formKey.lastIndexOf("_")
        if (lastUnderscore <= 0) return null
        return formKey.take(lastUnderscore + 1)
    }
    
    /**
     * Parses a dynamic sub-field form key into (dynamicFieldId, instanceIndex, subFieldId), or null if invalid.
     */
    private fun parseDynamicSubFieldFormKey(formKey: String): Triple<String, Int, String>? {
        if (!formKey.contains("_instance")) return null
        val parts = formKey.split("_instance", limit = 2)
        if (parts.size != 2) return null
        val dynamicFieldId = parts[0]
        val rest = parts[1]
        val subParts = rest.split("_", limit = 2)
        if (subParts.size != 2) return null
        val instanceIndex = subParts[0].toIntOrNull() ?: return null
        val subFieldId = subParts[1]
        return Triple(dynamicFieldId, instanceIndex, subFieldId)
    }
    
    private fun createPhotoField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_photo,
            containerFields,
            false
        ) as LinearLayout
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val buttonCapture = container.findViewById<MaterialButton>(R.id.buttonCapture)
        val textFileName = container.findViewById<TextView>(R.id.textFileName)
        
        textLabel.text = if (fieldConfig.required) {
            "${fieldConfig.label} *"
        } else {
            fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        // Load existing photo filename
        val existingValue = fieldValues[fieldConfig.id]
        if (existingValue?.photoFileName != null) {
            textFileName.text = getString(R.string.photo_filename, existingValue.photoFileName)
            textFileName.visibility = View.VISIBLE
        }
        
        if (!isReadOnly) {
            buttonCapture.setOnClickListener {
                currentPhotoFieldId = fieldConfig.id
                checkCameraPermissionAndCapture()
            }
        } else {
            buttonCapture.isEnabled = false
        }
        
        return container
    }
    
    private fun checkCameraPermissionAndCapture() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                capturePhoto(currentPhotoFieldId ?: return)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private fun capturePhoto(fieldId: String) {
        try {
            // Create a file to store the photo
            val photoDir = File(getExternalFilesDir(null), "photos")
            if (!photoDir.exists()) {
                photoDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(photoDir, "IMG_${timestamp}.jpg")
            this.photoFile = photoFile
            
            val photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            
            // Launch custom camera activity with back camera
            val intent = Intent(this, CameraActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_OUTPUT_URI, photoUri)
                putExtra(CameraActivity.EXTRA_OUTPUT_FILE_PATH, photoFile.absolutePath)
            }
            cameraActivityLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error capturing photo: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("FormEditActivity", "Error capturing photo", e)
        }
    }
    
    private fun savePhotoToMediaStore(photoFile: File): Uri? {
        return try {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, photoFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TREC_Logsheets")
            }
            
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            uri?.let {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    photoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            
            uri
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error saving photo to MediaStore", e)
            null
        }
    }
    
    private fun updatePhotoFieldView(fieldId: String, fileName: String) {
        // Try to find the view in regular fields first
        val fieldView = fieldViews[fieldId]
        if (fieldView != null) {
            val textFileName = fieldView.findViewById<TextView>(R.id.textFileName)
            textFileName?.text = getString(R.string.photo_filename, fileName)
            textFileName?.visibility = View.VISIBLE
            return
        }
        
        // If not found, it might be a dynamic widget sub-field
        // Use findViewWithTag to search the entire view hierarchy
        val photoView = containerFields.findViewWithTag<View>(fieldId)
        if (photoView != null) {
            val textFileName = photoView.findViewById<TextView>(R.id.textFileName)
            textFileName?.text = getString(R.string.photo_filename, fileName)
            textFileName?.visibility = View.VISIBLE
        }
    }
    
    private fun createBarcodeField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_barcode,
            containerFields,
            false
        ) as LinearLayout
        
        val textInputLayout = container.findViewById<TextInputLayout>(R.id.textInputLayout)
        val editText = container.findViewById<TextInputEditText>(R.id.editText)
        val buttonScan = container.findViewById<MaterialButton>(R.id.buttonScan)
        
        // Set hint/label
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        } else {
            textInputLayout.hint = fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        // Load existing barcode value
        val existingValue = fieldValues[fieldConfig.id]
        if (existingValue?.value != null) {
            editText.setText(existingValue.value)
        }
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
            buttonScan.isEnabled = false
        } else {
            // Update fieldValues as user types
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val value = s?.toString()?.trim() ?: ""
                    if (value.isNotEmpty()) {
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = value)
                        markFormChanged()
                    } else {
                        fieldValues.remove(fieldConfig.id)
                        markFormChanged()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            
            // Set up scan button
            buttonScan.setOnClickListener {
                currentBarcodeFieldId = fieldConfig.id
                startBarcodeScanning()
            }
        }
        
        return container
    }
    
    private fun createTableField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_table,
            containerFields,
            false
        ) as LinearLayout
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val tableLayout = container.findViewById<TableLayout>(R.id.tableLayout)
        
        // Set label
        textLabel.text = if (fieldConfig.required) {
            "${fieldConfig.label} *"
        } else {
            fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        // Get rows and columns from config
        val rows = fieldConfig.rows ?: emptyList()
        val columns = fieldConfig.columns ?: emptyList()
        val inputType = fieldConfig.inputType ?: "text"
        
        if (rows.isEmpty() || columns.isEmpty()) {
            android.util.Log.w("FormEditActivity", "Table field ${fieldConfig.id} has no rows or columns")
            return container
        }
        
        // Load existing table data
        val existingValue = fieldValues[fieldConfig.id]
        val existingTableData = existingValue?.tableData ?: emptyMap()
        
        // Table cell styling: padding, row height, and grid line. Defined inline here; could be moved to styles/themes in a follow-up (Review #76).
        // Light grid line (mutate per cell so drawable state not shared)
        fun gridLine() = ContextCompat.getDrawable(this, R.drawable.table_cell_grid_line)?.mutate()
        val cellPadding = (8 * resources.displayMetrics.density).toInt() // 8dp
        val minRowHeight = (40 * resources.displayMetrics.density).toInt() // 40dp
        // Layout params for table cells (width 0 + weight 1f): compute once before loops
        val tableCellLpWidth = 0
        val tableCellLpHeight = TableRow.LayoutParams.MATCH_PARENT
        val tableCellLpWeight = 1f
        
        // Create header row
        val headerRow = TableRow(this).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            ).apply { minimumHeight = minRowHeight }
        }
        
        // Add empty cell for row header column (width=0 + weight 1f for equal columns, height=MATCH_PARENT for even row height)
        val emptyHeader = TextView(this).apply {
            text = ""
            setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
            setBackgroundColor(0xFFE0E0E0.toInt())
            minimumHeight = minRowHeight
            layoutParams = TableRow.LayoutParams(tableCellLpWidth, tableCellLpHeight, tableCellLpWeight)
            gridLine()?.let { foreground = it }
        }
        headerRow.addView(emptyHeader)
        
        // Add column headers
        for (column in columns) {
            val columnHeader = TextView(this).apply {
                text = column
                setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                setBackgroundColor(0xFFE0E0E0.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                minimumHeight = minRowHeight
                layoutParams = TableRow.LayoutParams(tableCellLpWidth, tableCellLpHeight, tableCellLpWeight)
                gridLine()?.let { foreground = it }
            }
            headerRow.addView(columnHeader)
        }
        tableLayout.addView(headerRow)
        
        // Create data rows (same min height and MATCH_PARENT cell height for alignment)
        for (row in rows) {
            val dataRow = TableRow(this).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                ).apply { minimumHeight = minRowHeight }
            }
            
            // Row header
            val rowHeader = TextView(this).apply {
                text = row
                setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                setBackgroundColor(0xFFF5F5F5.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = TableRow.LayoutParams(tableCellLpWidth, tableCellLpHeight, tableCellLpWeight)
                gridLine()?.let { foreground = it }
            }
            dataRow.addView(rowHeader)
            
            // Data cells
            for (column in columns) {
                val cellValue = existingTableData[row]?.get(column) ?: ""
                
                if (inputType.lowercase() == "boolean") {
                    // Create checkbox for boolean input type
                    val checkboxContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        layoutParams = TableRow.LayoutParams(tableCellLpWidth, tableCellLpHeight, tableCellLpWeight)
                        setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                        gridLine()?.let { foreground = it }
                    }
                    
                    val checkbox = CheckBox(this).apply {
                        isChecked = cellValue.lowercase() == "true"
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        
                        // Store row and column in tag for value collection
                        tag = "$row|$column"
                        
                        // Disable if read-only
                        if (isReadOnly) {
                            isEnabled = false
                            isFocusable = false
                            isClickable = false
                        } else {
                            // Mark form as changed and update fieldValues when checkbox state changes
                            setOnCheckedChangeListener { _, _ ->
                                // Update fieldValues with current table data
                                updateTableFieldValue(fieldConfig.id, tableLayout)
                                markFormChanged()
                            }
                        }
                    }
                    checkboxContainer.addView(checkbox)
                    dataRow.addView(checkboxContainer)
                } else {
                    // Create text input for other input types
                val editText = TextInputEditText(this).apply {
                    hint = ""
                    setText(cellValue)
                    setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                    setBackgroundColor(android.graphics.Color.WHITE)
                    layoutParams = TableRow.LayoutParams(tableCellLpWidth, tableCellLpHeight, tableCellLpWeight)
                    gridLine()?.let { foreground = it }
                    
                    // Set input type
                    when (inputType.lowercase()) {
                        "number" -> {
                            this.inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or 
                                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                        "integer" -> {
                            this.inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                        else -> {
                            this.inputType = android.text.InputType.TYPE_CLASS_TEXT
                        }
                    }
                    
                    // Store row and column in tag for value collection
                    tag = "$row|$column"
                    
                    // Disable if read-only
                    if (isReadOnly) {
                        isEnabled = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        isClickable = false
                    } else {
                        // Mark form as changed and update fieldValues when text changes
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: android.text.Editable?) {
                                // Update fieldValues with current table data
                                updateTableFieldValue(fieldConfig.id, tableLayout)
                                markFormChanged()
                            }
                        })
                    }
                }
                dataRow.addView(editText)
                }
            }
            tableLayout.addView(dataRow)
        }
        
        return container
    }
    
    /**
     * Helper method to collect table data from a TableLayout and update fieldValues
     */
    private fun updateTableFieldValue(fieldId: String, tableLayout: TableLayout) {
        val tableData = mutableMapOf<String, MutableMap<String, String>>()
        
        // Iterate through table rows (skip header row at index 0)
        for (i in 1 until tableLayout.childCount) {
            val row = tableLayout.getChildAt(i) as? TableRow ?: continue
            // First child is row header, rest are data cells
            val rowHeader = row.getChildAt(0) as? TextView
            val rowName = rowHeader?.text?.toString() ?: continue
            
            val rowData = mutableMapOf<String, String>()
            // Get column names from header row
            val headerRow = tableLayout.getChildAt(0) as? TableRow
            val columnNames = mutableListOf<String>()
            if (headerRow != null) {
                for (j in 1 until headerRow.childCount) {
                    val colHeader = headerRow.getChildAt(j) as? TextView
                    colHeader?.text?.toString()?.let { columnNames.add(it) }
                }
            }
            
            // Collect cell values
            for (j in 1 until row.childCount) {
                val cell = row.getChildAt(j)
                val columnIndex = j - 1
                if (columnIndex < columnNames.size) {
                    val columnName = columnNames[columnIndex]
                    val cellValue = when (cell) {
                        is LinearLayout -> {
                            // Check if this is a checkbox container
                            val checkbox = cell.getChildAt(0) as? CheckBox
                            if (checkbox != null) {
                                if (checkbox.isChecked) "true" else "false"
                            } else {
                                ""
                            }
                        }
                        is CheckBox -> {
                            if (cell.isChecked) "true" else "false"
                        }
                        is TextInputEditText -> {
                            cell.text?.toString()?.trim() ?: ""
                        }
                        else -> ""
                    }
                    if (cellValue.isNotEmpty()) {
                        rowData[columnName] = cellValue
                    }
                }
            }
            
            if (rowData.isNotEmpty()) {
                tableData[rowName] = rowData
            }
        }
        
        // Update fieldValues
        if (tableData.isNotEmpty()) {
            fieldValues[fieldId] = FormFieldValue(fieldId, tableData = tableData)
        } else {
            fieldValues.remove(fieldId)
        }
    }
    
    private fun createDynamicField(fieldConfig: FormFieldConfig): View {
        return try {
            val inflater = LayoutInflater.from(this)
            val container = inflater.inflate(
                R.layout.field_dynamic,
                containerFields,
                false
            ) as LinearLayout
            
            val textLabel = container.findViewById<TextView>(R.id.textLabel)
            val containerInstances = container.findViewById<LinearLayout>(R.id.containerInstances)
            val buttonAdd = container.findViewById<MaterialButton>(R.id.buttonAdd)
            
            if (textLabel == null || containerInstances == null || buttonAdd == null) {
                android.util.Log.e("FormEditActivity", "Failed to find views in field_dynamic layout")
                throw IllegalStateException("Failed to find required views in dynamic field layout")
            }
            
            // Set label
            textLabel.text = if (fieldConfig.required) {
                "${fieldConfig.label} *"
            } else {
                fieldConfig.label
            }
        
        container.tag = fieldConfig.id
        // Store dynamic field ID in containerInstances for easy access
        containerInstances.tag = fieldConfig.id
        
        val subFields = fieldConfig.subFields ?: emptyList()
        if (subFields.isEmpty()) {
            android.util.Log.w("FormEditActivity", "Dynamic field ${fieldConfig.id} has no subFields")
            return container
        }
        
        // Load existing dynamic data
        val existingValue = fieldValues[fieldConfig.id]
        val existingInstances = existingValue?.dynamicData ?: emptyList()
        
        // Create initial instance if none exist
        val instancesToCreate = existingInstances.ifEmpty {
            listOf(emptyMap<String, FormFieldValue>())
        }
        
        // Create instances
        for ((index, instanceData) in instancesToCreate.withIndex()) {
            createDynamicInstance(containerInstances, fieldConfig.id, index, subFields, instanceData, isReadOnly)
        }
        
        if (!isReadOnly) {
            // Update add button state
            updateAddButtonState(buttonAdd, containerInstances, subFields)
            
            buttonAdd.setOnClickListener {
                val newInstanceIndex = containerInstances.childCount
                createDynamicInstance(containerInstances, fieldConfig.id, newInstanceIndex, subFields, emptyMap(), isReadOnly)
                updateAddButtonState(buttonAdd, containerInstances, subFields)
                markFormChanged()
            }
        } else {
            buttonAdd.isEnabled = false
        }
        
        return container
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error creating dynamic field ${fieldConfig.id}: ${e.message}", e)
            e.printStackTrace()
            // Return error view
            TextView(this).apply {
                text = "Error: ${fieldConfig.label} - ${e.message}"
                setTextColor(android.graphics.Color.RED)
            }
        }
    }
    
    private fun createDynamicInstance(
        containerInstances: LinearLayout,
        dynamicFieldId: String,
        instanceIndex: Int,
        subFields: List<FormFieldConfig>,
        instanceData: Map<String, FormFieldValue>,
        isReadOnly: Boolean
    ) {
        try {
            val inflater = LayoutInflater.from(this)
            val instanceView = inflater.inflate(
                R.layout.item_dynamic_instance,
                containerInstances,
                false
            ) as com.google.android.material.card.MaterialCardView
            
            val textInstanceNumber = instanceView.findViewById<TextView>(R.id.textInstanceNumber)
            val buttonDelete = instanceView.findViewById<MaterialButton>(R.id.buttonDelete)
            val containerSubFields = instanceView.findViewById<LinearLayout>(R.id.containerSubFields)
            
            if (textInstanceNumber == null || buttonDelete == null || containerSubFields == null) {
                android.util.Log.e("FormEditActivity", "Failed to find views in item_dynamic_instance layout")
                throw IllegalStateException("Failed to find required views in instance layout")
            }
            
            // Use custom instance name if provided, otherwise default to "Instance"
            val instanceName = formConfig.fields.firstOrNull { it.id == dynamicFieldId }?.instanceName ?: "Instance"
            textInstanceNumber.text = getString(R.string.instance_number, instanceName, instanceIndex + 1)
            
            // Store instance index in tag
            instanceView.tag = instanceIndex
            
            // Create sub-fields for this instance
            for (subFieldConfig in subFields) {
                try {
                    val subFieldView = createSubFieldView(
                        dynamicFieldId,
                        instanceIndex,
                        subFieldConfig,
                        instanceData[subFieldConfig.id],
                        isReadOnly,
                        containerSubFields
                    )
                    containerSubFields.addView(subFieldView)
                } catch (e: Exception) {
                    android.util.Log.e("FormEditActivity", "Error creating sub-field ${subFieldConfig.id} in instance $instanceIndex: ${e.message}", e)
                    // Add error view for this sub-field
                    val errorView = TextView(this).apply {
                        text = "Error: ${subFieldConfig.label} - ${e.message}"
                        setTextColor(android.graphics.Color.RED)
                    }
                    containerSubFields.addView(errorView)
                }
            }
        
        if (!isReadOnly) {
            buttonDelete.setOnClickListener {
                containerInstances.removeView(instanceView)
                // Update instance numbers, delete button states, and add button state
                updateInstanceNumbers(containerInstances)
                updateDeleteButtonStates(containerInstances)
                val parent = containerInstances.parent as? View
                val buttonAdd = parent?.findViewById<MaterialButton>(R.id.buttonAdd)
                if (buttonAdd != null) {
                    updateAddButtonState(buttonAdd, containerInstances, subFields)
                }
                markFormChanged()
            }
        } else {
            buttonDelete.isEnabled = false
        }
        
        containerInstances.addView(instanceView)
        
            // Update delete button states after adding
            if (!isReadOnly) {
                updateDeleteButtonStates(containerInstances)
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error creating dynamic instance $instanceIndex: ${e.message}", e)
            e.printStackTrace()
            // Add error view to container
            val errorView = TextView(this).apply {
                text = "Error creating instance ${instanceIndex + 1}: ${e.message}"
                setTextColor(android.graphics.Color.RED)
                setPadding(16, 16, 16, 16)
            }
            containerInstances.addView(errorView)
        }
    }
    
    private fun createSubFieldView(
        dynamicFieldId: String,
        instanceIndex: Int,
        subFieldConfig: FormFieldConfig,
        existingValue: FormFieldValue?,
        isReadOnly: Boolean,
        parent: ViewGroup
    ): View {
        // Create a unique field ID for this sub-field in this instance
        val uniqueFieldId = formKeyForDynamicSubField(dynamicFieldId, instanceIndex, subFieldConfig.id)
        
        // Temporarily store the existing value with the unique ID
        if (existingValue != null) {
            fieldValues[uniqueFieldId] = existingValue.copy(fieldId = uniqueFieldId)
        }
        
        // Create the sub-field view using helper methods that accept a parent parameter
        val subFieldView = try {
            when (subFieldConfig.type) {
                FormFieldConfig.FieldType.TEXT -> createTextFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.TEXTAREA -> createTextAreaFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.DATE -> createDateFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.TIME -> createTimeFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.SELECT -> createSelectFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.MULTISELECT -> createMultiSelectFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.GPS -> createGPSFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.BARCODE -> createBarcodeFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.CHECKBOX -> createCheckboxFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                FormFieldConfig.FieldType.PHOTO -> createPhotoFieldForSubField(subFieldConfig, uniqueFieldId, parent)
                else -> {
                    android.util.Log.w("FormEditActivity", "Unsupported sub-field type: ${subFieldConfig.type} in dynamic widget")
                    TextView(this).apply {
                        text = "Unsupported field type: ${subFieldConfig.label}"
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error creating sub-field ${subFieldConfig.id}: ${e.message}", e)
            TextView(this).apply {
                text = "Error: ${subFieldConfig.label}"
                setTextColor(android.graphics.Color.RED)
            }
        }
        
        // Update the tag to include the unique ID and instance info
        // Store metadata in a custom data class since we can't use system resource IDs
        subFieldView.tag = uniqueFieldId
        // Store additional metadata in view's tag as a Pair or use a data class
        // We'll extract dynamicFieldId and instanceIndex from uniqueFieldId when needed
        
        // Store in fieldViews for later collection
        fieldViews[uniqueFieldId] = subFieldView
        
        // Set existing value if available
        if (existingValue != null) {
            setFieldValue(subFieldView, subFieldConfig, existingValue)
        }
        
        // Add TextWatcher or change listener to update add button state and fieldValues
        if (!isReadOnly) {
            addSubFieldChangeListener(subFieldView, subFieldConfig, uniqueFieldId, dynamicFieldId, instanceIndex)
        }
        
        return subFieldView
    }
    
    // Helper methods to create sub-fields for dynamic widgets (shared setup with top-level via inflateTextInputFieldInto; Review #78)
    private fun createTextFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val (textInputLayout, editText) = inflateTextInputFieldInto(fieldConfig, parent, uniqueFieldId)
        // Don't set hint on EditText - only on TextInputLayout to avoid overlap
        if (fieldConfig.inputType == "number") {
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        
        // Handle mask if present
        val mask = fieldConfig.mask
        if (mask != null) {
            // Set mask as placeholder
            editText.hint = mask
            // Initialize mask input helper to prevent colon deletion and limit section lengths
            MaskInputHelper(editText, mask)
            // On focus, only initialize with colons if field is empty
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val currentText = editText.text?.toString() ?: ""
                    // Only initialize if field is empty or only contains spaces/colons (no digits)
                    val hasDigits = currentText.any { it.isDigit() }
                    if (!hasDigits && (currentText.isEmpty() || currentText.all { it == ' ' || it == ':' })) {
                        // Show only colons in their positions
                        val colonsOnly = mask.map { char -> if (char == ':') ':' else ' ' }.joinToString("")
                        editText.setText(colonsOnly)
                        // Move cursor to first editable position
                        val firstEditablePos = mask.indexOfFirst { it != ':' }.takeIf { it >= 0 } ?: 0
                        editText.setSelection(firstEditablePos)
                    }
                    // If field has digits, keep the current value - don't clear it
                }
            }
        }
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        }
        // Note: TextWatcher will be added in addSubFieldChangeListener
        
        return textInputLayout
    }
    
    private fun createCheckboxFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val (container, checkbox) = inflateCheckboxFieldInto(fieldConfig, parent, uniqueFieldId)
        
        if (isReadOnly) {
            checkbox.isEnabled = false
            checkbox.isFocusable = false
            checkbox.isClickable = false
        }
        // Note: ChangeListener will be added in addSubFieldChangeListener
        
        return container
    }
    
    private fun createTextAreaFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val (textInputLayout, editText) = inflateTextInputFieldInto(fieldConfig, parent, uniqueFieldId)
        editText.minLines = 3
        editText.maxLines = 5
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        }
        // Note: TextWatcher will be added in addSubFieldChangeListener
        
        return textInputLayout
    }
    
    private fun createDateFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val (textInputLayout, editText) = inflateTextInputFieldInto(fieldConfig, parent, uniqueFieldId)
        editText.isFocusable = false
        editText.isClickable = true
        
        if (!isReadOnly) {
            editText.setOnClickListener {
                showDatePickerForSubField(uniqueFieldId, editText)
            }
        } else {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        }
        
        return textInputLayout
    }
    
    private fun createTimeFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val (textInputLayout, editText) = inflateTextInputFieldInto(fieldConfig, parent, uniqueFieldId)
        editText.isFocusable = false
        editText.isClickable = true
        
        if (!isReadOnly) {
            editText.setOnClickListener {
                showTimePickerForSubField(uniqueFieldId, editText)
            }
        } else {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        }
        
        return textInputLayout
    }
    
    private fun createSelectFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val (textInputLayout, editText) = inflateTextInputFieldInto(fieldConfig, parent, uniqueFieldId)
        editText.isFocusable = false
        editText.isClickable = true
        
        if (!isReadOnly) {
            editText.setOnClickListener {
                showSelectDialogForSubField(fieldConfig, editText, uniqueFieldId)
            }
        } else {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        }
        
        return textInputLayout
    }
    
    private fun createMultiSelectFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val (textInputLayout, editText) = inflateTextInputFieldInto(fieldConfig, parent, uniqueFieldId)
        editText.isFocusable = false
        editText.isClickable = true
        
        // Load existing values
        val existingValue = fieldValues[uniqueFieldId]
        if (existingValue?.values != null && existingValue.values.isNotEmpty()) {
            editText.setText(existingValue.values.joinToString(", "))
        }
        
        if (!isReadOnly) {
            editText.setOnClickListener {
                showMultiSelectDialogForSubField(fieldConfig, editText, uniqueFieldId)
            }
        } else {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
        }
        
        return textInputLayout
    }
    
    private fun createGPSFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_gps,
            parent,
            false
        ) as LinearLayout
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val buttonGetLocation = container.findViewById<MaterialButton>(R.id.buttonGetLocation)
        val editTextLatitude = container.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val editTextLongitude = container.findViewById<TextInputEditText>(R.id.editTextLongitude)
        
        if (textLabel == null || buttonGetLocation == null || editTextLatitude == null || editTextLongitude == null) {
            android.util.Log.e("FormEditActivity", "Failed to find views in field_gps layout")
            throw IllegalStateException("Failed to find required views in GPS field layout")
        }
        
        if (fieldConfig.required) {
            textLabel.text = getString(R.string.label_required, fieldConfig.label)
        } else {
            textLabel.text = fieldConfig.label
        }
        
        container.tag = uniqueFieldId
        
        // Load existing GPS coordinates
        val existingValue = fieldValues[uniqueFieldId]
        if (existingValue?.gpsLatitude != null && existingValue.gpsLongitude != null) {
            editTextLatitude.setText(existingValue.gpsLatitude.toString())
            editTextLongitude.setText(existingValue.gpsLongitude.toString())
        }
        
        if (isReadOnly) {
            editTextLatitude.setKeyListener(null)
            editTextLatitude.setTextIsSelectable(true)
            editTextLongitude.setKeyListener(null)
            editTextLongitude.setTextIsSelectable(true)
            buttonGetLocation.isEnabled = false
        } else {
            buttonGetLocation.setOnClickListener {
                // Get current values from text fields to pass to GPS picker
                val currentLat = editTextLatitude.text?.toString()?.toDoubleOrNull()
                val currentLon = editTextLongitude.text?.toString()?.toDoubleOrNull()
                openGPSPicker(uniqueFieldId, currentLat, currentLon)
            }
            // Note: TextWatchers will be added in addSubFieldChangeListener
        }
        
        return container
    }
    
    private fun createPhotoFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_photo,
            parent,
            false
        ) as LinearLayout
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val buttonCapture = container.findViewById<MaterialButton>(R.id.buttonCapture)
        val textFileName = container.findViewById<TextView>(R.id.textFileName)
        
        if (textLabel == null || buttonCapture == null || textFileName == null) {
            android.util.Log.e("FormEditActivity", "Failed to find views in field_photo layout")
            throw IllegalStateException("Failed to find required views in photo field layout")
        }
        
        if (fieldConfig.required) {
            textLabel.text = getString(R.string.label_required, fieldConfig.label)
        } else {
            textLabel.text = fieldConfig.label
        }
        
        container.tag = uniqueFieldId
        
        // Load existing photo filename
        val existingValue = fieldValues[uniqueFieldId]
        if (existingValue?.photoFileName != null) {
            textFileName.text = getString(R.string.photo_filename, existingValue.photoFileName)
            textFileName.visibility = View.VISIBLE
        }
        
        if (!isReadOnly) {
            buttonCapture.setOnClickListener {
                currentPhotoFieldId = uniqueFieldId
                checkCameraPermissionAndCapture()
            }
        } else {
            buttonCapture.isEnabled = false
        }
        
        return container
    }
    
    private fun createBarcodeFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_barcode,
            parent,
            false
        ) as LinearLayout
        
        val textInputLayout = container.findViewById<TextInputLayout>(R.id.textInputLayout)
        val editText = container.findViewById<TextInputEditText>(R.id.editText)
        val buttonScan = container.findViewById<MaterialButton>(R.id.buttonScan)
        
        if (textInputLayout == null || editText == null || buttonScan == null) {
            android.util.Log.e("FormEditActivity", "Failed to find views in field_barcode layout")
            throw IllegalStateException("Failed to find required views in barcode field layout")
        }
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        } else {
            textInputLayout.hint = fieldConfig.label
        }
        
        container.tag = uniqueFieldId
        
        // Read-only: allow focus and selection for copying, but not editing
        if (isReadOnly) {
            editText.setKeyListener(null)
            editText.setTextIsSelectable(true)
            buttonScan.isEnabled = false
        } else {
            buttonScan.setOnClickListener {
                currentBarcodeFieldId = uniqueFieldId
                startBarcodeScanning()
            }
            // Note: TextWatcher will be added in addSubFieldChangeListener
        }
        
        return container
    }
    
    private fun addSubFieldChangeListener(
        subFieldView: View,
        subFieldConfig: FormFieldConfig,
        uniqueFieldId: String,
        dynamicFieldId: String,
        @Suppress("UNUSED_PARAMETER") instanceIndex: Int
    ) {
        when (subFieldConfig.type) {
            FormFieldConfig.FieldType.TEXT,
            FormFieldConfig.FieldType.TEXTAREA,
            FormFieldConfig.FieldType.DATE,
            FormFieldConfig.FieldType.TIME,
            FormFieldConfig.FieldType.SELECT,
            FormFieldConfig.FieldType.BARCODE -> {
                val editText = subFieldView.findViewById<TextInputEditText>(R.id.editText)
                val mask = subFieldConfig.mask
                editText?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val rawValue = s?.toString() ?: ""
                        // For masked fields, check if value has digits (not just spaces/colons)
                        val value = if (mask != null) {
                        // For masked fields, normalize the value (add leading zeros) before saving
                        if (rawValue.any { it.isDigit() }) {
                            // Extract the actual value (remove spaces, keep colons and digits)
                            val cleanValue = rawValue.filter { it.isDigit() || it == ':' }
                            if (cleanValue.contains(':')) {
                                // Normalize by adding leading zeros
                                normalizeMaskedValue(cleanValue, mask)
                            } else {
                                // No colons yet, just save digits (will be formatted on load)
                                cleanValue
                            }
                        } else {
                            "" // Empty if no digits
                        }
                        } else {
                            rawValue.trim() // For non-masked fields, trim as usual
                        }
                        
                        if (value.isNotEmpty()) {
                            fieldValues[uniqueFieldId] = FormFieldValue(uniqueFieldId, value = value)
                        } else {
                            fieldValues.remove(uniqueFieldId)
                        }
                        markFormChanged()
                        updateAddButtonForDynamicField(dynamicFieldId)
                        
                        // Apply logic rules if this is a numeric field
                        // Note: Logic rules use base field IDs (subFieldConfig.id), not unique IDs
                        if (subFieldConfig.inputType == "number") {
                            // Check if this base field ID is used in any logic rule
                            val isInLogicRule = formConfig.logic.any { rule ->
                                rule.sourceIds.contains(subFieldConfig.id) || rule.targetId == subFieldConfig.id
                            }
                            if (isInLogicRule) {
                                val instancePrefix = instancePrefixFromFormKey(uniqueFieldId)
                                // Apply logic using the base field ID and instance prefix
                                applyLogicRules(subFieldConfig.id, instancePrefix)
                            }
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            }
            FormFieldConfig.FieldType.CHECKBOX -> {
                val checkbox = subFieldView.findViewById<CheckBox>(R.id.checkbox)
                checkbox?.setOnCheckedChangeListener { _, isChecked ->
                    fieldValues[uniqueFieldId] = FormFieldValue(uniqueFieldId, value = if (isChecked) "true" else "false")
                    markFormChanged()
                    updateAddButtonForDynamicField(dynamicFieldId)
                }
            }
            FormFieldConfig.FieldType.GPS -> {
                val editTextLatitude = subFieldView.findViewById<TextInputEditText>(R.id.editTextLatitude)
                val editTextLongitude = subFieldView.findViewById<TextInputEditText>(R.id.editTextLongitude)
                
                val updateGPSValue = {
                    val latStr = editTextLatitude?.text?.toString()?.trim() ?: ""
                    val lonStr = editTextLongitude?.text?.toString()?.trim() ?: ""
                    val latitude = latStr.toDoubleOrNull()
                    val longitude = lonStr.toDoubleOrNull()
                    
                    if (latitude != null && longitude != null) {
                        fieldValues[uniqueFieldId] = FormFieldValue(
                            uniqueFieldId,
                            gpsLatitude = latitude,
                            gpsLongitude = longitude
                        )
                    } else {
                        if (latStr.isEmpty() && lonStr.isEmpty()) {
                            fieldValues.remove(uniqueFieldId)
                        }
                    }
                    markFormChanged()
                    updateAddButtonForDynamicField(dynamicFieldId)
                }
                
                editTextLatitude?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        updateGPSValue()
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
                
                editTextLongitude?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        updateGPSValue()
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            }
            else -> {
                // For other types, we'll handle in their respective change handlers
            }
        }
    }
    
    private fun updateAddButtonForDynamicField(dynamicFieldId: String) {
        val dynamicFieldView = fieldViews[dynamicFieldId] ?: return
        val containerInstances = dynamicFieldView.findViewById<LinearLayout>(R.id.containerInstances)
        val buttonAdd = dynamicFieldView.findViewById<MaterialButton>(R.id.buttonAdd)
        val fieldConfig = formConfig.fields.firstOrNull { it.id == dynamicFieldId }
        fieldConfig?.subFields?.let { subFields ->
            updateAddButtonState(buttonAdd, containerInstances, subFields)
        }
    }
    
    private fun updateAddButtonState(
        buttonAdd: MaterialButton,
        containerInstances: LinearLayout,
        subFields: List<FormFieldConfig>
    ) {
        if (containerInstances.isEmpty()) {
            buttonAdd.isEnabled = true
            return
        }
        
        // Check if last instance is non-empty
        val lastInstance = containerInstances.getChildAt(containerInstances.childCount - 1)
        val lastInstanceIndex = lastInstance?.tag as? Int ?: -1
        
        var hasNonEmptyField = false
        for (subFieldConfig in subFields) {
            val uniqueFieldId = formKeyForDynamicSubField(containerInstances.tag as String, lastInstanceIndex, subFieldConfig.id)
            val subFieldView = fieldViews[uniqueFieldId]
            if (subFieldView != null && isSubFieldNonEmpty(subFieldView, subFieldConfig)) {
                hasNonEmptyField = true
                break
            }
        }
        
        buttonAdd.isEnabled = hasNonEmptyField
    }
    
    private fun isSubFieldNonEmpty(subFieldView: View, subFieldConfig: FormFieldConfig): Boolean {
        return when (subFieldConfig.type) {
            FormFieldConfig.FieldType.TEXT,
            FormFieldConfig.FieldType.TEXTAREA,
            FormFieldConfig.FieldType.DATE,
            FormFieldConfig.FieldType.TIME,
            FormFieldConfig.FieldType.SELECT,
            FormFieldConfig.FieldType.BARCODE -> {
                val editText = subFieldView.findViewById<TextInputEditText>(R.id.editText)
                editText?.text?.toString()?.trim()?.isNotEmpty() ?: false
            }
            FormFieldConfig.FieldType.CHECKBOX -> {
                val checkbox = subFieldView.findViewById<CheckBox>(R.id.checkbox)
                checkbox?.isChecked ?: false
            }
            FormFieldConfig.FieldType.GPS -> {
                val editTextLatitude = subFieldView.findViewById<TextInputEditText>(R.id.editTextLatitude)
                val editTextLongitude = subFieldView.findViewById<TextInputEditText>(R.id.editTextLongitude)
                val latStr = editTextLatitude?.text?.toString()?.trim() ?: ""
                val lonStr = editTextLongitude?.text?.toString()?.trim() ?: ""
                latStr.isNotEmpty() && lonStr.isNotEmpty() && 
                    latStr.toDoubleOrNull() != null && lonStr.toDoubleOrNull() != null
            }
            FormFieldConfig.FieldType.MULTISELECT -> {
                val uniqueFieldId = subFieldView.tag as? String
                val fieldValue = uniqueFieldId?.let { fieldValues[it] }
                fieldValue?.values?.isNotEmpty() ?: false
            }
            FormFieldConfig.FieldType.PHOTO -> {
                val uniqueFieldId = subFieldView.tag as? String
                val fieldValue = uniqueFieldId?.let { fieldValues[it] }
                fieldValue?.photoFileName?.isNotEmpty() ?: false
            }
            else -> false
        }
    }
    
    private fun updateDeleteButtonStates(containerInstances: LinearLayout) {
        val instanceCount = containerInstances.childCount
        for (i in 0 until instanceCount) {
            val instanceView = containerInstances.getChildAt(i)
            val buttonDelete = instanceView?.findViewById<MaterialButton>(R.id.buttonDelete)
            buttonDelete?.isEnabled = instanceCount > 1
        }
    }
    
    private fun updateInstanceNumbers(containerInstances: LinearLayout) {
        for (i in 0 until containerInstances.childCount) {
            val instanceView = containerInstances.getChildAt(i)
            val textInstanceNumber = instanceView?.findViewById<TextView>(R.id.textInstanceNumber)
            // Get instance name from the dynamic field config
            val dynamicFieldId = containerInstances.tag as? String
            val instanceName = if (dynamicFieldId != null) {
                formConfig.fields.firstOrNull { it.id == dynamicFieldId }?.instanceName ?: "Instance"
            } else {
                "Instance"
            }
            textInstanceNumber?.text = getString(R.string.instance_number, instanceName, i + 1)
            instanceView?.tag = i
            
            // Update tags in sub-fields
            val containerSubFields = instanceView?.findViewById<LinearLayout>(R.id.containerSubFields)
            if (containerSubFields != null) {
                for (j in 0 until containerSubFields.childCount) {
                    val subFieldView = containerSubFields.getChildAt(j)
                    val oldUniqueFieldId = subFieldView.tag as? String
                    if (oldUniqueFieldId != null) {
                        val parsed = parseDynamicSubFieldFormKey(oldUniqueFieldId)
                        if (parsed != null) {
                            val (parsedDynamicFieldId, _, subFieldId) = parsed
                            val newUniqueFieldId = formKeyForDynamicSubField(parsedDynamicFieldId, i, subFieldId)
                            if (oldUniqueFieldId != newUniqueFieldId) {
                                    // Update fieldViews mapping
                                    fieldViews.remove(oldUniqueFieldId)
                                    fieldViews[newUniqueFieldId] = subFieldView
                                    subFieldView.tag = newUniqueFieldId
                                    
                                    // Update fieldValues mapping
                                    val oldValue = fieldValues.remove(oldUniqueFieldId)
                                    if (oldValue != null) {
                                        fieldValues[newUniqueFieldId] = oldValue.copy(fieldId = newUniqueFieldId)
                                    }
                                }
                            }
                    }
                }
            }
        }
    }
    
    private fun startBarcodeScanning() {
        // For now, use a simple approach: take a photo and scan it
        // In a full implementation, you'd use CameraX with ML Kit overlay
        currentPhotoFieldId = "temp_barcode_scan"
        checkCameraPermissionAndCaptureForBarcode()
    }
    
    private fun checkCameraPermissionAndCaptureForBarcode() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                capturePhotoForBarcode()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    private fun capturePhotoForBarcode() {
        try {
            val photoDir = File(getExternalFilesDir(null), "photos")
            if (!photoDir.exists()) {
                photoDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(photoDir, "BARCODE_${timestamp}.jpg")
            this.photoFile = photoFile
            
            val photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            
            // Launch custom camera activity with back camera
            val intent = Intent(this, CameraActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_OUTPUT_URI, photoUri)
                putExtra(CameraActivity.EXTRA_OUTPUT_FILE_PATH, photoFile.absolutePath)
            }
            cameraActivityLauncher.launch(intent)
            // After photo is taken, we'll process it in the launcher callback
        } catch (e: Exception) {
            Toast.makeText(this, "Error capturing photo for barcode: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun processBarcodeFromPhoto(photoFile: File) {
        lifecycleScope.launch {
            try {
                val imageUri = FileProvider.getUriForFile(
                    this@FormEditActivity,
                    "${packageName}.fileprovider",
                    photoFile
                )
                val image = InputImage.fromFilePath(this@FormEditActivity, imageUri)
                val task = barcodeScanner.process(image)
                val barcodes = withContext(Dispatchers.IO) {
                    Tasks.await(task)
                }
                if (isDestroyed || isFinishing) return@launch
                if (barcodes != null && barcodes.isNotEmpty()) {
                    val barcode = barcodes[0]
                    val barcodeValue = when {
                        barcode.rawValue != null -> barcode.rawValue!!
                        barcode.displayValue != null -> barcode.displayValue!!
                        else -> "Unknown"
                    }
                    
                    if (currentBarcodeFieldId != null) {
                    fieldValues[currentBarcodeFieldId!!] = FormFieldValue(
                        currentBarcodeFieldId!!,
                        value = barcodeValue
                    )
                    updateBarcodeFieldView(currentBarcodeFieldId!!, barcodeValue)
                    markFormChanged()
                        Toast.makeText(this@FormEditActivity, "Barcode scanned: $barcodeValue", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (!isDestroyed && !isFinishing) {
                        Toast.makeText(this@FormEditActivity, "No barcode found in image", Toast.LENGTH_SHORT).show()
                    }
                }
                currentBarcodeFieldId = null
                currentPhotoFieldId = null
            } catch (e: Exception) {
                android.util.Log.e("FormEditActivity", "Error processing barcode", e)
                if (!isDestroyed && !isFinishing) {
                    Toast.makeText(this@FormEditActivity, "Error scanning barcode: ${e.message}", Toast.LENGTH_LONG).show()
                }
                currentBarcodeFieldId = null
                currentPhotoFieldId = null
            }
        }
    }
    
    private fun updateBarcodeFieldView(fieldId: String, barcodeValue: String) {
        val fieldView = fieldViews[fieldId] ?: return
        val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
        editText?.setText(barcodeValue)
        // Move cursor to end
        editText?.setSelection(barcodeValue.length)
    }
    
    private fun showDatePickerForSubField(fieldId: String, editText: TextInputEditText) {
        val calendar = Calendar.getInstance()
        // If there's an existing value, parse it
        val existingValue = fieldValues[fieldId]?.value
        if (existingValue != null) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = dateFormat.parse(existingValue)
                if (date != null) {
                    calendar.time = date
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
        
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedDate = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val dateString = dateFormat.format(selectedDate.time)
                editText.setText(dateString)
                fieldValues[fieldId] = FormFieldValue(fieldId, value = dateString)
                markFormChanged()
                dynamicFieldIdFromFormKey(fieldId)?.let { updateAddButtonForDynamicField(it) }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    
    private fun showTimePickerForSubField(fieldId: String, editText: TextInputEditText) {
        val calendar = Calendar.getInstance()
        val existingValue = fieldValues[fieldId]?.value
        if (existingValue != null) {
            try {
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val time = timeFormat.parse(existingValue)
                if (time != null) {
                    calendar.time = time
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
        
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val timeString = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
                editText.setText(timeString)
                fieldValues[fieldId] = FormFieldValue(fieldId, value = timeString)
                markFormChanged()
                dynamicFieldIdFromFormKey(fieldId)?.let { updateAddButtonForDynamicField(it) }
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true // 24-hour format
        ).show()
    }
    
    private fun showDatePicker(fieldId: String, editText: TextInputEditText) {
        val calendar = Calendar.getInstance()
        // If there's an existing value, parse it
        val existingValue = fieldValues[fieldId]?.value
        if (existingValue != null) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = dateFormat.parse(existingValue)
                if (date != null) {
                    calendar.time = date
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
        
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedDate = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val dateString = dateFormat.format(selectedDate.time)
                editText.setText(dateString)
                fieldValues[fieldId] = FormFieldValue(fieldId, value = dateString)
                markFormChanged()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    
    private fun showTimePicker(fieldId: String, editText: TextInputEditText) {
        val calendar = Calendar.getInstance()
        val existingValue = fieldValues[fieldId]?.value
        if (existingValue != null) {
            try {
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val time = timeFormat.parse(existingValue)
                if (time != null) {
                    calendar.time = time
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
        
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val timeString = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
                editText.setText(timeString)
                fieldValues[fieldId] = FormFieldValue(fieldId, value = timeString)
                markFormChanged()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true // 24-hour format
        ).show()
    }
    
    private fun showSelectDialog(fieldConfig: FormFieldConfig, editText: TextInputEditText) {
        val options = fieldConfig.options ?: return
        
        AlertDialog.Builder(this)
            .setTitle(fieldConfig.label)
            .setItems(options.toTypedArray()) { _, which ->
                val selectedValue = options[which]
                editText.setText(selectedValue)
                fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = selectedValue)
                markFormChanged()
            }
            .show()
    }
    
    private fun showSelectDialogForSubField(fieldConfig: FormFieldConfig, editText: TextInputEditText, uniqueFieldId: String) {
        val options = fieldConfig.options ?: return
        
        AlertDialog.Builder(this)
            .setTitle(fieldConfig.label)
            .setItems(options.toTypedArray()) { _, which ->
                val selectedValue = options[which]
                editText.setText(selectedValue)
                fieldValues[uniqueFieldId] = FormFieldValue(uniqueFieldId, value = selectedValue)
                markFormChanged()
                dynamicFieldIdFromFormKey(uniqueFieldId)?.let { updateAddButtonForDynamicField(it) }
            }
            .show()
    }
    
    private fun showMultiSelectDialogForSubField(fieldConfig: FormFieldConfig, editText: TextInputEditText, uniqueFieldId: String) {
        val options = fieldConfig.options ?: return
        val currentValues = fieldValues[uniqueFieldId]?.values?.toMutableSet() ?: mutableSetOf()
        val checkedItems = BooleanArray(options.size) { i ->
            currentValues.contains(options[i])
        }
        
        AlertDialog.Builder(this)
            .setTitle(fieldConfig.label)
            .setMultiChoiceItems(
                options.toTypedArray(),
                checkedItems
            ) { _, which, isChecked ->
                if (isChecked) {
                    currentValues.add(options[which])
                } else {
                    currentValues.remove(options[which])
                }
            }
            .setPositiveButton("OK") { _, _ ->
                if (currentValues.isNotEmpty()) {
                    editText.setText(currentValues.joinToString(", "))
                    fieldValues[uniqueFieldId] = FormFieldValue(uniqueFieldId, values = currentValues.toList())
                    markFormChanged()
                    dynamicFieldIdFromFormKey(uniqueFieldId)?.let { updateAddButtonForDynamicField(it) }
                } else {
                    editText.setText("")
                    fieldValues.remove(uniqueFieldId)
                    markFormChanged()
                    dynamicFieldIdFromFormKey(uniqueFieldId)?.let { updateAddButtonForDynamicField(it) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Normalize a masked value by adding leading zeros to each section
     * e.g., "1:32" -> "01:32", "11:1" -> "11:01", "1:2:3" -> "01:02:03"
     */
    private fun normalizeMaskedValue(value: String, mask: String): String {
        if (!value.contains(':')) return value
        
        // Parse mask to get section lengths (e.g., "mm:ss" -> [2, 2], "HH:mm:ss" -> [2, 2, 2])
        val sectionLengths = mutableListOf<Int>()
        var currentSectionLength = 0
        for (char in mask) {
            if (char == ':') {
                if (currentSectionLength > 0) {
                    sectionLengths.add(currentSectionLength)
                    currentSectionLength = 0
                }
            } else {
                currentSectionLength++
            }
        }
        if (currentSectionLength > 0) {
            sectionLengths.add(currentSectionLength)
        }
        
        val valueParts = value.split(':')
        val normalizedParts = mutableListOf<String>()
        
        for (i in sectionLengths.indices) {
            val sectionLength = sectionLengths[i]
            val valuePart = valueParts.getOrNull(i) ?: ""
            val digits = valuePart.filter { it.isDigit() }
            
            // Pad with leading zeros
            val normalized = digits.padStart(sectionLength, '0')
            normalizedParts.add(normalized)
        }
        
        return normalizedParts.joinToString(":")
    }
    
    /**
     * Helper function to format digits according to mask
     */
    private fun formatMaskedValue(digits: String, mask: String): String {
        // Parse mask to get section lengths
        val sections = mutableListOf<Int>()
        var currentSectionLength = 0
        for (char in mask) {
            if (char == ':') {
                if (currentSectionLength > 0) {
                    sections.add(currentSectionLength)
                    currentSectionLength = 0
                }
            } else {
                currentSectionLength++
            }
        }
        if (currentSectionLength > 0) {
            sections.add(currentSectionLength)
        }
        
        // Build formatted string
        val result = StringBuilder()
        var digitIndex = 0
        var currentSectionIndex = 0
        var currentSectionDigitCount = 0
        
        for (char in mask) {
            if (char == ':') {
                result.append(':')
                currentSectionIndex++
                currentSectionDigitCount = 0
            } else {
                val sectionLength = sections.getOrNull(currentSectionIndex) ?: 0
                if (digitIndex < digits.length && currentSectionDigitCount < sectionLength) {
                    result.append(digits[digitIndex])
                    digitIndex++
                    currentSectionDigitCount++
                } else {
                    result.append(' ')
                }
            }
        }
        
        return result.toString()
    }
    
    private fun setFieldValue(fieldView: View, fieldConfig: FormFieldConfig, fieldValue: FormFieldValue) {
        when (fieldConfig.type) {
            FormFieldConfig.FieldType.TEXT,
            FormFieldConfig.FieldType.TEXTAREA,
            FormFieldConfig.FieldType.DATE,
            FormFieldConfig.FieldType.TIME -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                val value = fieldValue.value ?: ""
                // For masked fields, format the value according to mask if needed
                if (fieldConfig.mask != null && value.isNotEmpty()) {
                    val mask = fieldConfig.mask
                    // Value might be normalized (e.g., "01:32") or not (e.g., "1:32")
                    // Extract digits preserving colon positions
                    val cleanValue = value.filter { it.isDigit() || it == ':' }
                    
                    if (cleanValue.contains(':')) {
                        // Value has colons, format it to match mask structure
                        val digits = cleanValue.filter { it.isDigit() }
                        val formatted = formatMaskedValue(digits, mask)
                        editText?.setText(formatted)
                        // Ensure cursor is at end or first empty position
                        val cursorPos = if (formatted.any { it == ' ' }) {
                            formatted.indexOfFirst { it == ' ' }.takeIf { it >= 0 } ?: formatted.length
                        } else {
                            formatted.length
                        }
                        editText?.setSelection(cursorPos)
                    } else {
                        // No colons, just digits - format from scratch
                        val digits = cleanValue
                        if (digits.isNotEmpty()) {
                            val formatted = formatMaskedValue(digits, mask)
                            editText?.setText(formatted)
                            val cursorPos = if (formatted.any { it == ' ' }) {
                                formatted.indexOfFirst { it == ' ' }.takeIf { it >= 0 } ?: formatted.length
                            } else {
                                formatted.length
                            }
                            editText?.setSelection(cursorPos)
                        } else {
                            editText?.setText("")
                        }
                    }
                } else {
                    editText?.setText(value)
                }
            }
            FormFieldConfig.FieldType.SELECT -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                editText?.setText(fieldValue.value)
            }
            FormFieldConfig.FieldType.MULTISELECT -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                if (fieldValue.values != null && fieldValue.values.isNotEmpty()) {
                    editText?.setText(fieldValue.values.joinToString(", "))
                } else {
                    editText?.setText("")
                }
            }
            FormFieldConfig.FieldType.CHECKBOX -> {
                val checkbox = fieldView.findViewById<CheckBox>(R.id.checkbox)
                checkbox?.isChecked = fieldValue.value?.lowercase() == "true"
            }
            FormFieldConfig.FieldType.BARCODE -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                editText?.setText(fieldValue.value)
            }
            FormFieldConfig.FieldType.GPS -> {
                val editTextLatitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLatitude)
                val editTextLongitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLongitude)
                if (fieldValue.gpsLatitude != null) {
                    editTextLatitude?.setText(fieldValue.gpsLatitude.toString())
                }
                if (fieldValue.gpsLongitude != null) {
                    editTextLongitude?.setText(fieldValue.gpsLongitude.toString())
                }
            }
            FormFieldConfig.FieldType.DYNAMIC -> {
                // Dynamic fields are handled during creation, not here
                // The data is already loaded when createDynamicField is called
            }
            else -> {
                // Handle other types later
            }
        }
    }
    
    /**
     * Applies default value to a field view based on field configuration
     * Only called for new forms (when existingFormData is null)
     */
    private fun applyDefaultValue(fieldView: View, fieldConfig: FormFieldConfig) {
        val defaultValue = fieldConfig.defaultValue ?: return
        
        try {
            when (fieldConfig.type) {
                FormFieldConfig.FieldType.TEXT,
                FormFieldConfig.FieldType.TEXTAREA -> {
                    val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                    editText?.setText(defaultValue)
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = defaultValue)
                }
                
                FormFieldConfig.FieldType.CHECKBOX -> {
                    val checkbox = fieldView.findViewById<CheckBox>(R.id.checkbox)
                    val isChecked = defaultValue.lowercase() == "true"
                    checkbox?.isChecked = isChecked
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = if (isChecked) "true" else "false")
                }
                
                FormFieldConfig.FieldType.SELECT -> {
                    // Validate that default value is in options
                    val options = fieldConfig.options
                    if (options != null && options.contains(defaultValue)) {
                        val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                        editText?.setText(defaultValue)
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = defaultValue)
                    } else {
                        android.util.Log.w("FormEditActivity", "Default value '$defaultValue' not found in options for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.MULTISELECT -> {
                    // Parse comma-separated values or JSON array
                    val options = fieldConfig.options ?: return
                    val defaultValues = parseMultiSelectDefault(defaultValue)
                    // Validate all values are in options
                    val validValues = defaultValues.filter { options.contains(it) }
                    if (validValues.isNotEmpty()) {
                        val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                        editText?.setText(validValues.joinToString(", "))
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, values = validValues)
                    } else {
                        android.util.Log.w("FormEditActivity", "None of the default values found in options for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.SELECT_IMAGE -> {
                    // Validate that default value is in imageOptions
                    val imageOptions = fieldConfig.imageOptions
                    if (imageOptions != null && imageOptions.any { it.value == defaultValue }) {
                        val recyclerView = fieldView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewOptions)
                        val adapter = recyclerView?.adapter as? ImageOptionAdapter
                        adapter?.setSelectedValue(defaultValue)
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = defaultValue)
                    } else {
                        android.util.Log.w("FormEditActivity", "Default value '$defaultValue' not found in imageOptions for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.MULTISELECT_IMAGE -> {
                    // Parse comma-separated values
                    val imageOptions = fieldConfig.imageOptions ?: return
                    val defaultValues = parseMultiSelectDefault(defaultValue)
                    // Validate all values are in imageOptions
                    val validValues = defaultValues.filter { imageOptions.any { opt -> opt.value == it } }
                    if (validValues.isNotEmpty()) {
                        val recyclerView = fieldView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewOptions)
                        val adapter = recyclerView?.adapter as? ImageOptionAdapter
                        adapter?.setSelectedValues(validValues.toSet())
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, values = validValues)
                    } else {
                        android.util.Log.w("FormEditActivity", "None of the default values found in imageOptions for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.DATE -> {
                    val dateValue = if (defaultValue.lowercase() == "now") {
                        // Get current date in yyyy-MM-dd format
                        val calendar = Calendar.getInstance()
                        String.format(Locale.getDefault(), "%04d-%02d-%02d", calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH) + 1,
                            calendar.get(Calendar.DAY_OF_MONTH))
                    } else {
                        defaultValue
                    }
                    val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                    editText?.setText(dateValue)
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = dateValue)
                }
                
                FormFieldConfig.FieldType.TIME -> {
                    val timeValue = if (defaultValue.lowercase() == "now") {
                        // Get current time in HH:mm format
                        val calendar = Calendar.getInstance()
                        String.format(Locale.getDefault(), "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE))
                    } else {
                        defaultValue
                    }
                    val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                    editText?.setText(timeValue)
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = timeValue)
                }
                
                else -> {
                    // Default values not supported for this field type
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error applying default value for field ${fieldConfig.id}: ${e.message}", e)
        }
    }
    
    /**
     * Applies a prefill value to a field view
     * Similar to applyDefaultValue but uses the provided value directly
     */
    private fun applyPrefillValue(fieldView: View, fieldConfig: FormFieldConfig, prefillValue: String) {
        try {
            when (fieldConfig.type) {
                FormFieldConfig.FieldType.TEXT,
                FormFieldConfig.FieldType.TEXTAREA -> {
                    val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                    editText?.setText(prefillValue)
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = prefillValue)
                }
                
                FormFieldConfig.FieldType.CHECKBOX -> {
                    val checkbox = fieldView.findViewById<CheckBox>(R.id.checkbox)
                    val isChecked = prefillValue.lowercase() == "true"
                    checkbox?.isChecked = isChecked
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = if (isChecked) "true" else "false")
                }
                
                FormFieldConfig.FieldType.SELECT -> {
                    // Validate that prefill value is in options
                    val options = fieldConfig.options
                    if (options != null && options.contains(prefillValue)) {
                        val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                        editText?.setText(prefillValue)
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = prefillValue)
                    } else {
                        android.util.Log.w("FormEditActivity", "Prefill value '$prefillValue' not found in options for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.MULTISELECT -> {
                    // Parse comma-separated values or JSON array
                    val options = fieldConfig.options ?: return
                    val prefillValues = parseMultiSelectDefault(prefillValue)
                    // Validate all values are in options
                    val validValues = prefillValues.filter { options.contains(it) }
                    if (validValues.isNotEmpty()) {
                        val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                        editText?.setText(validValues.joinToString(", "))
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, values = validValues)
                    } else {
                        android.util.Log.w("FormEditActivity", "None of the prefill values found in options for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.SELECT_IMAGE -> {
                    // Validate that prefill value is in imageOptions
                    val imageOptions = fieldConfig.imageOptions
                    if (imageOptions != null && imageOptions.any { it.value == prefillValue }) {
                        val recyclerView = fieldView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewOptions)
                        val adapter = recyclerView?.adapter as? ImageOptionAdapter
                        adapter?.setSelectedValue(prefillValue)
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = prefillValue)
                    } else {
                        android.util.Log.w("FormEditActivity", "Prefill value '$prefillValue' not found in imageOptions for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.MULTISELECT_IMAGE -> {
                    // Parse comma-separated values
                    val imageOptions = fieldConfig.imageOptions ?: return
                    val prefillValues = parseMultiSelectDefault(prefillValue)
                    // Validate all values are in imageOptions
                    val validValues = prefillValues.filter { imageOptions.any { opt -> opt.value == it } }
                    if (validValues.isNotEmpty()) {
                        val recyclerView = fieldView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewOptions)
                        val adapter = recyclerView?.adapter as? ImageOptionAdapter
                        adapter?.setSelectedValues(validValues.toSet())
                        fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, values = validValues)
                    } else {
                        android.util.Log.w("FormEditActivity", "None of the prefill values found in imageOptions for field ${fieldConfig.id}")
                    }
                }
                
                FormFieldConfig.FieldType.DATE -> {
                    val dateValue = if (prefillValue.lowercase() == "now") {
                        // Get current date in yyyy-MM-dd format
                        val calendar = Calendar.getInstance()
                        String.format(Locale.getDefault(), "%04d-%02d-%02d", calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH) + 1,
                            calendar.get(Calendar.DAY_OF_MONTH))
                    } else {
                        prefillValue
                    }
                    val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                    editText?.setText(dateValue)
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = dateValue)
                }
                
                FormFieldConfig.FieldType.TIME -> {
                    val timeValue = if (prefillValue.lowercase() == "now") {
                        // Get current time in HH:mm format
                        val calendar = Calendar.getInstance()
                        String.format(Locale.getDefault(), "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE))
                    } else {
                        prefillValue
                    }
                    val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                    editText?.setText(timeValue)
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = timeValue)
                }
                
                else -> {
                    // Prefills not supported for this field type
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error applying prefill value for field ${fieldConfig.id}: ${e.message}", e)
        }
    }
    
    /**
     * Parses default value for multiselect fields
     * Supports comma-separated values or JSON array format
     */
    private fun parseMultiSelectDefault(defaultValue: String): List<String> {
        return try {
            // Try parsing as JSON array first
            val jsonArray = org.json.JSONArray(defaultValue)
            (0 until jsonArray.length()).mapNotNull { jsonArray.optString(it).takeIf { it.isNotEmpty() } }
        } catch (e: Exception) {
            // If not JSON, treat as comma-separated values
            defaultValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    
    private fun collectFieldValues(): List<FormFieldValue> {
        val values = mutableListOf<FormFieldValue>()
        
        for ((fieldId, fieldView) in fieldViews) {
            val fieldConfig = formConfig.fields.firstOrNull { it.id == fieldId } ?: continue
            
            when (fieldConfig.type) {
                FormFieldConfig.FieldType.TEXT,
                FormFieldConfig.FieldType.TEXTAREA,
                FormFieldConfig.FieldType.DATE,
                FormFieldConfig.FieldType.TIME -> {
                    val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                    val rawValue = editText?.text?.toString() ?: ""
                    // For masked fields, normalize the value (add leading zeros) before saving
                    val value = if (fieldConfig.mask != null) {
                        if (rawValue.any { it.isDigit() }) {
                            // Extract the actual value (remove spaces, keep colons and digits)
                            val cleanValue = rawValue.filter { it.isDigit() || it == ':' }
                            if (cleanValue.contains(':')) {
                                // Normalize by adding leading zeros
                                normalizeMaskedValue(cleanValue, fieldConfig.mask)
                            } else {
                                // No colons yet, just save digits (will be formatted on load)
                                cleanValue
                            }
                        } else {
                            "" // Empty if no digits (just placeholder)
                        }
                    } else {
                        rawValue.trim() // For non-masked fields, trim as usual
                    }
                    if (value.isNotEmpty()) {
                        values.add(FormFieldValue(fieldId, value = value))
                    }
                }
                FormFieldConfig.FieldType.CHECKBOX -> {
                    val checkbox = fieldView.findViewById<CheckBox>(R.id.checkbox)
                    val isChecked = checkbox?.isChecked ?: false
                    values.add(FormFieldValue(fieldId, value = if (isChecked) "true" else "false"))
                }
            FormFieldConfig.FieldType.SELECT -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                val value = editText?.text?.toString()?.trim() ?: ""
                if (value.isNotEmpty()) {
                    values.add(FormFieldValue(fieldId, value = value))
                }
            }
            FormFieldConfig.FieldType.MULTISELECT -> {
                // Values are already stored in fieldValues when user selects
                fieldValues[fieldId]?.let { values.add(it) }
            }
            FormFieldConfig.FieldType.GPS -> {
                // Read GPS values from text fields
                val editTextLatitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLatitude)
                val editTextLongitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLongitude)
                val latStr = editTextLatitude?.text?.toString()?.trim() ?: ""
                val lonStr = editTextLongitude?.text?.toString()?.trim() ?: ""
                val latitude = latStr.toDoubleOrNull()
                val longitude = lonStr.toDoubleOrNull()
                if (latitude != null && longitude != null) {
                    values.add(FormFieldValue(fieldId, gpsLatitude = latitude, gpsLongitude = longitude))
                }
            }
            FormFieldConfig.FieldType.PHOTO -> {
                // Photo filename is stored when photo is captured
                fieldValues[fieldId]?.let { values.add(it) }
            }
            FormFieldConfig.FieldType.BARCODE -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                val value = editText?.text?.toString()?.trim() ?: ""
                if (value.isNotEmpty()) {
                    values.add(FormFieldValue(fieldId, value = value))
                }
            }
            FormFieldConfig.FieldType.TABLE -> {
                // Collect table data from all cells
                val tableData = mutableMapOf<String, MutableMap<String, String>>()
                val tableLayout = fieldView.findViewById<TableLayout>(R.id.tableLayout)
                
                // Iterate through table rows (skip header row at index 0)
                for (i in 1 until tableLayout.childCount) {
                    val row = tableLayout.getChildAt(i) as? TableRow ?: continue
                    // First child is row header, rest are data cells
                    val rowHeader = row.getChildAt(0) as? TextView
                    val rowName = rowHeader?.text?.toString() ?: continue
                    
                    val rowData = mutableMapOf<String, String>()
                    // Get column names from header row
                    val headerRow = tableLayout.getChildAt(0) as? TableRow
                    val columnNames = mutableListOf<String>()
                    if (headerRow != null) {
                        for (j in 1 until headerRow.childCount) {
                            val colHeader = headerRow.getChildAt(j) as? TextView
                            colHeader?.text?.toString()?.let { columnNames.add(it) }
                        }
                    }
                    
                    // Collect cell values
                    for (j in 1 until row.childCount) {
                        val cell = row.getChildAt(j)
                        val columnIndex = j - 1
                        if (columnIndex < columnNames.size) {
                            val columnName = columnNames[columnIndex]
                            val cellValue = when (cell) {
                                is LinearLayout -> {
                                    // Check if this is a checkbox container
                                    val checkbox = cell.getChildAt(0) as? CheckBox
                                    if (checkbox != null) {
                                        if (checkbox.isChecked) "true" else "false"
                                    } else {
                                        ""
                                    }
                                }
                                is CheckBox -> {
                                    if (cell.isChecked) "true" else "false"
                                }
                                is TextInputEditText -> {
                                    cell.text?.toString()?.trim() ?: ""
                                }
                                else -> ""
                            }
                            if (cellValue.isNotEmpty()) {
                                rowData[columnName] = cellValue
                            }
                        }
                    }
                    
                    if (rowData.isNotEmpty()) {
                        tableData[rowName] = rowData
                    }
                }
                
                if (tableData.isNotEmpty()) {
                    values.add(FormFieldValue(fieldId, tableData = tableData))
                }
            }
            FormFieldConfig.FieldType.DYNAMIC -> {
                // Collect dynamic data from all instances
                val containerInstances = fieldView.findViewById<LinearLayout>(R.id.containerInstances)
                val dynamicInstances = mutableListOf<Map<String, FormFieldValue>>()
                
                for (i in 0 until containerInstances.childCount) {
                    val instanceView = containerInstances.getChildAt(i)
                    val containerSubFields = instanceView.findViewById<LinearLayout>(R.id.containerSubFields)
                    if (containerSubFields != null) {
                        val instanceData = mutableMapOf<String, FormFieldValue>()
                        val dynamicFieldConfig = formConfig.fields.firstOrNull { it.id == fieldId }
                        val subFields = dynamicFieldConfig?.subFields ?: emptyList()
                        
                        for (subFieldConfig in subFields) {
                            val uniqueFieldId = formKeyForDynamicSubField(fieldId, i, subFieldConfig.id)
                            val subFieldView = fieldViews[uniqueFieldId]
                            
                            val subFieldValue = when (subFieldConfig.type) {
                                FormFieldConfig.FieldType.GPS -> {
                                    // Read GPS values from text fields
                                    val editTextLatitude = subFieldView?.findViewById<TextInputEditText>(R.id.editTextLatitude)
                                    val editTextLongitude = subFieldView?.findViewById<TextInputEditText>(R.id.editTextLongitude)
                                    val latStr = editTextLatitude?.text?.toString()?.trim() ?: ""
                                    val lonStr = editTextLongitude?.text?.toString()?.trim() ?: ""
                                    val latitude = latStr.toDoubleOrNull()
                                    val longitude = lonStr.toDoubleOrNull()
                                    if (latitude != null && longitude != null) {
                                        FormFieldValue(uniqueFieldId, gpsLatitude = latitude, gpsLongitude = longitude)
                                    } else {
                                        null
                                    }
                                }
                                else -> {
                                    // For other types, use value from fieldValues
                                    fieldValues[uniqueFieldId]
                                }
                            }
                            
                            if (subFieldValue != null) {
                                // Create a copy with the original sub-field ID
                                instanceData[subFieldConfig.id] = subFieldValue.copy(fieldId = subFieldConfig.id)
                            }
                        }
                        
                        if (instanceData.isNotEmpty()) {
                            dynamicInstances.add(instanceData)
                        }
                    }
                }
                
                if (dynamicInstances.isNotEmpty()) {
                    values.add(FormFieldValue(fieldId, dynamicData = dynamicInstances))
                }
            }
            else -> {
                // Use existing value if available
                fieldValues[fieldId]?.let { values.add(it) }
            }
            }
        }
        
        return values
    }
    
    private fun validateForm(): Boolean {
        // Handle display-only fields before the loop: validate only fields that accept input
        val fieldsToValidate = formConfig.fields.filter {
            it.type != FormFieldConfig.FieldType.SECTION && it.type != FormFieldConfig.FieldType.IMAGE_DISPLAY
        }
        for (fieldConfig in fieldsToValidate) {
            if (fieldConfig.required) {
                val fieldValue = fieldValues[fieldConfig.id]
                
                when (fieldConfig.type) {
                    FormFieldConfig.FieldType.TEXT,
                    FormFieldConfig.FieldType.TEXTAREA,
                    FormFieldConfig.FieldType.DATE,
                    FormFieldConfig.FieldType.TIME,
                    FormFieldConfig.FieldType.SELECT,
                    FormFieldConfig.FieldType.SELECT_IMAGE,
                    FormFieldConfig.FieldType.BARCODE -> {
                        val value = fieldValue?.value?.trim()
                        if (value.isNullOrEmpty()) {
                            Toast.makeText(
                                this,
                                "${fieldConfig.label} is required",
                                Toast.LENGTH_SHORT
                            ).show()
                            return false
                        }
                    }
                    FormFieldConfig.FieldType.CHECKBOX -> {
                        // For required checkbox, it must be checked (value == "true")
                        val isChecked = fieldValue?.value?.lowercase() == "true"
                        if (!isChecked) {
                            Toast.makeText(
                                this,
                                "${fieldConfig.label} is required",
                                Toast.LENGTH_SHORT
                            ).show()
                            return false
                        }
                    }
                    FormFieldConfig.FieldType.MULTISELECT,
                    FormFieldConfig.FieldType.MULTISELECT_IMAGE -> {
                        val values = fieldValue?.values
                        if (values.isNullOrEmpty()) {
                            Toast.makeText(
                                this,
                                "${fieldConfig.label} is required",
                                Toast.LENGTH_SHORT
                            ).show()
                            return false
                        }
                    }
                    FormFieldConfig.FieldType.GPS -> {
                        // Read GPS values from text fields
                        val fieldView = fieldViews[fieldConfig.id] ?: return false
                        val editTextLatitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLatitude)
                        val editTextLongitude = fieldView.findViewById<TextInputEditText>(R.id.editTextLongitude)
                        val latStr = editTextLatitude?.text?.toString()?.trim() ?: ""
                        val lonStr = editTextLongitude?.text?.toString()?.trim() ?: ""
                        val latitude = latStr.toDoubleOrNull()
                        val longitude = lonStr.toDoubleOrNull()
                        if (latitude == null || longitude == null) {
                            Toast.makeText(
                                this,
                                "${fieldConfig.label} is required",
                                Toast.LENGTH_SHORT
                            ).show()
                            return false
                        }
                    }
                    FormFieldConfig.FieldType.PHOTO -> {
                        if (fieldValue?.photoFileName.isNullOrEmpty()) {
                            Toast.makeText(
                                this,
                                "${fieldConfig.label} is required",
                                Toast.LENGTH_SHORT
                            ).show()
                            return false
                        }
                    }
                    FormFieldConfig.FieldType.TABLE -> {
                        // For tables, check if at least one cell has a value if required
                        val tableData = fieldValue?.tableData
                        if (tableData == null || tableData.isEmpty() || 
                            tableData.values.all { rowData -> rowData.isEmpty() }) {
                            Toast.makeText(
                                this,
                                "${fieldConfig.label} is required",
                                Toast.LENGTH_SHORT
                            ).show()
                            return false
                        }
                    }
                    FormFieldConfig.FieldType.DYNAMIC -> {
                        // Dynamic widget data lives in sub-field keys (fieldId_instance0_subFieldId), not in fieldValues[fieldConfig.id].
                        // Collect current instances from the UI (same as collectFieldValues) so we validate what's on screen.
                        val fieldView = fieldViews[fieldConfig.id] ?: run {
                            Toast.makeText(this, "${fieldConfig.label} requires at least one instance", Toast.LENGTH_SHORT).show()
                            return false
                        }
                        val containerInstances = fieldView.findViewById<LinearLayout>(R.id.containerInstances)
                        val dynamicData = mutableListOf<Map<String, FormFieldValue>>()
                        for (i in 0 until containerInstances.childCount) {
                            val instanceView = containerInstances.getChildAt(i)
                            val containerSubFields = instanceView.findViewById<LinearLayout>(R.id.containerSubFields)
                            if (containerSubFields != null) {
                                val instanceData = mutableMapOf<String, FormFieldValue>()
                                val subFields = fieldConfig.subFields ?: emptyList()
                                for (subFieldConfig in subFields) {
                                    val uniqueFieldId = formKeyForDynamicSubField(fieldConfig.id, i, subFieldConfig.id)
                                    val subFieldView = fieldViews[uniqueFieldId]
                                    val subFieldValue = when (subFieldConfig.type) {
                                        FormFieldConfig.FieldType.GPS -> {
                                            val editTextLatitude = subFieldView?.findViewById<TextInputEditText>(R.id.editTextLatitude)
                                            val editTextLongitude = subFieldView?.findViewById<TextInputEditText>(R.id.editTextLongitude)
                                            val latStr = editTextLatitude?.text?.toString()?.trim() ?: ""
                                            val lonStr = editTextLongitude?.text?.toString()?.trim() ?: ""
                                            val latitude = latStr.toDoubleOrNull()
                                            val longitude = lonStr.toDoubleOrNull()
                                            if (latitude != null && longitude != null) {
                                                FormFieldValue(uniqueFieldId, gpsLatitude = latitude, gpsLongitude = longitude)
                                            } else null
                                        }
                                        else -> fieldValues[uniqueFieldId]
                                    }
                                    if (subFieldValue != null) {
                                        instanceData[subFieldConfig.id] = subFieldValue.copy(fieldId = subFieldConfig.id)
                                    }
                                }
                                if (instanceData.isNotEmpty()) {
                                    dynamicData.add(instanceData)
                                }
                            }
                        }
                        if (dynamicData.isEmpty()) {
                            Toast.makeText(
                                this,
                                "${fieldConfig.label} requires at least one instance",
                                Toast.LENGTH_SHORT
                            ).show()
                            return false
                        }
                        // Check mandatory sub-fields in each instance
                        val subFields = fieldConfig.subFields ?: emptyList()
                        for ((instanceIndex, instanceData) in dynamicData.withIndex()) {
                            for (subFieldConfig in subFields) {
                                if (subFieldConfig.required) {
                                    val subFieldValue = instanceData[subFieldConfig.id]
                                    val isEmpty = when (subFieldConfig.type) {
                                        FormFieldConfig.FieldType.TEXT,
                                        FormFieldConfig.FieldType.TEXTAREA,
                                        FormFieldConfig.FieldType.DATE,
                                        FormFieldConfig.FieldType.TIME,
                                        FormFieldConfig.FieldType.SELECT,
                                        FormFieldConfig.FieldType.BARCODE -> {
                                            subFieldValue?.value?.trim()?.isEmpty() ?: true
                                        }
                                        FormFieldConfig.FieldType.CHECKBOX -> {
                                            subFieldValue?.value?.lowercase() != "true"
                                        }
                                        FormFieldConfig.FieldType.GPS -> {
                                            subFieldValue?.gpsLatitude == null || subFieldValue.gpsLongitude == null
                                        }
                                        FormFieldConfig.FieldType.MULTISELECT -> {
                                            subFieldValue?.values.isNullOrEmpty()
                                        }
                                        FormFieldConfig.FieldType.PHOTO -> {
                                            subFieldValue?.photoFileName.isNullOrEmpty()
                                        }
                                        else -> false
                                    }
                                    if (isEmpty) {
                                        val instanceName = fieldConfig.instanceName ?: "Instance"
                                        Toast.makeText(
                                            this,
                                            "${fieldConfig.label} - $instanceName ${instanceIndex + 1}: ${subFieldConfig.label} is required",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return false
                                    }
                                }
                            }
                        }
                    }
                    FormFieldConfig.FieldType.SECTION,
                    FormFieldConfig.FieldType.IMAGE_DISPLAY -> {
                        // Section headers and image displays are display-only and don't need validation
                        // This case should never be reached due to the continue statement above,
                        // but included for exhaustiveness
                    }
                }
            }
        }
        return true
    }
    
    private fun showSubmitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Submit Form")
            .setMessage("Are you sure you want to submit this form? You won't be able to edit it after submission.")
            .setPositiveButton("Submit") { _, _ ->
                saveForm(isDraft = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveForm(isDraft: Boolean) {
        lifecycleScope.launch {
            // Collect current field values from UI
            val currentValues = collectFieldValues()
            
            // Identify which fields are dynamic widgets (to exclude their sub-fields)
            val dynamicFieldIds = formConfig.fields
                .filter { it.type == FormFieldConfig.FieldType.DYNAMIC }
                .map { it.id }
                .toSet()
            
            // Merge with existing values (for fields like GPS, photo, barcode that might not be in UI yet)
            // But exclude sub-field values from dynamic widgets (they're already included in dynamicData)
            val allValues = mutableMapOf<String, FormFieldValue>()
            
            // First, add all current values (these include properly structured dynamic data)
            currentValues.forEach { allValues[it.fieldId] = it }
            
            // Then, add other field values that aren't sub-fields of dynamic widgets
            // (sub-fields have IDs like "${dynamicFieldId}_instance${i}_${subFieldId}")
            fieldValues.forEach { (id, value) ->
                // Check if this is a sub-field of a dynamic widget
                val isSubField = dynamicFieldIds.any { dynamicFieldId ->
                    id.startsWith("${dynamicFieldId}_instance")
                }
                
                if (!isSubField) {
                    // Only add if not already in currentValues (currentValues takes precedence)
                    if (!allValues.containsKey(id)) {
                        allValues[id] = value
                    }
                }
            }
            
            // Preserve createdAt from existing data, or set to current time if new form
            // Check both draft and submitted versions to find the earliest createdAt
            // Always check both draft and submitted versions to find the best createdAt
            val existingDraft = formFileHelper.loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = true)
            val existingSubmitted = formFileHelper.loadFormData(siteName, formId, orderInSection, subIndex, loadDraft = false)
            
            // Priority: submitted version's createdAt > draft's createdAt > new timestamp
            // (submitted version's createdAt is the original creation time if it exists)
            val createdAt = existingSubmitted?.createdAt
                ?: existingDraft?.createdAt
                ?: FormData.getCurrentTimestamp()
            
            // Get logsheet version from existing data, or get latest for new submissions
            // Note: saveFormData will also set the version, but we need it here for FormData constructor
            val logsheetVersion: String? = existingSubmitted?.logsheetVersion
                ?: existingDraft?.logsheetVersion
                ?: run {
                    val downloader = LogsheetDownloader(this@FormEditActivity)
                    downloader.getLatestLogsheetVersion(formId)
                }
            
            if (logsheetVersion == null || logsheetVersion.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FormEditActivity, "Error: Could not determine logsheet version", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            
            val finalLogsheetVersion = logsheetVersion
            
            val formData = FormData(
                formId = formId,
                siteName = siteName,
                isSubmitted = !isDraft,
                createdAt = createdAt,
                submittedAt = if (!isDraft) FormData.getCurrentTimestamp() else null,
                logsheetVersion = finalLogsheetVersion,
                fieldValues = allValues.values.toList()
            )
            
            val success = formFileHelper.saveFormData(formData, orderInSection, subIndex)
            
            if (success) {
                // Update initial state to match current state
                initialFieldValues.clear()
                initialFieldValues.putAll(allValues)
                isFormSaved = true
                
                if (isDraft) {
                    AppLogger.i("FormEditActivity", "Draft saved: site=$siteName, form=$formId")
                    Toast.makeText(this@FormEditActivity, "Draft saved", Toast.LENGTH_SHORT).show()
                } else {
                    AppLogger.i("FormEditActivity", "Form submitted: site=$siteName, form=$formId")
                    Toast.makeText(this@FormEditActivity, "Form submitted", Toast.LENGTH_SHORT).show()
                    // Mark as completed in database
                    val database = AppDatabase.getDatabase(this@FormEditActivity)
                    database.formCompletionDao().insertCompletion(
                        FormCompletion(
                            siteName = siteName,
                            formId = formId,
                            completedAt = System.currentTimeMillis()
                        )
                    )
                    finish()
                }
            } else {
                AppLogger.e("FormEditActivity", "Failed to save form: site=$siteName, form=$formId, isDraft=$isDraft")
                Toast.makeText(
                    this@FormEditActivity,
                    "Error saving form",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

/**
 * Adapter for displaying image-based options in a RecyclerView
 */
private class ImageOptionAdapter(
    private val imageOptions: List<ImageOption>,
    private val isMultiSelect: Boolean,
    private val onSelectionChanged: (List<String>) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ImageOptionAdapter.ImageOptionViewHolder>() {
    
    private var selectedValues = mutableSetOf<String>()
    
    fun setSelectedValue(value: String) {
        selectedValues.clear()
        selectedValues.add(value)
        notifyDataSetChanged()
    }
    
    fun setSelectedValues(values: Set<String>) {
        selectedValues.clear()
        selectedValues.addAll(values)
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageOptionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_option, parent, false)
        return ImageOptionViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ImageOptionViewHolder, position: Int) {
        val option = imageOptions[position]
        val isSelected = selectedValues.contains(option.value)
        holder.bind(option, isSelected) {
            if (isMultiSelect) {
                if (isSelected) {
                    selectedValues.remove(option.value)
                } else {
                    selectedValues.add(option.value)
                }
                notifyItemChanged(position)
                onSelectionChanged(selectedValues.toList())
            } else {
                selectedValues.clear()
                selectedValues.add(option.value)
                notifyDataSetChanged()
                onSelectionChanged(listOf(option.value))
            }
        }
    }
    
    override fun getItemCount(): Int = imageOptions.size
    
    inner class ImageOptionViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageViewOption)
        private val textLabel: TextView = itemView.findViewById(R.id.textOptionLabel)
        private val cardView: com.google.android.material.card.MaterialCardView = itemView as com.google.android.material.card.MaterialCardView
        
        fun bind(option: ImageOption, isSelected: Boolean, onClick: () -> Unit) {
            // Load image from downloaded files
            try {
                val downloader = LogsheetDownloader(itemView.context)
                val imageFile = downloader.getImageFile(option.imagePath)
                
                if (imageFile != null && imageFile.exists()) {
                    // Load from downloaded file
                    val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (bitmap != null) {
                        // Calculate optimal size maintaining aspect ratio
                        val density = itemView.context.resources.displayMetrics.density
                        
                        // Calculate available width from grid (3 columns)
                        // Account for: card margin (4dp each side = 8dp), padding (8dp each side = 16dp)
                        val cardMarginDp = 8f
                        val paddingDp = 16f
                        val totalHorizontalSpaceDp = cardMarginDp + paddingDp
                        val totalHorizontalSpacePx = (totalHorizontalSpaceDp * density).toInt()
                        
                        // Get parent RecyclerView width, fallback to screen width / 3
                        val parentRecyclerView = itemView.parent?.parent as? androidx.recyclerview.widget.RecyclerView
                        val availableWidthPx = if (parentRecyclerView != null && parentRecyclerView.width > 0) {
                            (parentRecyclerView.width / 3) - totalHorizontalSpacePx
                        } else {
                            // Fallback: use screen width / 3
                            val screenWidthPx = itemView.context.resources.displayMetrics.widthPixels
                            (screenWidthPx / 3) - totalHorizontalSpacePx
                        }.coerceAtLeast((100 * density).toInt()) // Minimum 100dp width
                        
                        // Account for label space below image
                        val willHaveLabel = option.label != null && option.label.isNotEmpty()
                        val labelSpaceDp = if (willHaveLabel) {
                            val textSizeSp = 12f
                            val lineHeightDp = textSizeSp * 1.2f
                            val twoLinesHeightDp = lineHeightDp * 2f
                            twoLinesHeightDp + 4f // + marginTop
                        } else {
                            0f
                        }
                        val labelSpacePx = (labelSpaceDp * density).toInt()
                        
                        // Max height constraint (120dp minus label space)
                        val maxHeightDp = 120f
                        val maxHeightPx = (maxHeightDp * density).toInt()
                        val maxImageHeightPx = maxHeightPx - labelSpacePx
                        
                        val imageWidth = bitmap.width
                        val imageHeight = bitmap.height
                        val aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
                        
                        // Calculate dimensions that fit within available space while maintaining aspect ratio
                        val targetWidth: Int
                        val targetHeight: Int
                        
                        if (imageWidth >= imageHeight) {
                            // Landscape or square: use available width, but ensure height doesn't exceed max
                            val widthBasedHeight = (availableWidthPx / aspectRatio).toInt()
                            
                            if (widthBasedHeight <= maxImageHeightPx) {
                                // Width-based scaling fits within height constraint
                                // Use full available width (or original if smaller)
                                targetWidth = availableWidthPx.coerceAtMost(imageWidth)
                                targetHeight = if (imageWidth > availableWidthPx) {
                                    widthBasedHeight
                                } else {
                                    imageHeight
                                }
                            } else {
                                // Height constraint is limiting, scale based on height
                                targetHeight = maxImageHeightPx
                                targetWidth = (maxImageHeightPx * aspectRatio).toInt().coerceAtMost(availableWidthPx)
                            }
                        } else {
                            // Portrait: scale based on height, but ensure width fits
                            val minImageHeightPx = (60 * density).toInt()
                            val effectiveMaxHeight = maxImageHeightPx.coerceAtLeast(minImageHeightPx)
                            
                            if (imageHeight > effectiveMaxHeight) {
                                targetHeight = effectiveMaxHeight
                                targetWidth = (effectiveMaxHeight * aspectRatio).toInt()
                            } else {
                                // Image is smaller than max, use original size (but ensure it fits width)
                                targetWidth = imageWidth.coerceAtMost(availableWidthPx)
                                targetHeight = if (imageWidth > availableWidthPx) {
                                    (availableWidthPx / aspectRatio).toInt()
                                } else {
                                    imageHeight
                                }
                            }
                        }
                        
                        // Set the calculated dimensions
                        val layoutParams = imageView.layoutParams
                        if (layoutParams != null) {
                            layoutParams.width = targetWidth
                            layoutParams.height = targetHeight
                            imageView.layoutParams = layoutParams
                        }
                        
                imageView.setImageBitmap(bitmap)
                    } else {
                        // Reset to default size for placeholder
                        val layoutParams = imageView.layoutParams
                        if (layoutParams != null) {
                            val defaultSize = (80 * itemView.context.resources.displayMetrics.density).toInt()
                            layoutParams.width = defaultSize
                            layoutParams.height = defaultSize
                            imageView.layoutParams = layoutParams
                        }
                        imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                } else {
                    android.util.Log.w("ImageOptionAdapter", "Image not found: ${option.imagePath}")
                    // Reset to default size for placeholder
                    val layoutParams = imageView.layoutParams
                    if (layoutParams != null) {
                        val defaultSize = (80 * itemView.context.resources.displayMetrics.density).toInt()
                        layoutParams.width = defaultSize
                        layoutParams.height = defaultSize
                        imageView.layoutParams = layoutParams
                    }
                    // Set a placeholder or error icon
                    imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageOptionAdapter", "Error loading image ${option.imagePath}: ${e.message}", e)
                // Reset to default size for placeholder
                val layoutParams = imageView.layoutParams
                if (layoutParams != null) {
                    val defaultSize = (80 * itemView.context.resources.displayMetrics.density).toInt()
                    layoutParams.width = defaultSize
                    layoutParams.height = defaultSize
                    imageView.layoutParams = layoutParams
                }
                // Set a placeholder or error icon
                imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            }
            
            // Set label if provided
            if (option.label != null) {
                textLabel.text = option.label
                textLabel.visibility = View.VISIBLE
            } else {
                textLabel.visibility = View.GONE
            }
            
            // Update selection state
            if (isSelected) {
                cardView.strokeColor = itemView.context.getColor(android.R.color.holo_blue_dark)
                cardView.strokeWidth = 4
                cardView.setCardBackgroundColor(0xFFE3F2FD.toInt()) // Light blue background
            } else {
                cardView.strokeColor = itemView.context.getColor(android.R.color.transparent)
                cardView.strokeWidth = 2
                cardView.setCardBackgroundColor(0xFFFFFFFF.toInt()) // White background
            }
            
            itemView.setOnClickListener { onClick() }
        }
    }
}
