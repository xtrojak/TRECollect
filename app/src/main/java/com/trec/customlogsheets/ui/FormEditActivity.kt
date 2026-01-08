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
        
        val textLabel = container.findViewById<TextView>(R.id.textLabel)
        val buttonScan = container.findViewById<MaterialButton>(R.id.buttonScan)
        val textBarcodeValue = container.findViewById<TextView>(R.id.textBarcodeValue)
        
        textLabel.text = if (fieldConfig.required) {
            "${fieldConfig.label} *"
        } else {
            fieldConfig.label
        }
        
        container.tag = fieldConfig.id
        
        // Load existing barcode value
        val existingValue = fieldValues[fieldConfig.id]
        if (existingValue?.value != null) {
            textBarcodeValue.text = "Barcode: ${existingValue.value}"
            textBarcodeValue.visibility = View.VISIBLE
        }
        
        if (!isReadOnly) {
            buttonScan.setOnClickListener {
                currentBarcodeFieldId = fieldConfig.id
                startBarcodeScanning()
            }
        } else {
            buttonScan.isEnabled = false
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
                        // Mark form as changed when text changes
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: android.text.Editable?) {
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
        val textBarcodeValue = fieldView.findViewById<TextView>(R.id.textBarcodeValue)
        textBarcodeValue?.text = "Barcode: $barcodeValue"
        textBarcodeValue?.visibility = View.VISIBLE
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
                // Barcode value is stored when scanned
                fieldValues[fieldId]?.let { values.add(it) }
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
            
            // Merge with existing values (for fields like GPS, photo, barcode that might not be in UI yet)
            val allValues = mutableMapOf<String, FormFieldValue>()
            fieldValues.forEach { (id, value) -> allValues[id] = value }
            currentValues.forEach { allValues[it.fieldId] = it }
            
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

