package com.example.openeer.ui.capture

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.OutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.R
import com.example.openeer.ui.util.toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.Executor
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: MaterialButton
    private lateinit var openGalleryButton: MaterialButton
    private lateinit var stateIdle: TextView
    private lateinit var stateHold: TextView
    private lateinit var stateLock: TextView
    private lateinit var cameraController: LifecycleCameraController
    private lateinit var displayManager: DisplayManager

    private val mainExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private var activeRecording: Recording? = null
    private var lastPhotoPath: String? = null
    private var lastVideoUri: Uri? = null
    private var pendingVideoFile: File? = null
    private var captureState: CaptureState = CaptureState.IDLE
    private var ignoreNextTapAfterLongPressFailure = false
    private var noteId: Long? = null

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == previewView.display?.displayId) {
                updateTargetRotations()
            }
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                handlePickedMedia(uri)
            }
        }

    private val mediaPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            if (granted) {
                launchUnifiedPhotoPicker()
            } else {
                toast("Permission galerie refusée", Toast.LENGTH_LONG)
            }
        }

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        val started = startVideoRecording()
        if (!started) {
            longPressTriggered = false
            ignoreNextTapAfterLongPressFailure = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_camera_capture)

        val toolbar: MaterialToolbar = findViewById(R.id.cameraToolbar)
        val controls: View = findViewById(R.id.cameraControls)
        val root: View = findViewById(R.id.cameraRoot)
        previewView = findViewById(R.id.cameraPreview)
        captureButton = findViewById(R.id.captureButton)
        openGalleryButton = findViewById(R.id.openGalleryButton)
        stateIdle = findViewById(R.id.captureStateIdle)
        stateHold = findViewById(R.id.captureStateHold)
        stateLock = findViewById(R.id.captureStateLock)

        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L).takeIf { it > 0 }

        val originalToolbarPaddingTop = toolbar.paddingTop
        val originalControlsPaddingBottom = controls.paddingBottom

        toolbar.setNavigationOnClickListener { finishWithResultOk() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = originalToolbarPaddingTop + systemBars.top)
            controls.updatePadding(bottom = originalControlsPaddingBottom + systemBars.bottom)
            insets
        }

        ViewCompat.requestApplyInsets(root)

        setupCameraController()
        setupGalleryButton()
        setupCaptureButton()

        onBackPressedDispatcher.addCallback(this) {
            finishWithResultOk()
        }
    }

    override fun onResume() {
        super.onResume()
        cameraController.setAudioEnabled(hasAudioPermission())
        updateTargetRotations()
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressHandler.removeCallbacks(longPressRunnable)
        activeRecording?.apply {
            stop()
            close()
        }
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(displayListener)
        }
    }

    private fun setupCameraController() {
        cameraController = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            isTapToFocusEnabled = true
            isPinchToZoomEnabled = true
            setAudioEnabled(hasAudioPermission())
            bindToLifecycle(this@CameraCaptureActivity)
        }
        previewView.controller = cameraController
        previewView.doOnLayout { updateTargetRotations() }

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun setupGalleryButton() {
        openGalleryButton.setOnClickListener { openMediaPickerWithPermissions() }
    }

    private fun setupCaptureButton() {
        updateCaptureState(CaptureState.IDLE)

        captureButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isRecording()) {
                        longPressTriggered = false
                        longPressHandler.postDelayed(
                            longPressRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
                    } else {
                        longPressTriggered = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (ignoreNextTapAfterLongPressFailure) {
                        ignoreNextTapAfterLongPressFailure = false
                        longPressTriggered = false
                        return@setOnTouchListener true
                    }
                    if (longPressTriggered) {
                        handleLongPressRelease()
                    } else {
                        handleTapWhileNotLongPress(cancelled = false)
                        view.performClick()
                    }
                    longPressTriggered = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    ignoreNextTapAfterLongPressFailure = false
                    if (longPressTriggered) {
                        handleLongPressRelease()
                    }
                    longPressTriggered = false
                    true
                }
                else -> false
            }
        }
    }

    private fun openMediaPickerWithPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val needed = mutableListOf<String>()
            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.READ_MEDIA_IMAGES
            }
            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.READ_MEDIA_VIDEO
            }
            if (needed.isNotEmpty()) {
                mediaPermissionsLauncher.launch(needed.toTypedArray())
                return
            }
        }
        launchUnifiedPhotoPicker()
    }

    private fun launchUnifiedPhotoPicker() {
        pickMediaLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    private fun handlePickedMedia(uri: Uri) {
        val targetNoteId = noteId
        if (targetNoteId == null) {
            toast("Note introuvable", Toast.LENGTH_LONG)
            return
        }

        when (val mime = resolveMimeType(uri)) {
            null -> handlePickedImage(targetNoteId, uri, null)
            else -> {
                if (mime.startsWith("video")) {
                    handlePickedVideo(targetNoteId, uri, mime)
                } else if (mime.startsWith("image")) {
                    handlePickedImage(targetNoteId, uri, mime)
                } else {
                    toast("Format de média non pris en charge", Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun resolveMimeType(uri: Uri): String? {
        val resolverMime = contentResolver.getType(uri)
        if (!resolverMime.isNullOrBlank()) {
            return resolverMime
        }
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return if (!extension.isNullOrEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else {
            null
        }
    }

    private fun handlePickedImage(noteId: Long, uri: Uri, mime: String?) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    blocksRepo.appendPhoto(noteId, uri.toString(), mimeType = mime ?: "image/*")
                }.isSuccess
            }
            if (saved) {
                lastPhotoPath = uri.toString()
                toast(getString(R.string.camera_capture_photo_saved))
            } else {
                toast(getString(R.string.camera_capture_photo_error), Toast.LENGTH_LONG)
            }
        }
    }

    private fun handlePickedVideo(noteId: Long, uri: Uri, mime: String) {
        lifecycleScope.launch {
            val duration = readVideoDurationMs(uri) ?: 0L
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    blocksRepo.appendVideo(noteId, uri.toString(), mimeType = mime, durationMs = duration)
                }.isSuccess
            }
            if (saved) {
                lastVideoUri = uri
                toast(getString(R.string.camera_capture_video_saved))
            } else {
                toast(getString(R.string.camera_capture_video_error), Toast.LENGTH_LONG)
            }
        }
    }

    private suspend fun readVideoDurationMs(uri: Uri): Long? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this@CameraCaptureActivity, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (t: Throwable) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun handleTapWhileNotLongPress(cancelled: Boolean) {
        if (cancelled) return
        if (isRecording()) {
            if (captureState != CaptureState.LOCK) {
                updateCaptureState(CaptureState.LOCK)
            } else {
                stopVideoRecording()
            }
        } else {
            capturePhoto()
        }
    }

    private fun handleLongPressRelease() {
        if (!isRecording()) return
        if (captureState == CaptureState.LOCK) return
        stopVideoRecording()
    }

    private fun capturePhoto() {
        val photoFile = createPhotoFile()
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        captureButton.isEnabled = false
        cameraController.takePicture(
            output,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    captureButton.isEnabled = true
                    lastPhotoPath = photoFile.absolutePath
                    toast(getString(R.string.camera_capture_photo_saved))
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    photoFile.delete()
                    toast(getString(R.string.camera_capture_photo_error), length = Toast.LENGTH_SHORT)
                }
            }
        )
    }

    private fun startVideoRecording(): Boolean {
        if (isRecording()) return true
        val outputOptions = buildVideoOutputOptions()
        val recording = try {
            cameraController.startRecording(outputOptions, mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (captureState != CaptureState.LOCK) {
                            updateCaptureState(CaptureState.HOLD)
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        handleVideoFinalize(event)
                    }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            pendingVideoFile?.delete()
            pendingVideoFile = null
            toast(getString(R.string.camera_capture_video_start_error), length = Toast.LENGTH_LONG)
            return false
        }
        activeRecording = recording
        updateCaptureState(CaptureState.HOLD)
        return true
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
    }

    private fun handleVideoFinalize(event: VideoRecordEvent.Finalize) {
        if (event.hasError()) {
            toast(getString(R.string.camera_capture_video_error), length = Toast.LENGTH_LONG)
            val outputUri = event.outputResults.outputUri
            if (outputUri != null) {
                deleteVideoUri(outputUri)
            } else {
                pendingVideoFile?.delete()
            }
            lastVideoUri = null
        } else {
            val outputUri = event.outputResults.outputUri
            lastVideoUri = outputUri ?: pendingVideoFile?.let { Uri.fromFile(it) }
            toast(getString(R.string.camera_capture_video_saved))
        }
        event.recording.close()
        activeRecording = null
        pendingVideoFile = null
        updateCaptureState(CaptureState.IDLE)
    }

    private fun buildVideoOutputOptions(): OutputOptions {
        val name = "vid_${System.currentTimeMillis()}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OpenEER")
            }
            pendingVideoFile = null
            MediaStoreOutputOptions.Builder(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValues).build()
        } else {
            val dir = File(filesDir, "videos").apply { mkdirs() }
            val file = File(dir, "$name.mp4")
            pendingVideoFile = file
            FileOutputOptions.Builder(file).build()
        }
    }

    private fun deleteVideoUri(uri: Uri) {
        when (uri.scheme?.lowercase()) {
            "content" -> runCatching { contentResolver.delete(uri, null, null) }
            "file", null -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.delete() }
            else -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        }
    }

    private fun updateCaptureState(state: CaptureState) {
        captureState = state
        val activeColor = ContextCompat.getColor(this, android.R.color.white)
        val inactiveColor = ColorUtils.setAlphaComponent(activeColor, 0x80)
        stateIdle.setTextColor(if (state == CaptureState.IDLE) activeColor else inactiveColor)
        stateHold.setTextColor(if (state == CaptureState.HOLD) activeColor else inactiveColor)
        stateLock.setTextColor(if (state == CaptureState.LOCK) activeColor else inactiveColor)
    }

    private fun updateTargetRotations() {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        cameraController.imageCaptureTargetRotation = rotation
        cameraController.videoCaptureTargetRotation = rotation
        cameraController.previewTargetRotation = rotation
    }

    private fun createPhotoFile(): File {
        val dir = File(filesDir, "images").apply { mkdirs() }
        return File(dir, "cap_${System.currentTimeMillis()}.jpg")
    }

    private fun isRecording(): Boolean = activeRecording != null

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun finishWithResultOk() {
        val data = Intent().apply {
            lastPhotoPath?.let { putExtra(EXTRA_LAST_PHOTO, it) }
            lastVideoUri?.let { putExtra(EXTRA_LAST_VIDEO_URI, it.toString()) }
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private enum class CaptureState { IDLE, HOLD, LOCK }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_LAST_PHOTO = "extra_last_photo"
        const val EXTRA_LAST_VIDEO_URI = "extra_last_video"
    }
}
