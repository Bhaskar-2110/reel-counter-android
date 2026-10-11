package com.bhaskar.reelcounter

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Lays its children out in the largest centred square that fits (up to [maxSizeDp]), so Home's
 * ring and Iris shrink on short screens instead of pushing anything into a scroll.
 */
class SquareFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var maxSizeDp = 320f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val side = minOf(w, h, Ui.dp(context, maxSizeDp))
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        // Children tagged "inner" (Iris) take [INNER] of the square: her face then fills most
        // of the ring without touching it.
        val inner = MeasureSpec.makeMeasureSpec((side * INNER).toInt(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.tag == "inner") c.measure(inner, inner) else c.measure(spec, spec)
        }
    }

    private companion object {
        const val INNER = 0.94f
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = (width - c.measuredWidth) / 2
            val y = (height - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}
