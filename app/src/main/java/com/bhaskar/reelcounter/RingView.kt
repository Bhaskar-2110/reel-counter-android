package com.bhaskar.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp
import kotlin.math.min

/**
 * The Today screen's progress ring: a track in the raised colour and an arc in the mood colour,
 * starting at 12 o'clock and running clockwise with round ends, like the app icon. Changes to
 * the progress and colour animate smoothly.
 */
class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9 * dp
        color = context.getColor(R.color.raised)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()
    private var shown = 0f
    private var target = 0f
    private var color = context.getColor(R.color.mood_calm)
    private var lastFrame = 0L

    /** Sets how full the ring is (0..1) and its colour; both animate. */
    fun set(progress: Float, color: Int) {
        target = progress.coerceIn(0f, 1f)
        this.color = color
        lastFrame = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        shown += (target - shown) * (1f - exp(-dt * 6f))

        val inset = track.strokeWidth / 2f
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        box.set(left, top, left + size - 2 * inset, top + size - 2 * inset)
        canvas.drawArc(box, 0f, 360f, false, track)
        arc.color = color
        if (shown > 0.003f) canvas.drawArc(box, -90f, 360f * shown, false, arc)

        if (kotlin.math.abs(target - shown) > 0.001f) postInvalidateOnAnimation()
    }
}
