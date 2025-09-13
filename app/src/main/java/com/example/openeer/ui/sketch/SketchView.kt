package com.example.openeer.ui.sketch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class SketchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { PEN, LINE, RECT, CIRCLE, ARROW, ERASE }

    private var mode: Mode = Mode.PEN
    private val actions = mutableListOf<Pair<Path, Paint>>()
    private var tempPath: Path? = null
    private var startX = 0f
    private var startY = 0f

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
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun setMode(m: Mode) { mode = m }

    fun undo() {
        if (actions.isNotEmpty()) {
            actions.removeLast()
            invalidate()
        }
    }

    fun hasContent(): Boolean = actions.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        actions.forEach { (p, paint) -> canvas.drawPath(p, paint) }
        tempPath?.let { path ->
            val paint = if (mode == Mode.ERASE) erasePaint else drawPaint
            canvas.drawPath(path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mode) {
            Mode.PEN -> handlePen(event)
            Mode.LINE -> handleLine(event)
            Mode.RECT, Mode.CIRCLE, Mode.ARROW -> handleShape(event)
            Mode.ERASE -> handleEraser(event)
        }
        return true
    }

    private fun handlePen(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                tempPath = Path().apply { moveTo(ev.x, ev.y) }
            }
            MotionEvent.ACTION_MOVE -> tempPath?.lineTo(ev.x, ev.y)
            MotionEvent.ACTION_UP -> {
                tempPath?.lineTo(ev.x, ev.y)
                tempPath?.let { actions += it to Paint(drawPaint) }
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
            MotionEvent.ACTION_MOVE -> tempPath?.lineTo(ev.x, ev.y)
            MotionEvent.ACTION_UP -> {
                tempPath?.lineTo(ev.x, ev.y)
                tempPath?.let { actions += it to Paint(erasePaint) }
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
                tempPath?.let { actions += it to Paint(drawPaint) }
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
                tempPath?.let { actions += it to Paint(drawPaint) }
                tempPath = null
            }
        }
        invalidate()
    }

    private fun buildShapePath(sx: Float, sy: Float, ex: Float, ey: Float): Path = when (mode) {
        Mode.RECT -> Path().apply { addRect(sx, sy, ex, ey, Path.Direction.CW) }
        Mode.CIRCLE -> Path().apply { addOval(RectF(sx, sy, ex, ey), Path.Direction.CW) }
        Mode.ARROW -> Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
            val angle = atan2((ey - sy), (ex - sx))
            val len = 40f
            lineTo(
                (ex - len * cos(angle - PI / 6)).toFloat(),
                (ey - len * sin(angle - PI / 6)).toFloat()
            )
            moveTo(ex, ey)
            lineTo(
                (ex - len * cos(angle + PI / 6)).toFloat(),
                (ey - len * sin(angle + PI / 6)).toFloat()
            )
        }
        else -> Path()
    }

    /** Exporte le dessin actuel en Bitmap (utilisé par SketchEditorActivity) */
    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }

    /** Exporte et enregistre directement en PNG dans un dossier donné */
    fun exportPngTo(dir: File): Uri {
        val bmp = exportBitmap()
        dir.mkdirs()
        val file = File(dir, "sketch_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Uri.fromFile(file)
    }
}
