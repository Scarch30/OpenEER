package com.example.openeer.ui.screenshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000")
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val selectionPath = Path()

    private var imageBounds: RectF? = null
    private var selectionRect: RectF? = null
    private var touchStart: PointF? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    fun setImageBounds(bounds: RectF?) {
        imageBounds = bounds?.let { RectF(it) }
        selectionRect = bounds?.let { RectF(it) }
        isEnabled = bounds != null
        invalidate()
    }

    fun resetSelection() {
        selectionRect = imageBounds?.let { RectF(it) }
        invalidate()
    }

    fun clearSelection() {
        selectionRect = null
        invalidate()
    }

    fun hasSelection(): Boolean = selectionRect != null

    fun getNormalizedSelection(): RectF? {
        val bounds = imageBounds ?: return null
        val selection = selectionRect ?: return null
        val width = bounds.width().takeIf { it > 0f } ?: return null
        val height = bounds.height().takeIf { it > 0f } ?: return null
        val left = ((selection.left - bounds.left) / width).coerceIn(0f, 1f)
        val top = ((selection.top - bounds.top) / height).coerceIn(0f, 1f)
        val right = ((selection.right - bounds.left) / width).coerceIn(0f, 1f)
        val bottom = ((selection.bottom - bounds.top) / height).coerceIn(0f, 1f)
        return RectF(left, top, right, bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bounds = imageBounds ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!bounds.contains(event.x, event.y)) {
                    return false
                }
                val startX = event.x.coerceIn(bounds.left, bounds.right)
                val startY = event.y.coerceIn(bounds.top, bounds.bottom)
                touchStart = PointF(startX, startY)
                selectionRect = RectF(startX, startY, startX, startY)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val start = touchStart ?: return false
                val currentX = event.x.coerceIn(bounds.left, bounds.right)
                val currentY = event.y.coerceIn(bounds.top, bounds.bottom)
                val left = min(start.x, currentX)
                val top = min(start.y, currentY)
                val right = max(start.x, currentX)
                val bottom = max(start.y, currentY)
                if (selectionRect == null) {
                    selectionRect = RectF(left, top, right, bottom)
                } else {
                    selectionRect?.set(left, top, right, bottom)
                }
                clampSelection()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchStart = null
                clampSelection()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val selection = selectionRect ?: return
        val save = canvas.saveLayer(null, null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        selectionPath.reset()
        selectionPath.addRect(selection, Path.Direction.CW)
        canvas.drawPath(selectionPath, clearPaint)
        canvas.drawRect(selection, borderPaint)
        canvas.restoreToCount(save)
    }

    private fun clampSelection() {
        val bounds = imageBounds ?: return
        val selection = selectionRect ?: return
        val left = selection.left.coerceIn(bounds.left, bounds.right)
        val top = selection.top.coerceIn(bounds.top, bounds.bottom)
        val right = selection.right.coerceIn(bounds.left, bounds.right)
        val bottom = selection.bottom.coerceIn(bounds.top, bounds.bottom)
        if (right - left < 1f || bottom - top < 1f) {
            selection.set(bounds)
        } else {
            selection.set(left, top, right, bottom)
        }
    }
}
