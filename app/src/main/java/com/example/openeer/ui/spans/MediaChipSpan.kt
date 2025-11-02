package com.example.openeer.ui.spans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.roundToInt

class MediaChipSpan(
    context: Context,
    val id: Long,
) : ReplacementSpan() {

    private val label: String = "media:#$id"
    private val paddingH: Int = dp(context, 8)
    private val chipHeight: Int = dp(context, 28)
    private val cornerRadius: Float = dp(context, 12).toFloat()

    @ColorInt
    private val backgroundColor: Int = resolveColor(
        context,
        com.google.android.material.R.attr.colorSurfaceVariant,
        Color.parseColor("#DDDDDD"),
    )

    @ColorInt
    private val textColor: Int = resolveColor(
        context,
        com.google.android.material.R.attr.colorOnSurface,
        Color.BLACK,
    )

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = backgroundColor
    }

    private var cachedWidth: Int = -1

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        if (cachedWidth < 0) {
            cachedWidth = paint.measureText(label).roundToInt() + paddingH * 2
        }

        fm?.let {
            val original = paint.fontMetricsInt
            val textHeight = original.descent - original.ascent
            val desired = chipHeight
            val extra = desired - textHeight
            val extraTop = ceil(extra / 2f).toInt()
            val extraBottom = extra - extraTop
            it.ascent = original.ascent - extraTop
            it.descent = original.descent + extraBottom
            it.top = it.ascent
            it.bottom = it.descent
        }

        return cachedWidth
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
        val width = cachedWidth.takeIf { it >= 0 }
            ?: (paint.measureText(label).roundToInt() + paddingH * 2).also { cachedWidth = it }
        val chipWidth = width.toFloat()
        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val desiredHeight = chipHeight.toFloat()
        val centerY = y + (fm.descent + fm.ascent) / 2f
        val chipTop = centerY - desiredHeight / 2f
        val chipBottom = chipTop + desiredHeight

        val rect = RectF(x, chipTop, x + chipWidth, chipBottom)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)

        val previousColor = paint.color
        val previousStyle = paint.style
        val previousIsAntiAlias = paint.isAntiAlias

        paint.color = textColor
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL

        val textOffset = (desiredHeight - textHeight) / 2f
        val textBaseline = chipTop + textOffset - fm.ascent
        canvas.drawText(label, x + paddingH, textBaseline, paint)

        paint.color = previousColor
        paint.style = previousStyle
        paint.isAntiAlias = previousIsAntiAlias
    }

    private fun resolveColor(
        context: Context,
        attr: Int,
        @ColorInt fallback: Int,
    ): Int = MaterialColors.getColor(context, attr, fallback)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
