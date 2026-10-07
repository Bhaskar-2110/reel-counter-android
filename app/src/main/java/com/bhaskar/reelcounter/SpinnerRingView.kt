package com.bhaskar.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The pause screen's "breathing" ring: a quiet track with a short arc that turns once every six
 * seconds. It's there to slow you down, not to count down like a bomb.
 */
class SpinnerRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        color = context.getColor(R.color.night_raised)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.film_muted)
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val inset = track.strokeWidth / 2f
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        box.set(left, top, left + size - 2 * inset, top + size - 2 * inset)
        canvas.drawArc(box, 0f, 360f, false, track)
        val angle = (SystemClock.uptimeMillis() % 6000L) / 6000f * 360f - 90f
        canvas.drawArc(box, angle, 90f, false, arc)
        postInvalidateOnAnimation()
    }
}
