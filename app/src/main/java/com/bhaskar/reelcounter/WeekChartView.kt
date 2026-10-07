package com.bhaskar.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Seven bars, one per day, with a dashed line at the daily limit. The last bar is today. */
class WeekChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var labels: List<String> = emptyList()
    private var values: List<Int> = emptyList()
    private var limit = 0

    private val dp = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.muted)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * dp
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
        color = context.getColor(R.color.muted)
    }
    private val linePath = Path()

    fun setData(labels: List<String>, values: List<Int>, limit: Int) {
        this.labels = labels
        this.values = values
        this.limit = limit
        contentDescription = labels.zip(values).joinToString { (l, v) -> "$l $v" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val top = 20 * dp
        val bottom = height - 22 * dp
        val scale = max(max(limit, values.max()), 1) * 1.1f
        val slot = width / values.size.toFloat()
        val barW = min(slot * 0.6f, 34 * dp)

        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val h = max((bottom - top) * v / scale, 2 * dp)
            barPaint.color = context.getColor(
                when {
                    i == values.lastIndex -> R.color.accent
                    v > limit -> R.color.over
                    else -> R.color.bar
                }
            )
            canvas.drawRoundRect(cx - barW / 2, bottom - h, cx + barW / 2, bottom, 4 * dp, 4 * dp, barPaint)
            canvas.drawText(v.toString(), cx, bottom - h - 5 * dp, textPaint)
            canvas.drawText(labels[i], cx, height - 4 * dp, textPaint)
        }

        val y = bottom - (bottom - top) * limit / scale
        linePath.reset()
        linePath.moveTo(0f, y)
        linePath.lineTo(width.toFloat(), y)
        canvas.drawPath(linePath, linePaint)
    }
}
