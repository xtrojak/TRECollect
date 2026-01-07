package com.trec.customlogsheets.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.trec.customlogsheets.R
import com.trec.customlogsheets.data.*
import kotlinx.coroutines.launch
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
        formConfig = PredefinedForms.getFormConfig(this, formId) ?: run {
            Toast.makeText(this, "Form configuration not found", Toast.LENGTH_SHORT).show()
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
        
        for (fieldConfig in formConfig.fields) {
            val fieldView = createFieldView(fieldConfig)
            containerFields.addView(fieldView)
            fieldViews[fieldConfig.id] = fieldView
            
            // Load existing value if available
            val existingValue = fieldValues[fieldConfig.id]
            if (existingValue != null) {
                setFieldValue(fieldView, fieldConfig, existingValue)
            }
        }
    }
    
    private fun createFieldView(fieldConfig: FormFieldConfig): View {
        return when (fieldConfig.type) {
            FormFieldConfig.FieldType.TEXT -> createTextField(fieldConfig)
            FormFieldConfig.FieldType.TEXTAREA -> createTextAreaField(fieldConfig)
            FormFieldConfig.FieldType.DATE -> createDateField(fieldConfig)
            FormFieldConfig.FieldType.TIME -> createTimeField(fieldConfig)
            FormFieldConfig.FieldType.SELECT -> createSelectField(fieldConfig)
            FormFieldConfig.FieldType.MULTISELECT -> createMultiSelectField(fieldConfig)
            FormFieldConfig.FieldType.GPS -> createGPSField(fieldConfig) // Placeholder for now
            FormFieldConfig.FieldType.PHOTO -> createPhotoField(fieldConfig) // Placeholder for now
            FormFieldConfig.FieldType.BARCODE -> createBarcodeField(fieldConfig) // Placeholder for now
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
        // For now, use same as select - will enhance later
        return createSelectField(fieldConfig)
    }
    
    private fun createGPSField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = "${fieldConfig.label} (GPS - Coming soon)"
        editText.isFocusable = false
        editText.isClickable = true
        
        textInputLayout.tag = fieldConfig.id
        
        editText.setOnClickListener {
            Toast.makeText(this, "GPS picker coming soon", Toast.LENGTH_SHORT).show()
        }
        
        return textInputLayout
    }
    
    private fun createPhotoField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = "${fieldConfig.label} (Photo - Coming soon)"
        editText.isFocusable = false
        editText.isClickable = true
        
        textInputLayout.tag = fieldConfig.id
        
        editText.setOnClickListener {
            Toast.makeText(this, "Photo capture coming soon", Toast.LENGTH_SHORT).show()
        }
        
        return textInputLayout
    }
    
    private fun createBarcodeField(fieldConfig: FormFieldConfig): View {
        val inflater = LayoutInflater.from(this)
        val textInputLayout = inflater.inflate(
            R.layout.field_text_input,
            containerFields,
            false
        ) as TextInputLayout
        
        val editText = textInputLayout.findViewById<TextInputEditText>(R.id.editText)
        editText.hint = "${fieldConfig.label} (Barcode - Coming soon)"
        editText.isFocusable = false
        editText.isClickable = true
        
        textInputLayout.tag = fieldConfig.id
        
        editText.setOnClickListener {
            Toast.makeText(this, "Barcode scanner coming soon", Toast.LENGTH_SHORT).show()
        }
        
        return textInputLayout
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

