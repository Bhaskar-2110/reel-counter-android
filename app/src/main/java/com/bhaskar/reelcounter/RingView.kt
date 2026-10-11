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
 * Home's progress ring (design ".ring"): a track and an arc in the mood colour, starting at 12
 * o'clock with round ends. The arc sweeps in from empty when the screen opens, and changes to
 * the progress and colour glide.
 */
class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.attrColor(R.attr.rrTrack)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()
    private var shown = 0f
    private var target = 0f
    private var color = context.moodColor(0)
    private var shownColor = color
    private var lastFrame = 0L

    /** Sets how full the ring is (0..1) and its colour; both animate. */
    fun set(progress: Float, color: Int) {
        target = progress.coerceIn(0f, 1f)
        this.color = color
        if (CountStore.reduceMotion(context)) {
            shown = target
            shownColor = color
        }
        lastFrame = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val k = 1f - exp(-dt * 5f)
        shown += (target - shown) * k
        // Integer rounding can stall a blend one step short; then just land on the colour.
        val next = Mood.blend(shownColor, color, k * 1.5f)
        shownColor = if (next == shownColor) color else next

        // 16 of 258 in the design.
        val stroke = min(width, height) * 0.062f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val inset = stroke / 2f
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        box.set(left, top, left + size - 2 * inset, top + size - 2 * inset)
        canvas.drawArc(box, 0f, 360f, false, track)
        arc.color = shownColor
        if (shown > 0.003f) canvas.drawArc(box, -90f, 360f * shown, false, arc)

        if (kotlin.math.abs(target - shown) > 0.001f || shownColor != color) postInvalidateOnAnimation()
    }
}
