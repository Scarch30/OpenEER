package com.example.openeer.ui.camera

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.databinding.ActivityCameraCaptureBinding
import com.example.openeer.workers.VideoToTextWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class CameraCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "noteId"
        private const val MIME_IMAGE = "image/jpeg"
        private const val MIME_VIDEO = "video/mp4"
        private const val TAG = "CameraCapture"
    }

    private lateinit var binding: ActivityCameraCaptureBinding
    private lateinit var cameraExecutor: ExecutorService

    // Photo
    private var imageCapture: ImageCapture? = null

    // Vidéo
    private var videoCapture: VideoCapture<Recorder>? = null
    private var pendingRecording: PendingRecording? = null
    private var activeRecording: Recording? = null
    private var isLocked = false

    // Gestuelle “switch”
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val switchThresholdPx by lazy { 40f * resources.displayMetrics.density }

    private var noteIdArg: Long = -1L

    // Permissions
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private val requestReadImagesPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadLastPhotoThumbnail()
        }

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // Photo/Video Picker
    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) importFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCameraCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Insets: remonte la barre de boutons
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomControls) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = (16 * resources.displayMetrics.density).toInt()
            v.updatePadding(bottom = sys.bottom + extra)
            insets
        }

        noteIdArg = intent?.getLongExtra(EXTRA_NOTE_ID, -1L) ?: -1L
        if (noteIdArg <= 0L) {
            Toast.makeText(this, getString(R.string.invalid_note_id), Toast.LENGTH_SHORT).show()
            finish(); return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Permissions initiales
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Gestuelle: tap = photo, long tap = vidéo, glisser = lock
        binding.btnShutter.setOnClickListener {
            if (activeRecording != null) {
                if (isLocked) stopVideoRecording()
            } else {
                takePhotoAndAppend()
            }
        }
        binding.btnShutter.setOnLongClickListener {
            longPressed = true
            startVideoRecording(unlockedAtStart = true)
            binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        binding.btnShutter.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; longPressed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeRecording != null && !isLocked && longPressed) {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (hypot(dx, dy) >= switchThresholdPx) {
                            isLocked = true
                            binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            updateLockUi()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeRecording != null && !isLocked) stopVideoRecording()
                    longPressed = false
                }
            }
            false
        }

        // Galerie
        binding.btnGalleryThumb.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }

    override fun onResume() {
        super.onResume()
        ensureReadImagesPermissionThenLoadThumb()
    }

    // ======== Galerie : import ========

    private fun importFromUri(source: Uri) {
        lifecycleScope.launch {
            val type = contentResolver.getType(source) ?: source.toString().lowercase(Locale.US).let {
                when {
                    it.endsWith(".jpg") || it.endsWith(".jpeg") -> "image/jpeg"
                    it.endsWith(".png")  -> "image/png"
                    it.endsWith(".webp") -> "image/webp"
                    it.endsWith(".gif")  -> "image/gif"
                    it.endsWith(".mp4")  -> "video/mp4"
                    it.endsWith(".mkv")  -> "video/x-matroska"
                    it.endsWith(".3gp")  -> "video/3gpp"
                    else -> ""
                }
            }
            if (type.startsWith("image/")) {
                val name = "OpenEER_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, type.ifBlank { "image/*" })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenEER")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val dest = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (dest == null) {
                    Toast.makeText(this@CameraCaptureActivity, "Import image impossible", Toast.LENGTH_LONG).show()
                    return@launch
                }
                runCatching {
                    contentResolver.openOutputStream(dest)?.use { out ->
                        contentResolver.openInputStream(source)?.use { inp -> inp.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(dest, values, null, null)
                    }
                }.onFailure {
                    contentResolver.delete(dest, null, null)
                    Toast.makeText(this@CameraCaptureActivity, "Import image impossible", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val db = AppDatabase.get(this@CameraCaptureActivity)
                val blocks = BlocksRepository(db.blockDao(), db.noteDao())
                launch(Dispatchers.IO) {
                    blocks.appendPhoto(
                        noteId   = noteIdArg,
                        mediaUri = dest.toString(),
                        width    = null,
                        height   = null,
                        mimeType = type.ifBlank { "image/*" },
                        groupId  = null
                    )
                }
                Toast.makeText(this@CameraCaptureActivity, "Import image réussi", Toast.LENGTH_SHORT).show()
                finish()
            } else if (type.startsWith("video/")) {
                val name = "OpenEER_${System.currentTimeMillis()}.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, type.ifBlank { "video/*" })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OpenEER")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val dest = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (dest == null) {
                    Toast.makeText(this@CameraCaptureActivity, "Import vidéo impossible", Toast.LENGTH_LONG).show()
                    return@launch
                }
                runCatching {
                    contentResolver.openOutputStream(dest)?.use { out ->
                        contentResolver.openInputStream(source)?.use { inp -> inp.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        contentResolver.update(dest, values, null, null)
                    }
                }.onFailure {
                    contentResolver.delete(dest, null, null)
                    Toast.makeText(this@CameraCaptureActivity, "Import vidéo impossible", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val db = AppDatabase.get(this@CameraCaptureActivity)
                val blocks = BlocksRepository(db.blockDao(), db.noteDao())
                launch(Dispatchers.IO) {
                    val gid = generateGroupId()
                    val videoId = blocks.appendVideo(
                        noteId     = noteIdArg,
                        mediaUri   = dest.toString(),
                        mimeType   = type.ifBlank { "video/*" },
                        durationMs = null,
                        groupId    = gid
                    )
                    Log.d(TAG, "Append VIDEO OK (import): uri=$dest note=$noteIdArg gid=$gid videoId=$videoId")

                    VideoToTextWorker.enqueue(
                        context      = this@CameraCaptureActivity,
                        videoUri     = dest,
                        noteId       = noteIdArg,
                        groupId      = gid,
                        videoBlockId = videoId
                    )
                    Log.d(TAG, "Enqueued VideoToTextWorker (import). Tag='video_tx'")

                    // Log état WM
                    runCatching {
                        val infos = WorkManager.getInstance(this@CameraCaptureActivity)
                            .getWorkInfosByTag("video_tx").get()
                        for (wi in infos) Log.d(TAG, "WM(tag=video_tx) id=${wi.id} state=${wi.state}")
                    }
                }

                Toast.makeText(this@CameraCaptureActivity, "Import vidéo réussi", Toast.LENGTH_SHORT).show()

                // Attendre ~1s que le FGS soit monté avant de fermer (Android 14)
                lifecycleScope.launch {
                    waitWorkerRunning(tag = "video_tx", timeoutMs = 1500L)
                    finish()
                }
            } else {
                Toast.makeText(this@CameraCaptureActivity, "Type non supporté", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ======== Photo ========

    private fun takePhotoAndAppend() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OpenEER_$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, MIME_IMAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenEER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@CameraCaptureActivity, getString(R.string.camera_capture_photo_error), Toast.LENGTH_SHORT).show()
                }
            }
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val savedUri = outputFileResults.savedUri ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                        contentResolver.update(savedUri, values, null, null)
                    }
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val db = AppDatabase.get(this@CameraCaptureActivity)
                        val blocks = BlocksRepository(db.blockDao(), db.noteDao())
                        blocks.appendPhoto(
                            noteId   = noteIdArg,
                            mediaUri = savedUri.toString(),
                            width    = null,
                            height   = null,
                            mimeType = MIME_IMAGE,
                            groupId  = null
                        )
                    }.onSuccess {
                        launch(Dispatchers.Main) {
                            Toast.makeText(this@CameraCaptureActivity, getString(R.string.camera_capture_photo_saved), Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }.onFailure {
                        launch(Dispatchers.Main) {
                            Toast.makeText(this@CameraCaptureActivity, getString(R.string.camera_capture_photo_error), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    // ======== Vidéo ========

    private fun startVideoRecording(unlockedAtStart: Boolean) {
        val vc = videoCapture ?: return
        if (activeRecording != null) return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "OpenEER_$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, MIME_VIDEO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OpenEER")
            }
        }).build()

        val pending = vc.output
            .prepareRecording(this, output)
            .apply {
                if (ContextCompat.checkSelfPermission(this@CameraCaptureActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }

        pendingRecording = pending
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isLocked = !unlockedAtStart
                    showRecordingHud(true)
                    updateLockUi()
                }
                is VideoRecordEvent.Status -> {
                    val ms = event.recordingStats.recordedDurationNanos / 1_000_000
                    updateTimer(ms)
                }
                is VideoRecordEvent.Finalize -> {
                    showRecordingHud(false)
                    val uri = event.outputResults.outputUri
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    if (event.hasError()) {
                        Toast.makeText(this, getString(R.string.video_capture_error), Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                val db = AppDatabase.get(this@CameraCaptureActivity)
                                val blocks = BlocksRepository(db.blockDao(), db.noteDao())

                                val gid = generateGroupId()
                                val videoId = blocks.appendVideo(
                                    noteId     = noteIdArg,
                                    mediaUri   = uri.toString(),
                                    mimeType   = MIME_VIDEO,
                                    durationMs = durationMs,
                                    groupId    = gid
                                )
                                Log.d(TAG, "Append VIDEO OK (capture): uri=$uri note=$noteIdArg gid=$gid videoId=$videoId")

                                VideoToTextWorker.enqueue(
                                    context      = this@CameraCaptureActivity,
                                    videoUri     = uri,
                                    noteId       = noteIdArg,
                                    groupId      = gid,
                                    videoBlockId = videoId
                                )
                                Log.d(TAG, "Enqueued VideoToTextWorker (capture). Tag='video_tx'")

                                runCatching {
                                    val infos = WorkManager.getInstance(this@CameraCaptureActivity)
                                        .getWorkInfosByTag("video_tx").get()
                                    for (wi in infos) Log.d(TAG, "WM(tag=video_tx) id=${wi.id} state=${wi.state}")
                                }
                            }.onSuccess {
                                launch(Dispatchers.Main) {
                                    Toast.makeText(this@CameraCaptureActivity, getString(R.string.video_capture_saved), Toast.LENGTH_SHORT).show()
                                    // Attendre ~1,5s que le FGS soit monté avant de fermer (Android 14)
                                    lifecycleScope.launch {
                                        waitWorkerRunning(tag = "video_tx", timeoutMs = 1500L)
                                        finish()
                                    }
                                }
                            }.onFailure {
                                launch(Dispatchers.Main) {
                                    Toast.makeText(this@CameraCaptureActivity, getString(R.string.video_capture_error), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    activeRecording = null
                    pendingRecording = null
                }
            }
        }
        updateLockUi()
    }

    private suspend fun waitWorkerRunning(tag: String, timeoutMs: Long) {
        try {
            val wm = WorkManager.getInstance(this)
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                val infos = wm.getWorkInfosByTag(tag).get()
                val anyRunning = infos.any { it.state == WorkInfo.State.RUNNING }
                if (anyRunning) {
                    Log.d(TAG, "WM(tag=$tag) is RUNNING — ok to finish()")
                    break
                }
                delay(100)
            }
        } catch (_: Throwable) {
            // silencieux
        }
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
    }

    private fun updateLockUi() {
        val recording = activeRecording != null
        binding.recordingHud.isVisible = recording
    }

    private fun showRecordingHud(show: Boolean) {
        binding.recordingHud.isVisible = show
        if (!show) binding.recTimer.text = "00:00"
    }

    private fun updateTimer(ms: Long) {
        val total = (ms / 1000).toInt()
        val mm = total / 60
        val ss = total % 60
        binding.recTimer.text = String.format(Locale.US, "%02d:%02d", mm, ss)
    }

    // ======== Vignette galerie ========

    private fun ensureReadImagesPermissionThenLoadThumb() {
        val permission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadLastPhotoThumbnail()
        } else {
            requestReadImagesPermission.launch(permission)
        }
    }

    private fun loadLastPhotoThumbnail() {
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sort
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bmp: Bitmap = contentResolver.loadThumbnail(uri, Size(200, 200), null)
                    binding.btnGalleryThumb.setImageBitmap(bmp)
                } else {
                    binding.btnGalleryThumb.setImageURI(uri)
                }
            }
        } catch (_: SecurityException) {
            // Permission non accordée
        } catch (_: Throwable) {
            // Silencieux
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            } catch (_: Throwable) {
                Toast.makeText(this, getString(R.string.camera_start_error), Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
