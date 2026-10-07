package com.bhaskar.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A simple bar chart: one bar per value, optional labels under some bars and optional value
 * labels above them. The [highlight] bar uses the accent color.
 */
class BarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values = FloatArray(0)
    private var labels: List<String> = emptyList()
    private var valueLabels: List<String> = emptyList()
    private var highlight = -1

    private val dp = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.muted)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1 * dp
        color = context.getColor(R.color.line)
    }

    /**
     * @param labels one per bar; empty strings leave a bar unlabeled
     * @param valueLabels shown above each bar, or empty for none
     */
    fun setData(values: FloatArray, labels: List<String>, valueLabels: List<String> = emptyList(), highlight: Int = -1) {
        this.values = values
        this.labels = labels
        this.valueLabels = valueLabels
        this.highlight = highlight
        contentDescription = labels.zip(valueLabels.ifEmpty { values.map { it.toString() } })
            .joinToString { (l, v) -> "$l $v" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val top = if (valueLabels.isEmpty()) 6 * dp else 20 * dp
        val bottom = height - 20 * dp
        val scale = max(values.maxOrNull() ?: 0f, 0.0001f)
        val slot = width / values.size.toFloat()
        val barW = min(slot * 0.62f, 30 * dp)
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, gridPaint)

        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2f
            val h = max((bottom - top) * v / scale, if (v > 0f) 2 * dp else 0f)
            barPaint.color = context.getColor(if (i == highlight) R.color.accent else R.color.bar)
            val r = min(barW / 2f, 4 * dp)
            canvas.drawRoundRect(cx - barW / 2f, bottom - h, cx + barW / 2f, bottom, r, r, barPaint)
            valueLabels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                canvas.drawText(it, cx, bottom - h - 5 * dp, textPaint)
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                // Keep edge labels inside the chart instead of centering them off-screen.
                val half = textPaint.measureText(it) / 2f
                canvas.drawText(it, cx.coerceIn(half, width - half), height - 4 * dp, textPaint)
            }
        }
    }
}
