package com.example.openeer.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.openeer.R
import com.example.openeer.databinding.ActivityCameraCaptureBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraCaptureBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var hasRecordAudioPermission: Boolean = false

    private enum class RecordingState { IDLE, HOLD, LOCK }

    private var recordingState: RecordingState = RecordingState.IDLE
    private var recordingPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var longPressTriggered: Boolean = false
    private var ignoreNextUp: Boolean = false
    private lateinit var startVideoRunnable: Runnable

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA]
                ?: hasPermission(Manifest.permission.CAMERA)
            hasRecordAudioPermission = permissions[Manifest.permission.RECORD_AUDIO]
                ?: hasPermission(Manifest.permission.RECORD_AUDIO)

            if (cameraGranted) startCamera()
            else {
                Toast.makeText(this, "Permission caméra refusée", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            uris.forEach { uri ->
                takePersistablePermission(uri)
                showImportToast(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCameraCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        startVideoRunnable = Runnable {
            if (recordingState == RecordingState.IDLE &&
                recordingPointerId != MotionEvent.INVALID_POINTER_ID &&
                binding.btnCapture.isPressed
            ) {
                longPressTriggered = startVideoRecording()
                if (!longPressTriggered) {
                    recordingPointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
        }

        binding.btnCapture.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    handleActionDown(event)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    handlePointerDown(event)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    handleActionUp(event)
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    handlePointerUp(event)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    handleActionCancel()
                    true
                }
                else -> false
            }
        }

        binding.btnGallery.setOnClickListener {
            galleryLauncher.launch(arrayOf("image/*", "video/*"))
        }
        binding.btnScreenshot.setOnClickListener {
            Toast.makeText(this, "Capture d’écran (à venir)", Toast.LENGTH_SHORT).show()
        }

        hasRecordAudioPermission = hasPermission(Manifest.permission.RECORD_AUDIO)

        requestPermissionsIfNeeded()
    }

    private fun handleActionDown(event: MotionEvent) {
        when (recordingState) {
            RecordingState.IDLE -> {
                recordingPointerId = event.getPointerId(event.actionIndex)
                ignoreNextUp = false
                longPressTriggered = false
                binding.btnCapture.removeCallbacks(startVideoRunnable)
                binding.btnCapture.postDelayed(startVideoRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            RecordingState.HOLD -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId != recordingPointerId) {
                    recordingState = RecordingState.LOCK
                    ignoreNextUp = true
                }
            }
            RecordingState.LOCK -> {
                stopVideoRecording()
                ignoreNextUp = true
            }
        }
    }

    private fun handlePointerDown(event: MotionEvent) {
        when (recordingState) {
            RecordingState.HOLD -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId != recordingPointerId) {
                    recordingState = RecordingState.LOCK
                    ignoreNextUp = true
                }
            }
            RecordingState.LOCK -> {
                stopVideoRecording()
                ignoreNextUp = true
            }
            RecordingState.IDLE -> Unit
        }
    }

    private fun handleActionUp(event: MotionEvent) {
        binding.btnCapture.removeCallbacks(startVideoRunnable)
        val pointerId = event.getPointerId(event.actionIndex)
        if (longPressTriggered && recordingState == RecordingState.HOLD && pointerId == recordingPointerId) {
            stopVideoRecording()
        } else if (!longPressTriggered && !ignoreNextUp && recordingState == RecordingState.IDLE) {
            binding.btnCapture.performClick()
            takePhoto()
        }

        if (pointerId == recordingPointerId) {
            recordingPointerId = MotionEvent.INVALID_POINTER_ID
        }

        if (recordingState == RecordingState.IDLE) {
            longPressTriggered = false
        }

        ignoreNextUp = false
    }

    private fun handlePointerUp(event: MotionEvent) {
        binding.btnCapture.removeCallbacks(startVideoRunnable)
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerId == recordingPointerId && recordingState == RecordingState.HOLD) {
            stopVideoRecording()
        } else if (pointerId != recordingPointerId) {
            ignoreNextUp = false
        }

        if (pointerId == recordingPointerId) {
            recordingPointerId = MotionEvent.INVALID_POINTER_ID
        }
    }

    private fun handleActionCancel() {
        binding.btnCapture.removeCallbacks(startVideoRunnable)
        if (recordingState == RecordingState.HOLD) {
            stopVideoRecording()
        }
        recordingPointerId = MotionEvent.INVALID_POINTER_ID
        if (recordingState == RecordingState.IDLE) {
            longPressTriggered = false
        }
        ignoreNextUp = false
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) permissionsToRequest.add(Manifest.permission.CAMERA)
        if (!hasRecordAudioPermission) permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (isPermissionDeclared(Manifest.permission.READ_MEDIA_IMAGES) &&
                !hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
            ) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (isPermissionDeclared(Manifest.permission.READ_MEDIA_VIDEO) &&
                !hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
            ) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            startCamera()
        } else {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isPermissionDeclared(permission: String): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            packageInfo.requestedPermissions?.contains(permission) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun takePersistablePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Ignore if permission cannot be persisted
        }
    }

    private fun showImportToast(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: ""
        val message = when {
            mimeType.startsWith("image/") -> "Import image : $uri"
            mimeType.startsWith("video/") -> "Import vidéo : $uri"
            else -> "Import média : $uri"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            this.cameraProvider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityThan(Quality.FHD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Erreur caméra : ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "OpenEER_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenEER")
            }
        }

        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        imageCapture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(
                        this@CameraCaptureActivity,
                        getString(R.string.camera_capture_photo_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    Toast.makeText(
                        this@CameraCaptureActivity,
                        getString(R.string.camera_capture_photo_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private fun startVideoRecording(): Boolean {
        if (recordingState != RecordingState.IDLE) return false
        val videoCapture = videoCapture ?: return false

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val fileName = "OpenEER_${timestamp}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OpenEER")
            } else {
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val file = File(moviesDir, fileName)
                put(MediaStore.Video.Media.DATA, file.absolutePath)
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pendingRecording = videoCapture.output.prepareRecording(this, outputOptions)
        if (hasRecordAudioPermission) {
            pendingRecording.withAudioEnabled()
        }

        return try {
            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    handleFinalizeEvent(event)
                }
            }
            recordingState = RecordingState.HOLD
            true
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.camera_capture_video_start_error), Toast.LENGTH_SHORT).show()
            false
        } catch (e: IllegalStateException) {
            Toast.makeText(this, getString(R.string.camera_capture_video_start_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun stopVideoRecording() {
        if (recordingState == RecordingState.IDLE) return
        binding.btnCapture.removeCallbacks(startVideoRunnable)
        activeRecording?.stop()
        activeRecording = null
        recordingState = RecordingState.IDLE
        longPressTriggered = false
        ignoreNextUp = true
    }

    private fun handleFinalizeEvent(event: VideoRecordEvent.Finalize) {
        event.recording.close()
        recordingState = RecordingState.IDLE
        activeRecording = null

        if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
            Toast.makeText(this, getString(R.string.camera_capture_video_saved), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.camera_capture_video_error), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.btnCapture.removeCallbacks(startVideoRunnable)
        stopVideoRecording()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}
