package com.example.openeer.ui.sketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class SketchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { PEN, LINE, RECT, ROUND_RECT, CIRCLE, ELLIPSE, TRIANGLE, ARROW, STAR, ERASE }

    private var mode: Mode = Mode.PEN
    private var drawColor: Int = Color.BLACK
    private var drawStroke: Float = 5f
    private var fillShapes: Boolean = false

    private data class Stroke(
        val type: Mode,
        val color: Int,
        val width: Float,
        val filled: Boolean = false,
        val points: MutableList<PointF>? = null,
        val sx: Float? = null,
        val sy: Float? = null,
        val ex: Float? = null,
        val ey: Float? = null,
        var cachedPath: Path? = null
    )

    private val strokes: MutableList<Stroke> = mutableListOf()
    private val redoStack: MutableList<Stroke> = mutableListOf()
    private var tempStroke: Stroke? = null
    private var startX = 0f
    private var startY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val strokePaint = Paint(basePaint)
    private val erasePaintTemplate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun setMode(m: Mode) { mode = m; invalidate() }
    fun getMode(): Mode = mode
    fun setColor(color: Int) { drawColor = color; invalidate() }
    fun setStrokeWidth(px: Float) { drawStroke = max(1f, px); invalidate() }
    fun setFilledShapes(filled: Boolean) { fillShapes = filled; invalidate() }

    fun undo() { if (strokes.isNotEmpty()) { redoStack.add(strokes.removeLast()); invalidate() } }
    fun redo() { if (redoStack.isNotEmpty()) { strokes.add(redoStack.removeLast()); invalidate() } }
    fun clear() { strokes.clear(); redoStack.clear(); tempStroke = null; invalidate() }
    fun hasContent(): Boolean = strokes.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        try {
            for (s in strokes) drawStroke(canvas, s)
            tempStroke?.let { drawStroke(canvas, it, preview = true) }
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke, preview: Boolean = false) {
        val paint = when (s.type) {
            Mode.ERASE -> erasePaintTemplate.apply { strokeWidth = s.width }
            else -> strokePaint.apply {
                set(basePaint)
                color = s.color
                strokeWidth = s.width
                style = if (isShapeMode(s.type) && s.filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            }
        }
        val path = if (preview) {
            buildPathFromStroke(s)
        } else {
            s.cachedPath ?: buildPathFromStroke(s).also { s.cachedPath = it }
        }
        canvas.drawPath(path, paint)
    }

    private fun buildPathFromStroke(s: Stroke): Path = when (s.type) {
        Mode.PEN, Mode.ERASE -> Path().apply {
            val pts = s.points
            if (!pts.isNullOrEmpty()) {
                moveTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
        }
        Mode.LINE -> Path().apply {
            moveTo(s.sx ?: 0f, s.sy ?: 0f); lineTo(s.ex ?: 0f, s.ey ?: 0f)
        }
        Mode.RECT, Mode.ROUND_RECT, Mode.CIRCLE, Mode.ELLIPSE, Mode.TRIANGLE, Mode.ARROW, Mode.STAR -> {
            buildShapePath(s.type, s.sx ?: 0f, s.sy ?: 0f, s.ex ?: 0f, s.ey ?: 0f)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 🔒 empêcher TOUT ancêtre (dont le ScrollView) d’intercepter
        if (event.actionMasked == MotionEvent.ACTION_DOWN) disallowAllIntercepts()

        when (mode) {
            Mode.PEN      -> handleFreehand(event, erase = false)
            Mode.ERASE    -> handleFreehand(event, erase = true)
            Mode.LINE     -> handleLine(event)
            Mode.RECT, Mode.ROUND_RECT, Mode.CIRCLE, Mode.ELLIPSE, Mode.TRIANGLE, Mode.ARROW, Mode.STAR ->
                handleShape(event)
        }

        // re-sécuriser en fin de traitement (si le parent a relâché entre-temps)
        disallowAllIntercepts()
        return true
    }

    /** Remonte toute la hiérarchie pour interdire l’interception (ScrollView, etc.). */
    private fun disallowAllIntercepts() {
        var p = parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(true)
            p = p.parent
        }
    }

    private fun handleFreehand(ev: MotionEvent, erase: Boolean) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                redoStack.clear()
                tempStroke = Stroke(
                    type = if (erase) Mode.ERASE else Mode.PEN,
                    color = drawColor,
                    width = drawStroke,
                    points = mutableListOf(PointF(ev.x, ev.y))
                )
            }
            MotionEvent.ACTION_MOVE -> tempStroke?.points?.add(PointF(ev.x, ev.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tempStroke?.points?.add(PointF(ev.x, ev.y))
                tempStroke?.let { finalizeStroke(it) }
            }
        }
        invalidate()
    }

    private fun handleLine(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                redoStack.clear()
                startX = ev.x; startY = ev.y
                tempStroke = Stroke(Mode.LINE, drawColor, drawStroke, sx = startX, sy = startY, ex = startX, ey = startY)
            }
            MotionEvent.ACTION_MOVE -> tempStroke = tempStroke?.copy(ex = ev.x, ey = ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tempStroke = tempStroke?.copy(ex = ev.x, ey = ev.y)
                tempStroke?.let { finalizeStroke(it) }
            }
        }
        invalidate()
    }

    private fun handleShape(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                redoStack.clear()
                startX = ev.x; startY = ev.y
                tempStroke = Stroke(mode, drawColor, drawStroke, filled = fillShapes, sx = startX, sy = startY, ex = startX, ey = startY)
            }
            MotionEvent.ACTION_MOVE -> tempStroke = tempStroke?.copy(ex = ev.x, ey = ev.y, filled = fillShapes)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tempStroke = tempStroke?.copy(ex = ev.x, ey = ev.y, filled = fillShapes)
                tempStroke?.let { finalizeStroke(it) }
            }
        }
        invalidate()
    }

    private fun isShapeMode(m: Mode): Boolean = when (m) {
        Mode.RECT, Mode.ROUND_RECT, Mode.CIRCLE, Mode.ELLIPSE, Mode.TRIANGLE, Mode.ARROW, Mode.STAR -> true
        else -> false
    }

    private fun buildShapePath(type: Mode, sx: Float, sy: Float, ex: Float, ey: Float): Path {
        val left = min(sx, ex); val right = max(sx, ex)
        val top = min(sy, ey);  val bottom = max(sy, ey)
        val w = right - left;   val h = bottom - top
        val rect = RectF(left, top, right, bottom)

        return when (type) {
            Mode.RECT -> Path().apply { addRect(rect, Path.Direction.CW) }
            Mode.ROUND_RECT -> Path().apply { addRoundRect(rect, min(w, h) * 0.2f, min(w, h) * 0.2f, Path.Direction.CW) }
            Mode.CIRCLE, Mode.ELLIPSE -> Path().apply { addOval(rect, Path.Direction.CW) }
            Mode.TRIANGLE -> Path().apply {
                moveTo((left + right) / 2f, top); lineTo(right, bottom); lineTo(left, bottom); close()
            }
            Mode.ARROW -> Path().apply {
                moveTo(sx, sy); lineTo(ex, ey)
                val angle = atan2((ey - sy), (ex - sx))
                val len = 40f
                val x1 = (ex - len * cos(angle - PI / 6)).toFloat()
                val y1 = (ey - len * sin(angle - PI / 6)).toFloat()
                val x2 = (ex - len * cos(angle + PI / 6)).toFloat()
                val y2 = (ey - len * sin(angle + PI / 6)).toFloat()
                moveTo(ex, ey); lineTo(x1, y1)
                moveTo(ex, ey); lineTo(x2, y2)
            }
            Mode.STAR -> buildStarPath(rect)
            else -> Path()
        }
    }

    private fun buildStarPath(rect: RectF): Path {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val outerR = min(rect.width(), rect.height()) / 2f
        val innerR = outerR * 0.5f
        val path = Path()
        var angle = -PI / 2
        val step = PI / 5
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            val x = (cx + r * cos(angle)).toFloat()
            val y = (cy + r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            angle += step
        }
        path.close()
        return path
    }

    private fun finalizeStroke(stroke: Stroke) {
        stroke.cachedPath = buildPathFromStroke(stroke)
        strokes += stroke
        tempStroke = null
    }

    // ---- Persistance JSON (inchangé) ----
    fun getStrokesJson(): String {
        val arr = JSONArray()
        for (s in strokes) {
            val o = JSONObject()
                .put("type", s.type.name)
                .put("color", s.color)
                .put("width", s.width)
                .put("filled", s.filled)
            if (s.points != null) {
                val pts = JSONArray()
                s.points.forEach { p -> pts.put(JSONObject().put("x", p.x).put("y", p.y)) }
                o.put("points", pts)
            } else {
                o.put("sx", s.sx ?: 0f).put("sy", s.sy ?: 0f).put("ex", s.ex ?: 0f).put("ey", s.ey ?: 0f)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun setStrokesJson(json: String) {
        strokes.clear(); redoStack.clear(); tempStroke = null
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = Mode.valueOf(o.getString("type"))
                val color = o.optInt("color", Color.BLACK)
                val width = o.optDouble("width", 5.0).toFloat()
                val filled = o.optBoolean("filled", false)
                val stroke = if (o.has("points")) {
                    val ptsArr = o.getJSONArray("points")
                    val pts = mutableListOf<PointF>()
                    for (j in 0 until ptsArr.length()) {
                        val pj = ptsArr.getJSONObject(j)
                        pts.add(PointF(pj.getDouble("x").toFloat(), pj.getDouble("y").toFloat()))
                    }
                    Stroke(type, color, width, false, pts)
                } else {
                    val sx = o.optDouble("sx", 0.0).toFloat()
                    val sy = o.optDouble("sy", 0.0).toFloat()
                    val ex = o.optDouble("ex", 0.0).toFloat()
                    val ey = o.optDouble("ey", 0.0).toFloat()
                    Stroke(type, color, width, filled, null, sx, sy, ex, ey)
                }
                stroke.cachedPath = buildPathFromStroke(stroke)
                strokes.add(stroke)
            }
        } catch (_: Throwable) { /* ignore JSON invalide */ }
        invalidate()
    }
}
