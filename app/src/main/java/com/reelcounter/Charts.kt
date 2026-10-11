package com.reelcounter

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shared animation for the charts: bars grow in when new data arrives. */
private fun View.growIn(onFrame: (Float) -> Unit): ValueAnimator? {
    if (CountStore.reduceMotion(context)) {
        onFrame(1f)
        return null
    }
    return ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 600
        interpolator = Ui.EASE
        addUpdateListener { onFrame(it.animatedValue as Float) }
        start()
    }
}

/**
 * The design's week bars (".bars"): one rounded bar per day in its own colour, a day label
 * under each, and a dashed line at the daily limit labelled "limit 30".
 */
class WeekBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values = IntArray(0)
    private var labels: List<String> = emptyList()
    private var colors = IntArray(0)
    private var limit = 0
    private var maxValue = 1
    private var grow = 1f
    private var anim: ValueAnimator? = null

    private val dp = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        color = context.attrColor(R.attr.rrText3)
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        pathEffect = DashPathEffect(floatArrayOf(5 * dp, 4 * dp), 0f)
        color = context.moodColor(4)
        alpha = 180
    }
    private val limitText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.RIGHT
        color = context.moodColor(4)
        isFakeBoldText = true
    }
    private val box = RectF()

    /** [limit] 0 hides the limit line; [maxValue] is the top of the scale. */
    fun setData(values: IntArray, labels: List<String>, colors: IntArray, limit: Int, maxValue: Int = 0, animate: Boolean = true) {
        this.values = values
        this.labels = labels
        this.colors = colors
        this.limit = limit
        this.maxValue = max(1, if (maxValue > 0) maxValue else max(values.maxOrNull() ?: 0, limit) * 5 / 4)
        contentDescription = labels.zip(values.toList()).joinToString { (l, v) -> "$l $v" }
        anim?.cancel()
        if (animate) anim = growIn { grow = it; invalidate() } else grow = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val labelH = 16 * dp
        val bottom = height - labelH
        val top = 4 * dp
        val gap = 8 * dp
        val barW = (width - gap * (values.size - 1)) / values.size
        val radius = 6 * dp
        values.forEachIndexed { i, v ->
            val x = i * (barW + gap)
            val h = (bottom - top - 4 * dp) * min(v, maxValue) / maxValue.toFloat() * grow
            bar.color = colors.getOrElse(i) { context.attrColor(R.attr.rrAccent) }
            if (h > 0.5f) {
                box.set(x, bottom - 4 * dp - h, x + barW, bottom - 4 * dp)
                canvas.drawRoundRect(box, min(radius, barW / 2), min(radius, barW / 2), bar)
            }
            labels.getOrNull(i)?.let { canvas.drawText(it, x + barW / 2, height - 3 * dp, text) }
        }
        if (limit in 1..maxValue) {
            val y = bottom - 4 * dp - (bottom - top - 4 * dp) * limit / maxValue.toFloat()
            canvas.drawLine(0f, y, width.toFloat(), y, limitPaint)
            canvas.drawText(context.getString(R.string.chart_limit, limit), width.toFloat(), y - 4 * dp, limitText)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

/** "When reels get you": 24 thin bars, one per hour, the heaviest stretch in amber. */
class HoursView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values = IntArray(24)
    private var highlight: Set<Int> = emptySet()
    private var grow = 1f
    private var anim: ValueAnimator? = null
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    fun setData(values: IntArray, highlight: Set<Int>) {
        this.values = values
        this.highlight = highlight
        contentDescription = values.withIndex().filter { it.value > 0 }.joinToString { "${it.index}:00 ${it.value}" }
        anim?.cancel()
        anim = growIn { grow = it; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val gap = 2 * dp
        val w = (width - gap * (n - 1)) / n
        val maxV = max(1, values.maxOrNull() ?: 0)
        val hot = context.moodColor(2)
        val cold = context.attrColor(R.attr.rrRaised)
        values.forEachIndexed { i, v ->
            val h = max(height * 0.06f, height * v / maxV.toFloat()) * grow
            paint.color = if (i in highlight && v > 0) hot else cold
            val x = i * (w + gap)
            box.set(x, height - h, x + w, height.toFloat())
            canvas.drawRoundRect(box, 2 * dp, 2 * dp, paint)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * "This month" as a calendar (design ".cal"): weekday initials, then one square per day, mint
 * when under the limit, red when over, today outlined, days still to come dashed.
 */
class CalendarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** One per day of the month: reels, or null for a day still to come (or before install). */
    private var days: List<Int?> = emptyList()
    private var firstWeekday = 0
    private var today = -1
    private var limit = 1
    private val headers = listOf("M", "T", "W", "T", "F", "S", "S")

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * dp
        pathEffect = DashPathEffect(floatArrayOf(3 * dp, 3 * dp), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val box = RectF()
    private val gap get() = 5 * dp

    /** [firstWeekday] 0 = Monday; [today] is the 0-based index of today, or -1. */
    fun setData(days: List<Int?>, firstWeekday: Int, today: Int, limit: Int) {
        this.days = days
        this.firstWeekday = firstWeekday
        this.today = today
        this.limit = max(1, limit)
        requestLayout()
        invalidate()
    }

    private fun rows() = (firstWeekday + days.size + 6) / 7

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cell = (w - gap * 6) / 7
        val h = (16 * dp + rows() * (cell + gap)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val cell = (width - gap * 6) / 7
        head.color = context.attrColor(R.attr.rrText3)
        headers.forEachIndexed { i, h -> canvas.drawText(h, i * (cell + gap) + cell / 2, 11 * dp, head) }
        val calm = context.moodColor(0)
        val angry = context.moodColor(4)
        val fg = context.attrColor(R.attr.rrText)
        val faint = context.attrColor(R.attr.rrText3)
        dashed.color = context.attrColor(R.attr.rrLine)
        outline.color = fg
        days.forEachIndexed { i, v ->
            val slot = firstWeekday + i
            val x = (slot % 7) * (cell + gap)
            val y = 16 * dp + (slot / 7) * (cell + gap)
            box.set(x, y, x + cell, y + cell)
            val r = 8 * dp
            if (v == null) {
                box.inset(dashed.strokeWidth / 2, dashed.strokeWidth / 2)
                canvas.drawRoundRect(box, r, r, dashed)
                text.color = faint
            } else {
                val over = v >= limit
                val k = if (over) 0.4f + 0.3f * min(1f, (v - limit) / 10f) else (v / limit.toFloat()).coerceIn(.18f, .9f) * .55f
                fill.color = withAlpha(if (over) angry else calm, k)
                canvas.drawRoundRect(box, r, r, fill)
                text.color = if (over) 0xFFFFFFFF.toInt() else fg
                if (i == today) {
                    box.inset(1 * dp, 1 * dp)
                    canvas.drawRoundRect(box, r, r, outline)
                }
            }
            canvas.drawText((i + 1).toString(), x + cell / 2, y + cell / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

/** The pause screen's countdown ring: a track and an arc that empties as the seconds pass. */
class TimerRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var color = 0xFFFF7AD9.toInt()
    private var share = 1f
    private var anim: ValueAnimator? = null
    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7 * dp
        color = context.getColor(R.color.night_raised)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    /** Runs the ring down from full to empty over [seconds]. */
    fun start(seconds: Int) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = seconds * 1000L
            interpolator = null
            addUpdateListener { share = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = track.strokeWidth / 2
        val s = min(width, height).toFloat()
        box.set((width - s) / 2 + inset, (height - s) / 2 + inset, (width + s) / 2 - inset, (height + s) / 2 - inset)
        canvas.drawArc(box, 0f, 360f, false, track)
        arc.color = color
        if (share > 0.002f) canvas.drawArc(box, -90f, 360f * share, false, arc)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}
