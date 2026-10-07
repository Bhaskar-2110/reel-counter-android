package com.bhaskar.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The floating "dynamic island": a clear-glass circle with animated [Eyes] and a rolling reel
 * count under them. It grows out of a dot when shown, wobbles like a drop of liquid when the
 * count or mood changes, crackles with lightning when electric, and shakes when angry.
 *
 * The view fills a fixed-size overlay window and draws the circle at [pillCenterY], so the
 * window never has to be resized while the circle morphs.
 */
class IslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Vertical center of the circle in this view; negative means the middle of the view. */
    var pillCenterY = -1f

    constructor(context: Context, pillCenterY: Float) : this(context) {
        this.pillCenterY = pillCenterY
    }

    /**
     * True when a real backdrop blur sits behind the circle (see ReelCounterService), so the
     * glass can be a light frosted tint. Without blur, the glass is darker to stay readable.
     */
    var frosted = false

    private val dp = resources.displayMetrics.density
    private val eyes = Eyes()
    private val baseD = BASE_D_DP * dp

    private var count = 0
    private var previousCount = 0
    private var roll = 1f

    private var appear = 0f
    private var appearV = 0f
    private var appearTarget = 1f
    private var wobble = 0f
    private var wobbleV = 0f
    private var shake = 0f
    private var nextAngryShake = 0f
    private var onHidden: (() -> Unit)? = null
    private var lastFrame = 0L
    private var boltsAt = 0f
    private val bolts = Array(2) { FloatArray(14) }

    private val rect = RectF()
    private val innerRect = RectF()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(16 * dp, 0f, 5 * dp, 0x4D000000)
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenTop = Float.NaN
    private var sheenBottom = Float.NaN
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, dp * 0.8f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * dp
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
        setShadowLayer(4 * dp, 0f, 1 * dp, 0x73000000)
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFE45C.toInt()
        setShadowLayer(6 * dp, 0f, 0f, 0xFF22E5FF.toInt())
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val boltPath = Path()

    /** Updates what the island shows; changes are animated. */
    fun setState(count: Int, mood: Mood, tiredness: Float) {
        if (count != this.count) {
            previousCount = this.count
            this.count = count
            roll = if (appear < 0.5f) 1f else 0f
            wobbleV += 170 * dp
        }
        if (mood != eyes.mood) {
            wobbleV += 260 * dp
            if (mood == Mood.ANGRY) shake = 1f
        }
        eyes.mood = mood
        eyes.tiredness = tiredness
    }

    /** Shrinks the island back into a dot, then calls [then]. */
    fun dismiss(then: () -> Unit) {
        appearTarget = 0f
        onHidden = then
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        step(dt)

        if (appearTarget == 0f && appear < 0.03f && abs(appearV) < 2f) {
            onHidden?.invoke()
            onHidden = null
            return
        }

        // Grows from a dot to a full circle; wobble squashes it like a drop of liquid.
        val a = appear.coerceAtLeast(0f)
        val d = baseD * (0.15f + 0.85f * min(a, 1.2f))
        val w = (d + wobble).coerceIn(d * 0.75f, d * 1.3f)
        val h = (d - wobble * 0.6f).coerceIn(d * 0.75f, d * 1.25f)
        val shakeX = when {
            eyes.electric > 0.3f -> (Random.nextFloat() * 2f - 1f) * 1.4f * dp * eyes.electric
            else -> sin(eyes.time * 58f) * 3.2f * dp * shake
        }
        val cx = width / 2f + shakeX
        val cy = if (pillCenterY < 0f) height / 2f else pillCenterY
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = min(w, h) / 2f
        val visible = (a * 1.4f).coerceIn(0f, 1f)

        // Soft drop shadow; its fill stays faint so the glass on top still looks see-through.
        shadowPaint.color = ((0x12 * visible).toInt() shl 24)
        canvas.drawRoundRect(rect, r, r, shadowPaint)

        // Frosted glass: a dark translucent base, tinted by mood, with a white sheen on top.
        // Clear glass: only a faint frost, tinted by mood. Without a real blur behind it, a touch
        // more shade keeps the eyes and number readable over bright video.
        glassPaint.color = Eyes.blend(
            Eyes.blend(if (frosted) 0x14FFFFFF else 0x26101018, 0x2622E5FF, eyes.electric),
            0x30FF3B30, eyes.anger,
        )
        glassPaint.alpha = (glassPaint.alpha * visible).toInt()
        canvas.drawRoundRect(rect, r, r, glassPaint)
        // Frosted sheen: a soft white tint that fades out downward.
        if (sheenTop != rect.top || sheenBottom != rect.bottom) {
            sheenTop = rect.top
            sheenBottom = rect.bottom
            sheenPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        sheenPaint.alpha = (255 * visible).toInt()
        canvas.drawRoundRect(rect, r, r, sheenPaint)

        // Mood glow around the edge, then a crisp 1px highlight border.
        val glowStrength = max(
            eyes.electric * (0.45f + Random.nextFloat() * 0.55f),
            eyes.anger * (0.6f + 0.4f * sin(eyes.time * 6f)),
        )
        if (glowStrength > 0.03f) {
            val glowColor = if (eyes.electric > eyes.anger) 0xFF22E5FF.toInt() else 0xFFFF3B30.toInt()
            glowPaint.color = glowColor
            glowPaint.alpha = (255 * glowStrength * visible).toInt()
            glowPaint.setShadowLayer(12 * dp, 0f, 0f, glowColor)
            canvas.drawRoundRect(rect, r, r, glowPaint)
        }
        // Crisp 1px refraction edge, plus a fainter inner highlight just inside it.
        borderPaint.color = 0xFFFFFFFF.toInt()
        borderPaint.alpha = (0x99 * visible).toInt()
        canvas.drawRoundRect(rect, r, r, borderPaint)
        val inset = 1.5f * dp
        innerRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        borderPaint.alpha = (0x2E * visible).toInt()
        canvas.drawRoundRect(innerRect, r - inset, r - inset, borderPaint)

        val content = ((a - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (content > 0f) drawContent(canvas, content)
        if (eyes.electric > 0.1f) drawBolts(canvas, dt)
        if (eyes.anger > 0.3f) drawVein(canvas)

        postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        eyes.update(dt)
        // Underdamped springs give the liquid overshoot.
        val appearAccel = 170f * (appearTarget - appear) - 15f * appearV
        appearV += appearAccel * dt
        appear += appearV * dt
        val wobbleAccel = -260f * wobble - 9f * wobbleV
        wobbleV += wobbleAccel * dt
        wobble += wobbleV * dt
        roll = min(1f, roll + dt * 5f)
        shake = max(0f, shake - dt * 1.6f)
        if (eyes.mood == Mood.ANGRY) {
            nextAngryShake -= dt
            if (nextAngryShake <= 0f) {
                shake = 0.7f
                nextAngryShake = 2.2f
            }
        }
    }

    /** Eyes in the upper part of the circle, the count centered underneath. */
    private fun drawContent(canvas: Canvas, alpha: Float) {
        val h = rect.height()
        eyes.draw(canvas, rect.centerX(), rect.centerY() - h * 0.06f, h * 0.29f, h * 0.25f, h * 0.06f, alpha)

        val numberColor = Eyes.blend(
            Eyes.blend(0xFFFFFFFF.toInt(), 0xFF7DF3FF.toInt(), eyes.electric),
            0xFFFF6B5E.toInt(), eyes.anger,
        )
        numberPaint.textSize = h * 0.24f
        numberPaint.color = numberColor
        val x = rect.centerX()
        val baseline = rect.centerY() + h * 0.36f
        val travel = h * 0.25f
        canvas.save()
        canvas.clipRect(rect.left, rect.centerY() + h * 0.14f, rect.right, rect.bottom - 2 * dp)
        val eased = 1f - (1f - roll) * (1f - roll)
        if (roll < 1f) {
            numberPaint.alpha = (255 * alpha * (1f - eased)).toInt()
            canvas.drawText(previousCount.toString(), x, baseline - travel * eased, numberPaint)
        }
        numberPaint.alpha = (255 * alpha * eased.coerceAtLeast(0.15f)).toInt()
        canvas.drawText(count.toString(), x, baseline + travel * (1f - eased), numberPaint)
        canvas.restore()
    }

    /** Zigzag lightning on both sides of the circle, re-drawn ~14 times a second. */
    private fun drawBolts(canvas: Canvas, dt: Float) {
        boltsAt -= dt
        if (boltsAt <= 0f) {
            boltsAt = 0.07f
            for ((i, pts) in bolts.withIndex()) {
                val side = if (i == 0) -1 else 1
                val startX = if (side < 0) rect.left - 4 * dp else rect.right + 4 * dp
                for (p in 0 until 7) {
                    pts[p * 2] = startX + side * p * 4.5f * dp + (Random.nextFloat() - 0.5f) * 3 * dp
                    pts[p * 2 + 1] = rect.centerY() + (if (p % 2 == 0) -1 else 1) * (3 + Random.nextFloat() * 7) * dp
                }
            }
        }
        boltPaint.alpha = (255 * eyes.electric * (0.4f + Random.nextFloat() * 0.6f)).toInt()
        for (pts in bolts) {
            boltPath.reset()
            boltPath.moveTo(pts[0], pts[1])
            for (p in 1 until 7) boltPath.lineTo(pts[p * 2], pts[p * 2 + 1])
            canvas.drawPath(boltPath, boltPaint)
        }
    }

    companion object {
        /** Diameter of the island circle. */
        const val BASE_D_DP = 56
    }

    /** A pulsing anger mark at the circle's top-right edge. */
    private fun drawVein(canvas: Canvas) {
        val pulse = 1f + 0.18f * sin(eyes.time * 9f)
        emojiPaint.textSize = 15 * dp * pulse
        emojiPaint.alpha = (255 * eyes.anger).toInt()
        canvas.drawText("💢", rect.right - 4 * dp, rect.top + 6 * dp, emojiPaint)
    }
}
