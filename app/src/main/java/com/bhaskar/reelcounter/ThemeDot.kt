package com.bhaskar.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A theme swatch in Settings → Appearance (design ".tdot"): a 46dp circle showing the theme,
 * ringed in the accent colour when it's the one in use.
 */
class ThemeDot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var theme = AppTheme.DARK
        set(value) {
            field = value
            shaderSize = 0f
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
    }
    private val box = RectF()
    private var shaderSize = 0f

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = s / 2 - ring.strokeWidth
        ring.color = context.attrColor(if (isSelected) R.attr.rrAccent else R.attr.rrLine)
        canvas.drawCircle(cx, cy, s / 2 - ring.strokeWidth / 2, ring)
        val inner = r - 3 * dp
        box.set(cx - inner, cy - inner, cx + inner, cy + inner)
        when (theme) {
            AppTheme.SYSTEM -> {
                fill.shader = null
                fill.color = DARK
                canvas.drawArc(box, 90f, 180f, true, fill)
                fill.color = LIGHT
                canvas.drawArc(box, 270f, 180f, true, fill)
            }
            AppTheme.ROYAL -> {
                if (shaderSize != s) {
                    shaderSize = s
                    fill.shader = LinearGradient(box.left, box.top, box.right, box.bottom,
                        intArrayOf(0xFF5A1A93.toInt(), 0xFF2A0B52.toInt(), 0xFFFFCB45.toInt()), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawCircle(cx, cy, inner, fill)
            }
            else -> {
                fill.shader = null
                fill.color = if (theme == AppTheme.DARK) DARK else LIGHT
                canvas.drawCircle(cx, cy, inner, fill)
            }
        }
    }

    private companion object {
        const val DARK = 0xFF0B0D17.toInt()
        const val LIGHT = 0xFFF7F6F2.toInt()
    }
}
