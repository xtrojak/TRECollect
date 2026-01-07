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
    
    private lateinit var containerFields: LinearLayout
    private lateinit var textFormName: TextView
    private lateinit var textFormDescription: TextView
    private lateinit var buttonSaveDraft: MaterialButton
    private lateinit var buttonSubmit: MaterialButton
    
    private val fieldViews = mutableMapOf<String, View>()
    private val fieldValues = mutableMapOf<String, FormFieldValue>()
    private var currentPhotoFieldId: String? = null
    private var photoFile: File? = null
    private var currentBarcodeFieldId: String? = null
    private val barcodeScanner = BarcodeScanning.getClient()
    
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
    
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = formConfig.name
        toolbar.setNavigationOnClickListener {
            finish()
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
        
        buttonSaveDraft.setOnClickListener {
            saveForm(isDraft = true)
        }
        
        buttonSubmit.setOnClickListener {
            if (validateForm()) {
                showSubmitConfirmation()
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
                    fieldViews[fieldConfig.id] = fieldView
                    
                    // Load existing value if available
                    val existingValue = fieldValues[fieldConfig.id]
                    if (existingValue != null) {
                        setFieldValue(fieldView, fieldConfig, existingValue)
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
        
        // Update fieldValues as user types
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s?.toString()?.trim() ?: ""
                if (value.isNotEmpty()) {
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = value)
                } else {
                    fieldValues.remove(fieldConfig.id)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
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
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
        // Update fieldValues as user types
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s?.toString()?.trim() ?: ""
                if (value.isNotEmpty()) {
                    fieldValues[fieldConfig.id] = FormFieldValue(fieldConfig.id, value = value)
                } else {
                    fieldValues.remove(fieldConfig.id)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
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
        editText.isFocusable = false
        editText.isClickable = true
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
        editText.setOnClickListener {
            showDatePicker(fieldConfig.id, editText)
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
        editText.isFocusable = false
        editText.isClickable = true
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
        editText.setOnClickListener {
            showTimePicker(fieldConfig.id, editText)
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
        editText.isFocusable = false
        editText.isClickable = true
        
        if (fieldConfig.required) {
            textInputLayout.hint = "${fieldConfig.label} *"
        }
        
        textInputLayout.tag = fieldConfig.id
        
        editText.setOnClickListener {
            showSelectDialog(fieldConfig, editText)
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
            
            textSelected.setOnClickListener {
                showMultiSelectDialog(fieldConfig, textSelected)
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
                } else {
                    textView.text = "Tap to select options"
                    fieldValues.remove(fieldConfig.id)
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
        
        buttonGetLocation.setOnClickListener {
            openGPSPicker(fieldConfig.id)
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
        
        buttonCapture.setOnClickListener {
            currentPhotoFieldId = fieldConfig.id
            checkCameraPermissionAndCapture()
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
        
        buttonScan.setOnClickListener {
            currentBarcodeFieldId = fieldConfig.id
            startBarcodeScanning()
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

