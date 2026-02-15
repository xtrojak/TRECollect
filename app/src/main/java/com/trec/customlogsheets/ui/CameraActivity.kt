package com.trec.customlogsheets.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.trec.customlogsheets.R
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var outputFile: File? = null
    private var outputFileUri: Uri? = null
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isDestroyed || isFinishing) return@registerForActivityResult
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Get output file path and URI from intent
        val filePath = intent.getStringExtra(EXTRA_OUTPUT_FILE_PATH)
        outputFileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_OUTPUT_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_OUTPUT_URI)
        }
        
        if (filePath != null) {
            outputFile = File(filePath)
            // Ensure parent directory exists
            outputFile?.parentFile?.mkdirs()
        }
        
        val captureButton: FloatingActionButton = findViewById(R.id.captureButton)
        val cancelButton: FloatingActionButton = findViewById(R.id.cancelButton)
        
        captureButton.setOnClickListener {
            takePhoto()
        }
        
        cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        
        // Check camera permission
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            if (isDestroyed || isFinishing) return@addListener
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = findViewById<PreviewView>(R.id.previewView).surfaceProvider
                }
            
            // Set up ImageCapture with back camera by default
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: 0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            imageCapture = ImageCapture.Builder()
                .setTargetRotation(rotation)
                .build()
            
            // Select back camera (CameraSelector.LENS_FACING_BACK)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Error starting camera: ${exc.message}", Toast.LENGTH_LONG).show()
                android.util.Log.e("CameraActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val outputFile = outputFile ?: run {
            Toast.makeText(this, "Output file not specified", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create output file options using the file directly
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        
        // Set up image capture listener
        imageCapture.takePicture(
            outputFileOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        Toast.makeText(this@CameraActivity, "Error capturing photo: ${exception.message}", Toast.LENGTH_LONG).show()
                    }
                    android.util.Log.e("CameraActivity", "Photo capture failed", exception)
                }
                
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        val resultIntent = Intent().apply {
                            outputFileUri?.let { putExtra(EXTRA_OUTPUT_URI, it) }
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                }
            }
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    
    companion object {
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_OUTPUT_FILE_PATH = "output_file_path"
    }
}
