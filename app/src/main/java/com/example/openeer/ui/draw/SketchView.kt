package com.example.openeer.ui.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SketchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { PEN, LINE, SHAPE, ERASER }
    enum class Shape { RECT, OVAL, TRIANGLE, ARROW }

    var currentTool: Tool = Tool.PEN
    var currentShape: Shape = Shape.RECT

    private val drawPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val erasePaint = Paint().apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = 20f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    private val paths = mutableListOf<Pair<Path, Paint>>()
    private var tempPath: Path? = null
    private var startX = 0f
    private var startY = 0f

    fun setTool(tool: Tool) {
        currentTool = tool
    }

    fun cycleShape() {
        currentShape = when (currentShape) {
            Shape.RECT -> Shape.OVAL
            Shape.OVAL -> Shape.TRIANGLE
            Shape.TRIANGLE -> Shape.ARROW
            Shape.ARROW -> Shape.RECT
        }
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            paths.removeLast()
            invalidate()
        }
    }

    fun isEmpty(): Boolean = paths.isEmpty()
    fun hasContent(): Boolean = !isEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paths.forEach { (p, paint) -> canvas.drawPath(p, paint) }
        tempPath?.let { path ->
            val paint = when (currentTool) {
                Tool.ERASER -> erasePaint
                else -> drawPaint
            }
            canvas.drawPath(path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (currentTool) {
            Tool.PEN -> handlePen(event)
            Tool.LINE -> handleLine(event)
            Tool.SHAPE -> handleShape(event)
            Tool.ERASER -> handleEraser(event)
        }
        return true
    }

    private fun handlePen(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                tempPath = Path().apply { moveTo(ev.x, ev.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                tempPath?.lineTo(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> {
                tempPath?.lineTo(ev.x, ev.y)
                tempPath?.let { paths += it to Paint(drawPaint) }
                tempPath = null
            }
        }
        invalidate()
    }

    private fun handleEraser(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                tempPath = Path().apply { moveTo(ev.x, ev.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                tempPath?.lineTo(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> {
                tempPath?.lineTo(ev.x, ev.y)
                tempPath?.let { paths += it to Paint(erasePaint) }
                tempPath = null
            }
        }
        invalidate()
    }

    private fun handleLine(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                tempPath = Path().apply { moveTo(startX, startY) }
            }
            MotionEvent.ACTION_MOVE -> {
                tempPath = Path().apply {
                    moveTo(startX, startY)
                    lineTo(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                tempPath = Path().apply {
                    moveTo(startX, startY)
                    lineTo(ev.x, ev.y)
                }
                tempPath?.let { paths += it to Paint(drawPaint) }
                tempPath = null
            }
        }
        invalidate()
    }

    private fun handleShape(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                tempPath = Path()
            }
            MotionEvent.ACTION_MOVE -> {
                tempPath = buildShapePath(startX, startY, ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> {
                tempPath = buildShapePath(startX, startY, ev.x, ev.y)
                tempPath?.let { paths += it to Paint(drawPaint) }
                tempPath = null
            }
        }
        invalidate()
    }

    private fun buildShapePath(sx: Float, sy: Float, ex: Float, ey: Float): Path {
        return when (currentShape) {
            Shape.RECT -> Path().apply { addRect(sx, sy, ex, ey, Path.Direction.CW) }
            Shape.OVAL -> Path().apply { addOval(RectF(sx, sy, ex, ey), Path.Direction.CW) }
            Shape.TRIANGLE -> Path().apply {
                moveTo((sx + ex) / 2f, sy)
                lineTo(ex, ey)
                lineTo(sx, ey)
                close()
            }
            Shape.ARROW -> Path().apply {
                moveTo(sx, (sy + ey) / 2f)
                lineTo(ex, (sy + ey) / 2f)
                lineTo(ex - (ey - sy) / 4f, sy)
                moveTo(ex, (sy + ey) / 2f)
                lineTo(ex - (ey - sy) / 4f, ey)
            }
        }
    }

    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }
}
