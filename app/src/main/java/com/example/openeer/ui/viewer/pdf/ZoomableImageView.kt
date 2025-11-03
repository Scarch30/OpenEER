package com.example.openeer.ui.viewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.GestureDetectorCompat
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val gestureDetector: GestureDetectorCompat
    private val scaleDetector: ScaleGestureDetector
    private val contentMatrix = Matrix()
    private val displayRect = RectF()

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    init {
        super.setScaleType(ScaleType.MATRIX)
        gestureDetector = GestureDetectorCompat(context, GestureListener())
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        isLongClickable = false
    }

    override fun setScaleType(scaleType: ScaleType?) {
        // Force MATRIX to keep zoom behaviour consistent.
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val shouldDisallow = scaleDetector.isInProgress || currentScale > minScale + 0.01f
        parent?.requestDisallowInterceptTouchEvent(shouldDisallow)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!shouldDisallow) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            ensureWithinBounds()
        }
        return true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) {
            post { resetZoom() }
        }
    }

    fun resetZoom() {
        val d = drawable ?: return
        val viewWidth = width - paddingLeft - paddingRight
        val viewHeight = height - paddingTop - paddingBottom
        if (viewWidth <= 0 || viewHeight <= 0) {
            post { resetZoom() }
            return
        }

        val dw = if (d.intrinsicWidth > 0) d.intrinsicWidth.toFloat() else viewWidth.toFloat()
        val dh = if (d.intrinsicHeight > 0) d.intrinsicHeight.toFloat() else viewHeight.toFloat()

        val scale = min(viewWidth / dw, viewHeight / dh)
        minScale = scale
        maxScale = max(scale * 5f, scale + 2f)
        currentScale = scale

        contentMatrix.reset()
        contentMatrix.setScale(scale, scale)
        val dx = (viewWidth - dw * scale) / 2f + paddingLeft
        val dy = (viewHeight - dh * scale) / 2f + paddingTop
        contentMatrix.postTranslate(dx, dy)
        imageMatrix = contentMatrix
    }

    private fun applyMatrix() {
        imageMatrix = contentMatrix
    }

    private fun ensureWithinBounds() {
        val rect = getDisplayRect() ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0) deltaX = -rect.left
            if (rect.right < viewWidth) deltaX = viewWidth - rect.right
        }

        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0) deltaY = -rect.top
            if (rect.bottom < viewHeight) deltaY = viewHeight - rect.bottom
        }

        if (deltaX != 0f || deltaY != 0f) {
            contentMatrix.postTranslate(deltaX, deltaY)
            applyMatrix()
        }
    }

    private fun getDisplayRect(): RectF? {
        val d = drawable ?: return null
        displayRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        contentMatrix.mapRect(displayRect)
        return displayRect
    }

    private fun zoomTo(scale: Float, focusX: Float, focusY: Float) {
        val target = scale.coerceIn(minScale, maxScale)
        val factor = target / currentScale
        if (factor == 1f) return
        contentMatrix.postScale(factor, factor, focusX, focusY)
        currentScale = target
        ensureWithinBounds()
        applyMatrix()
    }

    private fun translateBy(dx: Float, dy: Float) {
        contentMatrix.postTranslate(dx, dy)
        ensureWithinBounds()
        applyMatrix()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = newScale / currentScale
            if (factor != 1f) {
                contentMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                currentScale = newScale
                ensureWithinBounds()
                applyMatrix()
            }
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (currentScale > minScale + 0.01f) {
                minScale
            } else {
                min(minScale * 2f, maxScale)
            }
            zoomTo(target, e.x, e.y)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent?,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (currentScale > minScale + 0.01f || isImageLargerThanView()) {
                translateBy(-distanceX, -distanceY)
                return true
            }
            return false
        }
    }

    private fun isImageLargerThanView(): Boolean {
        val rect = getDisplayRect() ?: return false
        return rect.width() > width || rect.height() > height
    }
}
