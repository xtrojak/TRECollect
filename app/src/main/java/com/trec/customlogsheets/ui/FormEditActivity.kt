package com.trec.customlogsheets.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Tasks
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.*
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
    private val barcodeScanner = BarcodeScanning.getClient()
    private var isFormSaved = false // Track if form was just saved
    
    // Helper to mark form as changed
    private fun markFormChanged() {
        isFormSaved = false
    }
    
    // Activity result launchers
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoFile != null) {
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
        
        if (requestCode == REQUEST_CODE_GPS_PICKER && resultCode == RESULT_OK) {
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
    
    private fun updateGPSFieldView(fieldId: String, latitude: Double, longitude: Double) {
        val fieldView = fieldViews[fieldId] ?: return
        val textCoordinates = fieldView.findViewById<TextView>(R.id.textCoordinates)
        textCoordinates?.text = "Lat: $latitude, Lon: $longitude"
        textCoordinates?.visibility = View.VISIBLE
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
        
        isReadOnly = intent.getBooleanExtra("isReadOnly", false)
        
        formFileHelper = FormFileHelper(this)
        
        // Load form configuration
        try {
            formConfig = PredefinedForms.getFormConfig(this, formId) ?: run {
                android.util.Log.e("FormEditActivity", "Form config not found for formId: $formId")
                android.util.Log.d("FormEditActivity", "Available forms: ${PredefinedForms.getForms(this).map { it.id }}")
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
        renderFields()
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
        handleBackPress()
    }
    
    private fun handleBackPress() {
        if (hasUnsavedChanges()) {
            showSaveChangesDialog()
        } else {
            finish()
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
                // Delete both draft and submitted versions
                formFileHelper.deleteForm(siteName, formId, isDraft = true)
                formFileHelper.deleteForm(siteName, formId, isDraft = false)
                
                // Clear all field values
                fieldValues.clear()
                initialFieldValues.clear()
                
                // Re-render fields to show empty state
                renderFields()
                
                Toast.makeText(this@FormEditActivity, "Form cleared", Toast.LENGTH_SHORT).show()
                isFormSaved = true // Mark as saved since there are no changes now
            } catch (e: Exception) {
                android.util.Log.e("FormEditActivity", "Error clearing form: ${e.message}", e)
                Toast.makeText(this@FormEditActivity, "Error clearing form: ${e.message}", Toast.LENGTH_LONG).show()
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
    
    private fun loadExistingData() {
        // Try to load draft first, then submitted version
        val existingData = formFileHelper.loadFormData(siteName, formId, loadDraft = true)
            ?: formFileHelper.loadFormData(siteName, formId, loadDraft = false)
        
        if (existingData != null) {
            for (fieldValue in existingData.fieldValues) {
                fieldValues[fieldValue.fieldId] = fieldValue
                // Store initial state for comparison
                initialFieldValues[fieldValue.fieldId] = fieldValue
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
                    
                    // Section headers are display-only and don't need to be stored in fieldViews
                    if (fieldConfig.type != FormFieldConfig.FieldType.SECTION) {
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
                        text = "Error loading field: ${fieldConfig.label}"
                        setTextColor(android.graphics.Color.RED)
                    }
                    containerFields.addView(errorView)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error rendering fields: ${e.message}", e)
            Toast.makeText(this, "Error rendering form fields: ${e.message}", Toast.LENGTH_LONG).show()
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
                FormFieldConfig.FieldType.GPS -> createGPSField(fieldConfig)
                FormFieldConfig.FieldType.PHOTO -> createPhotoField(fieldConfig)
                FormFieldConfig.FieldType.BARCODE -> createBarcodeField(fieldConfig)
                FormFieldConfig.FieldType.SECTION -> createSectionHeader(fieldConfig)
                FormFieldConfig.FieldType.TABLE -> createTableField(fieldConfig)
                FormFieldConfig.FieldType.DYNAMIC -> createDynamicField(fieldConfig)
            }
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error creating field view for ${fieldConfig.id}: ${e.message}", e)
            // Return a simple error TextView as fallback
            TextView(this).apply {
                text = "Error: ${fieldConfig.label}"
                setTextColor(android.graphics.Color.RED)
            }
        }
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
    
    private fun createTextField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        if (fieldConfig.inputType == "number") {
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        // Store field ID in tag for retrieval
        textInputLayout.tag = fieldConfig.id
        
        // Disable editing if read-only
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
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
        }
        
        return textInputLayout
    }
    
    private fun createTextAreaField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        editText.minLines = 3
        editText.maxLines = 5
        
        // Make read-only if needed
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
        }
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
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
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
        // Disable editing if read-only
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
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
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
        // Disable editing if read-only
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
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
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
        // Disable editing if read-only
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
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
        try {
            val inflater = LayoutInflater.from(this)
            val container = inflater.inflate(
                R.layout.field_multiselect,
                containerFields,
                false
            ) as? LinearLayout ?: throw IllegalStateException("Failed to inflate field_multiselect layout")
            
            val textLabel = container.findViewById<TextView>(R.id.textLabel)
            val textSelected = container.findViewById<TextView>(R.id.textSelected)
            
            if (textLabel == null) {
                android.util.Log.e("FormEditActivity", "textLabel is null for ${fieldConfig.id}")
                throw IllegalStateException("textLabel view not found in multiselect layout")
            }
            if (textSelected == null) {
                android.util.Log.e("FormEditActivity", "textSelected is null for ${fieldConfig.id}")
                throw IllegalStateException("textSelected view not found in multiselect layout")
            }
            
            textLabel.text = if (fieldConfig.required) {
                "${fieldConfig.label} *"
            } else {
                fieldConfig.label
            }
            
            container.tag = fieldConfig.id
            
            // Load existing values
            val existingValue = fieldValues[fieldConfig.id]
            if (existingValue?.values != null && existingValue.values.isNotEmpty()) {
                textSelected.text = existingValue.values.joinToString(", ")
            }
            
        if (isReadOnly) {
            textSelected.isEnabled = false
            textSelected.isClickable = false
            textSelected.isFocusable = false
            textSelected.isFocusableInTouchMode = false
            textSelected.alpha = 0.6f // Make it visually appear disabled
        } else {
            textSelected.setOnClickListener {
                showMultiSelectDialog(fieldConfig, textSelected)
            }
        }
            
            return container
        } catch (e: Exception) {
            android.util.Log.e("FormEditActivity", "Error in createMultiSelectField for ${fieldConfig.id}: ${e.message}", e)
            e.printStackTrace()
            throw e
        }
    }
    
    private fun showMultiSelectDialog(fieldConfig: FormFieldConfig, textView: TextView) {
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
                    textView.text = currentValues.joinToString(", ")
                    fieldValues[fieldConfig.id] = FormFieldValue(
                        fieldConfig.id,
                        values = currentValues.toList()
                    )
                    markFormChanged()
                } else {
                    textView.text = "Tap to select options"
                    fieldValues.remove(fieldConfig.id)
                    markFormChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val textCoordinates = container.findViewById<TextView>(R.id.textCoordinates)
        
        textLabel.text = if (fieldConfig.required) {
            "${fieldConfig.label} *"
        } else {
            fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        // Load existing GPS coordinates
        val existingValue = fieldValues[fieldConfig.id]
        if (existingValue?.gpsLatitude != null && existingValue.gpsLongitude != null) {
            textCoordinates.text = "Lat: ${existingValue.gpsLatitude}, Lon: ${existingValue.gpsLongitude}"
            textCoordinates.visibility = View.VISIBLE
        }
        
        if (!isReadOnly) {
            buttonGetLocation.setOnClickListener {
                openGPSPicker(fieldConfig.id)
            }
        } else {
            buttonGetLocation.isEnabled = false
        }
        
        return container
    }
    
    private fun openGPSPicker(fieldId: String) {
        val intent = Intent(this, GPSPickerActivity::class.java).apply {
            putExtra("fieldId", fieldId)
            // Pass existing coordinates if available
            val existingValue = fieldValues[fieldId]
            if (existingValue?.gpsLatitude != null && existingValue.gpsLongitude != null) {
                putExtra("latitude", existingValue.gpsLatitude)
                putExtra("longitude", existingValue.gpsLongitude)
            }
        }
        startActivityForResult(intent, REQUEST_CODE_GPS_PICKER)
    }
    
    companion object {
        private const val REQUEST_CODE_GPS_PICKER = 2001
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
            textFileName.text = "Photo: ${existingValue.photoFileName}"
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
            
            takePictureLauncher.launch(photoUri)
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
        val fieldView = fieldViews[fieldId] ?: return
        val textFileName = fieldView.findViewById<TextView>(R.id.textFileName)
        textFileName?.text = "Photo: $fileName"
        textFileName?.visibility = View.VISIBLE
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
        
        // Make read-only if needed
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
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
        val tableLayout = container.findViewById<android.widget.TableLayout>(R.id.tableLayout)
        
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
        
        // Create header row
        val headerRow = android.widget.TableRow(this).apply {
            layoutParams = android.widget.TableLayout.LayoutParams(
                android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                android.widget.TableLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Add empty cell for row header column
        val emptyHeader = TextView(this).apply {
            text = ""
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFFE0E0E0.toInt())
            layoutParams = android.widget.TableRow.LayoutParams(
                0,
                android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerRow.addView(emptyHeader)
        
        // Add column headers
        for (column in columns) {
            val columnHeader = TextView(this).apply {
                text = column
                setPadding(8, 8, 8, 8)
                setBackgroundColor(0xFFE0E0E0.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.TableRow.LayoutParams(
                    0,
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            headerRow.addView(columnHeader)
        }
        tableLayout.addView(headerRow)
        
        // Create data rows
        for (row in rows) {
            val dataRow = android.widget.TableRow(this).apply {
                layoutParams = android.widget.TableLayout.LayoutParams(
                    android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                    android.widget.TableLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            // Row header
            val rowHeader = TextView(this).apply {
                text = row
                setPadding(8, 8, 8, 8)
                setBackgroundColor(0xFFF5F5F5.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.TableRow.LayoutParams(
                    0,
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            dataRow.addView(rowHeader)
            
            // Data cells
            for (column in columns) {
                val cellValue = existingTableData[row]?.get(column) ?: ""
                val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
                    hint = ""
                    setText(cellValue)
                    setPadding(8, 8, 8, 8)
                    setBackgroundColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.TableRow.LayoutParams(
                        0,
                        android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    
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
            tableLayout.addView(dataRow)
        }
        
        return container
    }
    
    /**
     * Helper method to collect table data from a TableLayout and update fieldValues
     */
    private fun updateTableFieldValue(fieldId: String, tableLayout: android.widget.TableLayout) {
        val tableData = mutableMapOf<String, MutableMap<String, String>>()
        
        // Iterate through table rows (skip header row at index 0)
        for (i in 1 until tableLayout.childCount) {
            val row = tableLayout.getChildAt(i) as? android.widget.TableRow ?: continue
            // First child is row header, rest are data cells
            val rowHeader = row.getChildAt(0) as? TextView
            val rowName = rowHeader?.text?.toString() ?: continue
            
            val rowData = mutableMapOf<String, String>()
            // Get column names from header row
            val headerRow = tableLayout.getChildAt(0) as? android.widget.TableRow
            val columnNames = mutableListOf<String>()
            if (headerRow != null) {
                for (j in 1 until headerRow.childCount) {
                    val colHeader = headerRow.getChildAt(j) as? TextView
                    colHeader?.text?.toString()?.let { columnNames.add(it) }
                }
            }
            
            // Collect cell values
            for (j in 1 until row.childCount) {
                val cell = row.getChildAt(j) as? com.google.android.material.textfield.TextInputEditText
                val cellValue = cell?.text?.toString()?.trim() ?: ""
                val columnIndex = j - 1
                if (columnIndex < columnNames.size) {
                    val columnName = columnNames[columnIndex]
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
        val instancesToCreate = if (existingInstances.isEmpty()) {
            listOf(emptyMap<String, FormFieldValue>())
        } else {
            existingInstances
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
            textInstanceNumber.text = "$instanceName ${instanceIndex + 1}"
            
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
                        isReadOnly
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
        isReadOnly: Boolean
    ): View {
        // Create a unique field ID for this sub-field in this instance
        val uniqueFieldId = "${dynamicFieldId}_instance${instanceIndex}_${subFieldConfig.id}"
        
        // Temporarily store the existing value with the unique ID
        if (existingValue != null) {
            fieldValues[uniqueFieldId] = existingValue.copy(fieldId = uniqueFieldId)
        }
        
        // Create the sub-field view using helper methods that accept a parent parameter
        val subFieldView = try {
            when (subFieldConfig.type) {
                FormFieldConfig.FieldType.TEXT -> createTextFieldForSubField(subFieldConfig, uniqueFieldId)
                FormFieldConfig.FieldType.TEXTAREA -> createTextAreaFieldForSubField(subFieldConfig, uniqueFieldId)
                FormFieldConfig.FieldType.DATE -> createDateFieldForSubField(subFieldConfig, uniqueFieldId)
                FormFieldConfig.FieldType.TIME -> createTimeFieldForSubField(subFieldConfig, uniqueFieldId)
                FormFieldConfig.FieldType.SELECT -> createSelectFieldForSubField(subFieldConfig, uniqueFieldId)
                FormFieldConfig.FieldType.MULTISELECT -> createMultiSelectFieldForSubField(subFieldConfig, uniqueFieldId)
                FormFieldConfig.FieldType.BARCODE -> createBarcodeFieldForSubField(subFieldConfig, uniqueFieldId)
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
    
    // Helper methods to create sub-fields for dynamic widgets (using null as parent)
    private fun createTextFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            null,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        if (fieldConfig.inputType == "number") {
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = uniqueFieldId
        
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
        }
        // Note: TextWatcher will be added in addSubFieldChangeListener
        
        return textInputLayout
    }
    
    private fun createTextAreaFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            null,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        editText.minLines = 3
        editText.maxLines = 5
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = uniqueFieldId
        
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
        }
        // Note: TextWatcher will be added in addSubFieldChangeListener
        
        return textInputLayout
    }
    
    private fun createDateFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            null,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        editText.isFocusable = false
        editText.isClickable = true
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = uniqueFieldId
        
        if (!isReadOnly) {
            editText.setOnClickListener {
                showDatePickerForSubField(uniqueFieldId, editText)
            }
        } else {
            editText.isEnabled = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
        }
        
        return textInputLayout
    }
    
    private fun createTimeFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            null,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        editText.isFocusable = false
        editText.isClickable = true
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = uniqueFieldId
        
        if (!isReadOnly) {
            editText.setOnClickListener {
                showTimePickerForSubField(uniqueFieldId, editText)
            }
        } else {
            editText.isEnabled = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
        }
        
        return textInputLayout
    }
    
    private fun createSelectFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            null,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = fieldConfig.label
        editText.isFocusable = false
        editText.isClickable = true
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = uniqueFieldId
        
        if (!isReadOnly) {
            editText.setOnClickListener {
                showSelectDialogForSubField(fieldConfig, editText, uniqueFieldId)
            }
        } else {
            editText.isEnabled = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
        }
        
        return textInputLayout
    }
    
    private fun createMultiSelectFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_multiselect,
            null,
            false
        ) as? LinearLayout ?: throw IllegalStateException("Failed to inflate field_multiselect layout")
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val textSelected = container.findViewById<TextView>(R.id.textSelected)
        
        if (fieldConfig.required) {
            textLabel.text = "${fieldConfig.label} *"
        } else {
            textLabel.text = fieldConfig.label
        }
        
        container.tag = uniqueFieldId
        
        if (!isReadOnly) {
            container.setOnClickListener {
                showMultiSelectDialogForSubField(fieldConfig, textSelected, uniqueFieldId)
            }
        } else {
            container.isClickable = false
        }
        
        return container
    }
    
    private fun createBarcodeFieldForSubField(fieldConfig: FormFieldConfig, uniqueFieldId: String): View {
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(
            R.layout.field_barcode,
            null,
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
        
        if (isReadOnly) {
            editText.isEnabled = false
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isClickable = false
            textInputLayout.isEnabled = false
            textInputLayout.isClickable = false
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
        instanceIndex: Int
    ) {
        when (subFieldConfig.type) {
            FormFieldConfig.FieldType.TEXT,
            FormFieldConfig.FieldType.TEXTAREA,
            FormFieldConfig.FieldType.DATE,
            FormFieldConfig.FieldType.TIME,
            FormFieldConfig.FieldType.SELECT,
            FormFieldConfig.FieldType.BARCODE -> {
                val editText = subFieldView.findViewById<TextInputEditText>(R.id.editText)
                editText?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val value = s?.toString()?.trim() ?: ""
                        if (value.isNotEmpty()) {
                            fieldValues[uniqueFieldId] = FormFieldValue(uniqueFieldId, value = value)
                        } else {
                            fieldValues.remove(uniqueFieldId)
                        }
                        markFormChanged()
                        updateAddButtonForDynamicField(dynamicFieldId)
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
        if (fieldConfig != null && fieldConfig.subFields != null) {
            updateAddButtonState(buttonAdd, containerInstances, fieldConfig.subFields!!)
        }
    }
    
    private fun updateAddButtonState(
        buttonAdd: MaterialButton,
        containerInstances: LinearLayout,
        subFields: List<FormFieldConfig>
    ) {
        if (containerInstances.childCount == 0) {
            buttonAdd.isEnabled = true
            return
        }
        
        // Check if last instance is non-empty
        val lastInstance = containerInstances.getChildAt(containerInstances.childCount - 1) as? View
        val lastInstanceIndex = lastInstance?.tag as? Int ?: -1
        
        var hasNonEmptyField = false
        for (subFieldConfig in subFields) {
            val uniqueFieldId = "${containerInstances.tag}_instance${lastInstanceIndex}_${subFieldConfig.id}"
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
            FormFieldConfig.FieldType.MULTISELECT -> {
                val uniqueFieldId = subFieldView.tag as? String
                val fieldValue = uniqueFieldId?.let { fieldValues[it] }
                fieldValue?.values?.isNotEmpty() ?: false
            }
            else -> false
        }
    }
    
    private fun updateDeleteButtonStates(containerInstances: LinearLayout) {
        val instanceCount = containerInstances.childCount
        for (i in 0 until instanceCount) {
            val instanceView = containerInstances.getChildAt(i) as? View
            val buttonDelete = instanceView?.findViewById<MaterialButton>(R.id.buttonDelete)
            buttonDelete?.isEnabled = instanceCount > 1
        }
    }
    
    private fun updateInstanceNumbers(containerInstances: LinearLayout) {
        for (i in 0 until containerInstances.childCount) {
            val instanceView = containerInstances.getChildAt(i) as? View
            val textInstanceNumber = instanceView?.findViewById<TextView>(R.id.textInstanceNumber)
            // Get instance name from the dynamic field config
            val dynamicFieldId = containerInstances.tag as? String
            val instanceName = if (dynamicFieldId != null) {
                formConfig.fields.firstOrNull { it.id == dynamicFieldId }?.instanceName ?: "Instance"
            } else {
                "Instance"
            }
            textInstanceNumber?.text = "$instanceName ${i + 1}"
            instanceView?.tag = i
            
            // Update tags in sub-fields
            val containerSubFields = instanceView?.findViewById<LinearLayout>(R.id.containerSubFields)
            if (containerSubFields != null) {
                for (j in 0 until containerSubFields.childCount) {
                    val subFieldView = containerSubFields.getChildAt(j)
                    val oldUniqueFieldId = subFieldView.tag as? String
                    if (oldUniqueFieldId != null) {
                        // Parse the uniqueFieldId to extract dynamicFieldId and subFieldId
                        // Format: "${dynamicFieldId}_instance${oldInstanceIndex}_${subFieldId}"
                        val parts = oldUniqueFieldId.split("_instance")
                        if (parts.size == 2) {
                            val dynamicFieldId = parts[0]
                            val rest = parts[1]
                            val subFieldParts = rest.split("_", limit = 2)
                            if (subFieldParts.size == 2) {
                                val subFieldId = subFieldParts[1]
                                val newUniqueFieldId = "${dynamicFieldId}_instance${i}_${subFieldId}"
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
            
            // Use a different launcher that processes the barcode
            takePictureLauncher.launch(photoUri)
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
                    Toast.makeText(this@FormEditActivity, "No barcode found in image", Toast.LENGTH_SHORT).show()
                }
                
                // Clean up
                currentBarcodeFieldId = null
                currentPhotoFieldId = null
            } catch (e: Exception) {
                Toast.makeText(this@FormEditActivity, "Error scanning barcode: ${e.message}", Toast.LENGTH_LONG).show()
                android.util.Log.e("FormEditActivity", "Error processing barcode", e)
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
                updateAddButtonForDynamicField(fieldId.substringBefore("_instance"))
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
                val timeString = String.format("%02d:%02d", hourOfDay, minute)
                editText.setText(timeString)
                fieldValues[fieldId] = FormFieldValue(fieldId, value = timeString)
                markFormChanged()
                updateAddButtonForDynamicField(fieldId.substringBefore("_instance"))
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
                val timeString = String.format("%02d:%02d", hourOfDay, minute)
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
                updateAddButtonForDynamicField(uniqueFieldId.substringBefore("_instance"))
            }
            .show()
    }
    
    private fun showMultiSelectDialogForSubField(fieldConfig: FormFieldConfig, textView: TextView, uniqueFieldId: String) {
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
                    textView.text = currentValues.joinToString(", ")
                    fieldValues[uniqueFieldId] = FormFieldValue(uniqueFieldId, values = currentValues.toList())
                    markFormChanged()
                    updateAddButtonForDynamicField(uniqueFieldId.substringBefore("_instance"))
                } else {
                    textView.text = "Tap to select options"
                    fieldValues.remove(uniqueFieldId)
                    markFormChanged()
                    updateAddButtonForDynamicField(uniqueFieldId.substringBefore("_instance"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setFieldValue(fieldView: View, fieldConfig: FormFieldConfig, fieldValue: FormFieldValue) {
        when (fieldConfig.type) {
            FormFieldConfig.FieldType.TEXT,
            FormFieldConfig.FieldType.TEXTAREA,
            FormFieldConfig.FieldType.DATE,
            FormFieldConfig.FieldType.TIME -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                editText?.setText(fieldValue.value)
            }
            FormFieldConfig.FieldType.SELECT -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                editText?.setText(fieldValue.value)
            }
            FormFieldConfig.FieldType.BARCODE -> {
                val editText = fieldView.findViewById<TextInputEditText>(R.id.editText)
                editText?.setText(fieldValue.value)
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
                    val value = editText?.text?.toString()?.trim() ?: ""
                    if (value.isNotEmpty()) {
                        values.add(FormFieldValue(fieldId, value = value))
                    }
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
                // GPS values are stored when user selects location
                fieldValues[fieldId]?.let { values.add(it) }
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
                val tableLayout = fieldView.findViewById<android.widget.TableLayout>(R.id.tableLayout)
                
                // Iterate through table rows (skip header row at index 0)
                for (i in 1 until tableLayout.childCount) {
                    val row = tableLayout.getChildAt(i) as? android.widget.TableRow ?: continue
                    // First child is row header, rest are data cells
                    val rowHeader = row.getChildAt(0) as? TextView
                    val rowName = rowHeader?.text?.toString() ?: continue
                    
                    val rowData = mutableMapOf<String, String>()
                    // Get column names from header row
                    val headerRow = tableLayout.getChildAt(0) as? android.widget.TableRow
                    val columnNames = mutableListOf<String>()
                    if (headerRow != null) {
                        for (j in 1 until headerRow.childCount) {
                            val colHeader = headerRow.getChildAt(j) as? TextView
                            colHeader?.text?.toString()?.let { columnNames.add(it) }
                        }
                    }
                    
                    // Collect cell values
                    for (j in 1 until row.childCount) {
                        val cell = row.getChildAt(j) as? com.google.android.material.textfield.TextInputEditText
                        val cellValue = cell?.text?.toString()?.trim() ?: ""
                        val columnIndex = j - 1
                        if (columnIndex < columnNames.size) {
                            val columnName = columnNames[columnIndex]
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
                    val instanceView = containerInstances.getChildAt(i) as? View
                    val containerSubFields = instanceView?.findViewById<LinearLayout>(R.id.containerSubFields)
                    if (containerSubFields != null) {
                        val instanceData = mutableMapOf<String, FormFieldValue>()
                        val fieldConfig = formConfig.fields.firstOrNull { it.id == fieldId }
                        val subFields = fieldConfig?.subFields ?: emptyList()
                        
                        for (subFieldConfig in subFields) {
                            val uniqueFieldId = "${fieldId}_instance${i}_${subFieldConfig.id}"
                            val subFieldValue = fieldValues[uniqueFieldId]
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
        for (fieldConfig in formConfig.fields) {
            // Skip section headers in validation
            if (fieldConfig.type == FormFieldConfig.FieldType.SECTION) {
                continue
            }
            
            if (fieldConfig.required) {
                val fieldValue = fieldValues[fieldConfig.id]
                
                when (fieldConfig.type) {
                    FormFieldConfig.FieldType.TEXT,
                    FormFieldConfig.FieldType.TEXTAREA,
                    FormFieldConfig.FieldType.DATE,
                    FormFieldConfig.FieldType.TIME,
                    FormFieldConfig.FieldType.SELECT,
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
                    FormFieldConfig.FieldType.MULTISELECT -> {
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
                        if (fieldValue?.gpsLatitude == null || fieldValue.gpsLongitude == null) {
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
                        // For dynamic widgets, check if at least one instance exists and all mandatory sub-fields are filled
                        val dynamicData = fieldValue?.dynamicData
                        if (dynamicData == null || dynamicData.isEmpty()) {
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
                                        FormFieldConfig.FieldType.MULTISELECT -> {
                                            subFieldValue?.values.isNullOrEmpty()
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
                    FormFieldConfig.FieldType.SECTION -> {
                        // Section headers are display-only and don't need validation
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
            
            val formData = FormData(
                formId = formId,
                siteName = siteName,
                isSubmitted = !isDraft,
                submittedAt = if (!isDraft) System.currentTimeMillis() else null,
                fieldValues = allValues.values.toList()
            )
            
            val success = formFileHelper.saveFormData(formData, isDraft = isDraft)
            
            if (success) {
                // Update initial state to match current state
                initialFieldValues.clear()
                initialFieldValues.putAll(allValues)
                isFormSaved = true
                
                if (isDraft) {
                    Toast.makeText(this@FormEditActivity, "Draft saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FormEditActivity, "Form submitted", Toast.LENGTH_SHORT).show()
                    // Mark as completed in database
                    val database = com.trec.customlogsheets.data.AppDatabase.getDatabase(this@FormEditActivity)
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
                Toast.makeText(
                    this@FormEditActivity,
                    "Error saving form",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

