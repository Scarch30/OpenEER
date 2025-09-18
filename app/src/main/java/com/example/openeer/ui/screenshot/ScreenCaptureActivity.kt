package com.example.openeer.ui.screenshot

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.ui.util.toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_BLOCK_ID = "extra_block_id"
        const val EXTRA_FILE_PATH = "extra_file_path"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var preview: ImageView
    private lateinit var overlay: SelectionOverlayView
    private lateinit var progress: ProgressBar
    private lateinit var saveButton: MaterialButton
    private lateinit var retryButton: MaterialButton
    private lateinit var controlsContainer: View

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    private val blocksRepository: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startProjection(result.resultCode, result.data!!)
            } else {
                toast(R.string.screen_capture_cancelled, Toast.LENGTH_SHORT)
                finishWithCanceled()
            }
        }

    private var noteId: Long? = null
    private var capturedBitmap: Bitmap? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var hasCapturedFrame: Boolean = false
    private var userInitiatedStop: Boolean = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            if (!hasCapturedFrame && !userInitiatedStop) {
                runOnUiThread {
                    toast(R.string.screen_capture_protected, Toast.LENGTH_LONG)
                    finishWithCanceled()
                }
            }
            userInitiatedStop = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_screen_capture)

        noteId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L)?.takeIf { it > 0 }

        toolbar = findViewById(R.id.screenCaptureToolbar)
        preview = findViewById(R.id.screenCapturePreview)
        overlay = findViewById(R.id.screenSelectionOverlay)
        progress = findViewById(R.id.screenCaptureProgress)
        saveButton = findViewById(R.id.screenCaptureSave)
        retryButton = findViewById(R.id.screenCaptureRetry)
        controlsContainer = findViewById(R.id.screenCaptureControls)

        setupToolbar()
        setupButtons()
        requestProjection()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProjectionResources(suppressCallback = true)
        capturedBitmap?.recycle()
        capturedBitmap = null
    }

    private fun setupToolbar() {
        toolbar.title = getString(R.string.screen_capture_title)
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_close)
        toolbar.setNavigationOnClickListener { finishWithCanceled() }

        val root: View = findViewById(R.id.screenCaptureRoot)
        val originalToolbarPaddingTop = toolbar.paddingTop
        val originalControlsPaddingBottom = controlsContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = originalToolbarPaddingTop + systemBars.top)
            controlsContainer.updatePadding(bottom = originalControlsPaddingBottom + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupButtons() {
        saveButton.isEnabled = false
        saveButton.setOnClickListener { persistSelection() }
        retryButton.setOnClickListener { restartCapture() }
    }

    private fun requestProjection() {
        progress.isVisible = true
        saveButton.isEnabled = false
        capturedBitmap?.recycle()
        capturedBitmap = null
        overlay.setImageBounds(null)
        preview.setImageDrawable(null)
        val captureIntent = projectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }

    private fun restartCapture() {
        releaseProjectionResources(suppressCallback = true)
        requestProjection()
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        releaseProjectionResources(suppressCallback = true)
        userInitiatedStop = false
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        captureThread = HandlerThread("screen_capture").apply { start() }
        imageHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                processImage(reader)
            }, imageHandler)
        }

        hasCapturedFrame = false
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)?.also { projection ->
            projection.registerCallback(projectionCallback, imageHandler)
        }

        val densityDpi = metrics.densityDpi
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "screen_capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            imageHandler
        )
    }

    private fun processImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        reader.setOnImageAvailableListener(null, null)
        val bitmap = try {
            image.toBitmap()
        } catch (t: Throwable) {
            null
        } finally {
            image.close()
        }
        if (bitmap == null) {
            releaseProjectionResources()
            runOnUiThread {
                toast(R.string.screen_capture_protected, Toast.LENGTH_LONG)
                finishWithCanceled()
            }
            return
        }
        hasCapturedFrame = true
        capturedBitmap = bitmap
        releaseProjectionResources(suppressCallback = true)
        runOnUiThread {
            progress.isVisible = false
            preview.setImageBitmap(bitmap)
            saveButton.isEnabled = true
            preview.post { updateOverlayBounds() }
        }
    }

    private fun updateOverlayBounds() {
        val drawable = preview.drawable ?: return
        val imageMatrix: Matrix = preview.imageMatrix
        val drawableRect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        imageMatrix.mapRect(drawableRect)
        val left = drawableRect.left + preview.paddingLeft
        val top = drawableRect.top + preview.paddingTop
        val right = drawableRect.right + preview.paddingLeft
        val bottom = drawableRect.bottom + preview.paddingTop
        overlay.setImageBounds(RectF(left, top, right, bottom))
    }

    private fun persistSelection() {
        val bitmap = capturedBitmap ?: return
        val normalized = overlay.getNormalizedSelection() ?: RectF(0f, 0f, 1f, 1f)
        val cropRect = normalized.toBitmapRect(bitmap.width, bitmap.height)
        val safeRect = cropRect ?: RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val cropLeft = floor(safeRect.left).toInt().coerceIn(0, bitmap.width - 1)
        val cropTop = floor(safeRect.top).toInt().coerceIn(0, bitmap.height - 1)
        val cropRight = ceil(safeRect.right).toInt().coerceIn(cropLeft + 1, bitmap.width)
        val cropBottom = ceil(safeRect.bottom).toInt().coerceIn(cropTop + 1, bitmap.height)
        val cropWidth = max(1, cropRight - cropLeft)
        val cropHeight = max(1, cropBottom - cropTop)

        val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                saveBitmapToFile(cropped)
            }
            if (file == null) {
                toast(R.string.screen_capture_error, Toast.LENGTH_LONG)
                return@launch
            }
            val nid = noteId
            var blockId: Long? = null
            if (nid != null) {
                blockId = withContext(Dispatchers.IO) {
                    blocksRepository.appendPhoto(
                        nid,
                        file.absolutePath,
                        width = cropWidth,
                        height = cropHeight,
                        mimeType = "image/png",
                        extra = "{\"source\":\"screenshot\"}"
                    )
                }
            }
            val data = Intent().apply {
                file.absolutePath.let { putExtra(EXTRA_FILE_PATH, it) }
                nid?.let { putExtra(EXTRA_NOTE_ID, it) }
                blockId?.let { putExtra(EXTRA_BLOCK_ID, it) }
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun finishWithCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun releaseProjectionResources(suppressCallback: Boolean = false) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        mediaProjection?.let { projection ->
            if (suppressCallback) {
                userInitiatedStop = true
            }
            projection.unregisterCallback(projectionCallback)
            projection.stop()
        }
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        imageHandler = null
    }

    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        if (buffer.remaining() == 0) return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun RectF.toBitmapRect(bitmapWidth: Int, bitmapHeight: Int): RectF? {
        if (width() <= 0f || height() <= 0f) return null
        val scaledLeft = (this.left * bitmapWidth).coerceIn(0f, bitmapWidth.toFloat())
        val scaledTop = (this.top * bitmapHeight).coerceIn(0f, bitmapHeight.toFloat())
        val scaledRight = (this.right * bitmapWidth).coerceIn(scaledLeft + 1f, bitmapWidth.toFloat())
        val scaledBottom = (this.bottom * bitmapHeight).coerceIn(scaledTop + 1f, bitmapHeight.toFloat())
        return RectF(scaledLeft, scaledTop, scaledRight, scaledBottom)
    }

    private suspend fun saveBitmapToFile(bitmap: Bitmap): File? {
        val dir = File(filesDir, "images").apply { mkdirs() }
        val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    return null
                }
            }
        } catch (ioe: IOException) {
            return null
        }
        return file
    }
}
