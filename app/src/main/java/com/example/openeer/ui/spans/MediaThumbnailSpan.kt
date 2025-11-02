package com.example.openeer.ui.spans

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.openeer.R
import com.example.openeer.ui.spans.MediaThumbResolver.ThumbSource
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaThumbnailSpan(
    private val context: Context,
    private val textView: TextView,
    private val blockId: Long,
) : ReplacementSpan() {

    private val sizePx: Int = dp(THUMB_SIZE_DP)
    private val cornerRadius: Float = dp(CORNER_RADIUS_DP).toFloat()

    @ColorInt
    private val backgroundColor: Int = resolveColor(
        com.google.android.material.R.attr.colorSurfaceVariant,
        Color.parseColor("#DDDDDD"),
    )

    @ColorInt
    private val textColor: Int = resolveColor(
        com.google.android.material.R.attr.colorOnSurface,
        Color.BLACK,
    )

    private val placeholderLabel: String = "media:#$blockId"
    private val placeholderPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = textView.textSize
    }
    private val bitmapPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = backgroundColor
    }
    private val overlayTextPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(OVERLAY_TEXT_SP)
    }
    private val overlayBackgroundPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }

    private val rect = RectF()
    private val srcRect = Rect()
    private val clipPath = Path()

    @Suppress("unused")
    private val accessibilityDescription = "Miniature du média #$blockId"

    private var bitmap: Bitmap? = Companion.bitmapCache.get(blockId)
    private val loading = AtomicBoolean(false)

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        fm?.let {
            val fontMetrics = paint.fontMetricsInt
            val textHeight = fontMetrics.descent - fontMetrics.ascent
            val desired = sizePx
            val extra = desired - textHeight
            val extraTop = ceil(extra / 2f).toInt()
            val extraBottom = extra - extraTop
            it.ascent = fontMetrics.ascent - extraTop
            it.descent = fontMetrics.descent + extraBottom
            it.top = it.ascent
            it.bottom = it.descent
        }
        return sizePx
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val actualBitmap = bitmap ?: Companion.bitmapCache.get(blockId)?.also { cached ->
            bitmap = cached
        }
        val width = sizePx.toFloat()
        val height = sizePx.toFloat()
        val fontMetrics = paint.fontMetrics
        val centerY = y + (fontMetrics.descent + fontMetrics.ascent) / 2f
        val chipTop = centerY - height / 2f
        val chipBottom = chipTop + height

        rect.set(x, chipTop, x + width, chipBottom)

        if (actualBitmap != null) {
            srcRect.set(0, 0, actualBitmap.width, actualBitmap.height)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            val checkpoint = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(actualBitmap, srcRect, rect, bitmapPaint)
            canvas.restoreToCount(checkpoint)
        } else {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
            drawPlaceholder(canvas, rect)
            loadOnce()
        }
    }

    private fun drawPlaceholder(canvas: Canvas, bounds: RectF) {
        val textWidth = placeholderPaint.measureText(placeholderLabel)
        val baseline = bounds.top + (bounds.height() - placeholderPaint.descent() + placeholderPaint.ascent()) / 2f - placeholderPaint.ascent()
        val textX = bounds.left + (bounds.width() - textWidth) / 2f
        canvas.drawText(placeholderLabel, textX, baseline, placeholderPaint)
    }

    private fun loadOnce() {
        if (bitmap != null) return
        val cached = Companion.bitmapCache.get(blockId)
        if (cached != null) {
            bitmap = cached
            textView.post { textView.postInvalidateOnAnimation() }
            return
        }
        if (!loading.compareAndSet(false, true)) return
        Companion.loadScope.launch {
            try {
                val source = MediaThumbResolver.resolveSource(context, blockId)
                val loadedBitmap = when (source) {
                    is ThumbSource.Image -> loadImage(source.model)
                    is ThumbSource.MapSnapshot -> loadMapSnapshot(source.file)
                    is ThumbSource.Placeholder -> buildPlaceholderBitmap(source.kind)
                }
                if (loadedBitmap != null) {
                    bitmap = loadedBitmap
                    Companion.bitmapCache.put(blockId, loadedBitmap)
                    textView.post { textView.postInvalidateOnAnimation() }
                    loading.set(false)
                } else {
                    loading.set(false)
                }
            } catch (t: Throwable) {
                loading.set(false)
            }
        }
    }

    private fun loadImage(model: Any): Bitmap? {
        return runCatching {
            Glide.with(textView)
                .asBitmap()
                .load(model)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .submit(sizePx, sizePx)
                .get()
        }.getOrNull()?.also {
            loading.set(false)
        }
    }

    private fun loadMapSnapshot(file: java.io.File): Bitmap? {
        if (!file.exists()) {
            loading.set(false)
            return null
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        if (decoded == null) {
            loading.set(false)
            return null
        }
        val scaled = if (decoded.width != sizePx || decoded.height != sizePx) {
            Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true).also {
                if (it != decoded) {
                    decoded.recycle()
                }
            }
        } else {
            decoded
        }
        loading.set(false)
        return scaled
    }

    private fun buildPlaceholderBitmap(kind: ThumbSource.Kind): Bitmap? {
        val cached = Companion.placeholderCache[kind]
        if (cached != null) {
            loading.set(false)
            return cached
        }
        val drawableRes = when (kind) {
            ThumbSource.Kind.AUDIO -> R.drawable.ic_media_audio_24
            ThumbSource.Kind.FILE -> R.drawable.ic_media_file_24
            ThumbSource.Kind.UNKNOWN -> R.drawable.ic_media_file_24
            ThumbSource.Kind.BROKEN -> R.drawable.ic_media_file_24
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        rect.set(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        val drawable = getDrawable(drawableRes) ?: run {
            loading.set(false)
            return null
        }
        val iconSize = dp(ICON_SIZE_DP)
        val left = ((sizePx - iconSize) / 2f).toInt()
        val top = ((sizePx - iconSize) / 2f).toInt()
        drawable.setBounds(left, top, left + iconSize, top + iconSize)
        drawable.draw(canvas)
        if (kind == ThumbSource.Kind.BROKEN) {
            val strike = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x88FFFFFF.toInt()
                strokeWidth = dp(2f).toFloat()
            }
            canvas.drawLine(
                dp(8f).toFloat(),
                sizePx - dp(8f).toFloat(),
                sizePx - dp(8f).toFloat(),
                dp(8f).toFloat(),
                strike,
            )
            drawOverlayLabel(canvas, context.getString(R.string.media_link_broken))
        } else if (kind != ThumbSource.Kind.UNKNOWN) {
            drawOverlayLabel(canvas, kind.label)
        }
        Companion.placeholderCache[kind] = bitmap
        loading.set(false)
        return bitmap
    }

    private fun drawOverlayLabel(canvas: Canvas, label: String) {
        val padding = dp(4f)
        val textWidth = overlayTextPaint.measureText(label)
        val textHeight = overlayTextPaint.descent() - overlayTextPaint.ascent()
        val backgroundRect = RectF(
            padding.toFloat(),
            sizePx.toFloat() - textHeight - padding * 1.5f,
            padding.toFloat() + textWidth + padding.toFloat(),
            sizePx.toFloat() - padding.toFloat(),
        )
        canvas.drawRoundRect(backgroundRect, dp(2f).toFloat(), dp(2f).toFloat(), overlayBackgroundPaint)
        val textBaseline = backgroundRect.bottom - padding.toFloat() - overlayTextPaint.descent()
        canvas.drawText(label, backgroundRect.left + padding / 2f, textBaseline, overlayTextPaint)
    }

    private fun getDrawable(@DrawableRes resId: Int) = ContextCompat.getDrawable(context, resId)

    private fun resolveColor(attr: Int, @ColorInt fallback: Int): Int {
        return com.google.android.material.color.MaterialColors.getColor(context, attr, fallback)
    }

    private fun dp(value: Float): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun sp(value: Float): Float {
        return value * context.resources.displayMetrics.scaledDensity
    }

    companion object {
        private const val THUMB_SIZE_DP = 72
        private const val CORNER_RADIUS_DP = 12
        private const val ICON_SIZE_DP = 36
        private const val OVERLAY_TEXT_SP = 10f

        private val bitmapCache = object : android.util.LruCache<Long, Bitmap>(32) {
            override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
        }

        private val placeholderCache = Collections.synchronizedMap(mutableMapOf<ThumbSource.Kind, Bitmap>())
        private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

private val ThumbSource.Kind.label: String
    get() = when (this) {
        ThumbSource.Kind.AUDIO -> "AUDIO"
        ThumbSource.Kind.FILE -> "FICHIER"
        ThumbSource.Kind.UNKNOWN -> "MEDIA"
        ThumbSource.Kind.BROKEN -> "MEDIA"
    }

