package com.bhaskar.reelcounter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Mood { CURIOUS, ELECTRIC, ANGRY }

/**
 * A pair of cartoon eyes that act out a [Mood]. Call [update] once per frame with the elapsed
 * seconds, then [draw]. Every feature eases toward per-mood targets, so switching moods
 * morphs instead of snapping.
 *
 * - Curious: wide, sparkly, glancing around and blinking; lids and brows droop with [tiredness].
 * - Electric: very wide, spinning spiral pupils, jittering.
 * - Angry: slanted lids, brows slammed down, red irises staring straight ahead.
 */
class Eyes {
    var mood = Mood.CURIOUS

    /** 0 = fresh, 1 = at the limit. Makes curious eyes slowly look worried. */
    var tiredness = 0f

    /** 0..1 blend toward each mood, for anything drawn around the eyes. */
    var electric = 0f
        private set
    var anger = 0f
        private set
    var time = 0f
        private set

    private var open = 1f
    private var pupil = 1f
    private var browRaise = 0f
    private var browTilt = 0f
    private var browAlpha = 0.7f
    private var lookX = 0f
    private var lookY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var nextLook = 0.4f
    private var blinkAt = 1.5f
    private var blinkT = -1f
    private var spin = 0f
    private var jitterX = 0f
    private var jitterY = 0f

    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val spiralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val browPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val browPath = Path()
    private val mouthPath = Path()
    private val shine = RectF()
    private val eyePath = Path()
    private val lidPath = Path()
    private val spiralPath = Path()
    private val oval = RectF()

    fun update(dt: Float) {
        time += dt
        val ease = 1f - exp(-dt * 9f)
        val tired = tiredness.coerceIn(0f, 1f)

        val openTarget: Float
        val pupilTarget: Float
        val raiseTarget: Float
        val tiltTarget: Float
        val browAlphaTarget: Float
        when (mood) {
            Mood.CURIOUS -> {
                openTarget = 1f - 0.28f * tired
                pupilTarget = 1.15f - 0.25f * tired
                raiseTarget = 0.7f - 0.5f * tired
                tiltTarget = -8f - 10f * tired
                browAlphaTarget = 0.55f + 0.3f * tired
            }
            Mood.ELECTRIC -> {
                openTarget = 1.12f
                pupilTarget = 0.45f
                raiseTarget = 1.2f
                tiltTarget = 0f
                browAlphaTarget = 0.9f
            }
            Mood.ANGRY -> {
                openTarget = 0.8f
                pupilTarget = 0.7f
                raiseTarget = -0.6f
                tiltTarget = 26f
                browAlphaTarget = 1f
            }
        }
        open += (openTarget - open) * ease
        pupil += (pupilTarget - pupil) * ease
        browRaise += (raiseTarget - browRaise) * ease
        browTilt += (tiltTarget - browTilt) * ease
        browAlpha += (browAlphaTarget - browAlpha) * ease
        electric += ((if (mood == Mood.ELECTRIC) 1f else 0f) - electric) * ease
        anger += ((if (mood == Mood.ANGRY) 1f else 0f) - anger) * ease

        nextLook -= dt
        if (nextLook <= 0f) {
            when (mood) {
                Mood.CURIOUS -> {
                    targetX = Random.nextFloat() * 2f - 1f
                    targetY = (Random.nextFloat() * 2f - 1f) * 0.6f
                    nextLook = 0.6f + Random.nextFloat() * 1.8f
                }
                Mood.ELECTRIC -> {
                    targetX = Random.nextFloat() * 2f - 1f
                    targetY = Random.nextFloat() * 2f - 1f
                    nextLook = 0.05f + Random.nextFloat() * 0.08f
                }
                Mood.ANGRY -> {
                    targetX = 0f
                    targetY = 0.2f
                    nextLook = 1f
                }
            }
        }
        val gaze = 1f - exp(-dt * if (mood == Mood.ELECTRIC) 40f else 16f)
        lookX += (targetX - lookX) * gaze
        lookY += (targetY - lookY) * gaze

        if (blinkT >= 0f) {
            blinkT += dt
            if (blinkT > BLINK_SECONDS) {
                blinkT = -1f
                blinkAt = time + when {
                    mood == Mood.CURIOUS && Random.nextFloat() < 0.2f -> 0.22f // a cute double blink
                    mood == Mood.CURIOUS -> 1.8f + Random.nextFloat() * 3f
                    else -> 3.5f + Random.nextFloat() * 4f
                }
            }
        } else if (time >= blinkAt && mood != Mood.ELECTRIC) {
            blinkT = 0f
        }

        spin += dt * 10f
        if (electric > 0.05f) {
            jitterX = (Random.nextFloat() * 2f - 1f) * electric
            jitterY = (Random.nextFloat() * 2f - 1f) * electric
        } else {
            jitterX = 0f
            jitterY = 0f
        }
    }

    /** Draws both eyes centered on ([cx], [cy]); each eye is [eyeW] × [eyeH], [gap] apart. */
    fun draw(canvas: Canvas, cx: Float, cy: Float, eyeW: Float, eyeH: Float, gap: Float, alpha: Float = 1f) {
        // Soft light glow around each eye: white normally, cyan when electric, red when angry.
        val glow = blend(blend(0x99FFFFFF.toInt(), 0xFF22E5FF.toInt(), electric), 0xDDFF3B30.toInt(), anger)
        scleraPaint.setShadowLayer(eyeW * 0.3f, 0f, 0f, glow)
        scleraPaint.alpha = (255 * alpha).toInt()
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * (gap + eyeW) / 2f
            drawEye(canvas, ex, cy, eyeW, eyeH, side, alpha)
            drawBrow(canvas, ex, cy, eyeW, eyeH, side, alpha)
        }
    }

    /**
     * A small mouth under the eyes: a smile when curious, a jittery zigzag when electric, a
     * frown when angry. [width] is the mouth's width.
     */
    fun drawMouth(canvas: Canvas, cx: Float, cy: Float, width: Float, alpha: Float = 1f) {
        mouthPaint.strokeWidth = width * 0.09f
        mouthPaint.color = blend(0xFFB8B8B8.toInt(), 0xFFFF6B5E.toInt(), anger)
        mouthPaint.alpha = (255 * alpha).toInt()
        val half = width / 2f
        mouthPath.reset()
        if (electric > 0.5f) {
            val steps = 6
            for (i in 0..steps) {
                val px = cx - half + width * i / steps
                val py = cy + (if (i % 2 == 0) -1 else 1) * width * 0.07f + jitterY * width * 0.04f
                if (i == 0) mouthPath.moveTo(px, py) else mouthPath.lineTo(px, py)
            }
        } else {
            // Curve bends down for a smile and up for a frown; tiredness flattens it.
            val bend = width * (0.28f * (1f - 0.7f * tiredness.coerceIn(0f, 1f)) * (1f - anger) - 0.24f * anger)
            mouthPath.moveTo(cx - half, cy)
            mouthPath.quadTo(cx, cy + bend, cx + half, cy)
        }
        canvas.drawPath(mouthPath, mouthPaint)
    }

    private fun drawEye(canvas: Canvas, ex: Float, ey: Float, w: Float, h: Float, side: Int, alpha: Float) {
        val blink = if (blinkT >= 0f) sin(PI * blinkT / BLINK_SECONDS).toFloat() else 0f
        val openNow = (open * (1f - blink * 0.94f)).coerceAtLeast(0.06f)
        val eh = h * openNow
        val x = ex + jitterX * w * 0.1f
        val y = ey + jitterY * h * 0.08f
        oval.set(x - w / 2f, y - eh / 2f, x + w / 2f, y + eh / 2f)

        eyePath.reset()
        eyePath.addOval(oval, Path.Direction.CW)
        if (anger > 0.02f) {
            // A slanted lid: the inner corner (toward the nose) drops the most.
            val drop = anger * h * 0.42f
            val innerX = x - side * w / 2f
            val outerX = x + side * w / 2f
            lidPath.reset()
            lidPath.moveTo(outerX, oval.top - h * 0.1f + drop * 0.1f)
            lidPath.lineTo(innerX, oval.top + drop)
            lidPath.lineTo(innerX, oval.bottom + h)
            lidPath.lineTo(outerX, oval.bottom + h)
            lidPath.close()
            eyePath.op(lidPath, Path.Op.INTERSECT)
        }

        // Glowing white eyeball that shades to soft grey at the rim.
        scleraPaint.shader = RadialGradient(
            x, y, w * 0.56f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE2E2E2.toInt(), 0xFFA9A9A9.toInt()),
            floatArrayOf(0f, 0.55f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(eyePath, scleraPaint)

        canvas.save()
        canvas.clipPath(eyePath)
        val irisR = w * 0.31f * (0.8f + 0.2f * pupil)
        val ix = x + lookX * w * 0.18f
        val iy = y + lookY * eh * 0.16f + anger * h * 0.06f
        val a = (255 * alpha).toInt()

        val plain = 1f - electric
        if (plain > 0.02f) {
            // Big black pupil with a faint inner ring, and a tiny highlight toward the outside.
            pupilPaint.alpha = (a * plain).toInt()
            canvas.drawCircle(ix, iy, irisR, pupilPaint)
            ringPaint.strokeWidth = irisR * 0.12f
            ringPaint.color = blend(0xFF2E2E2E.toInt(), 0xFF7A1F1A.toInt(), anger)
            ringPaint.alpha = (a * plain).toInt()
            canvas.drawCircle(ix, iy, irisR * 0.62f, ringPaint)
            val sx = ix + side * irisR * 0.5f
            shine.set(sx - irisR * 0.17f, iy - irisR * 0.07f, sx + irisR * 0.17f, iy + irisR * 0.09f)
            shinePaint.alpha = (a * plain).toInt()
            canvas.drawOval(shine, shinePaint)
        }
        if (electric > 0.02f) {
            spiralPath.reset()
            val turns = 2.6f
            val steps = 48
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val ang = t * turns * 2f * PI.toFloat() + spin * side
                val r = irisR * 1.15f * t
                val px = ix + cos(ang) * r
                val py = iy + sin(ang) * r
                if (i == 0) spiralPath.moveTo(px, py) else spiralPath.lineTo(px, py)
            }
            spiralPaint.strokeWidth = w * 0.08f
            spiralPaint.color = 0xFF050505.toInt()
            spiralPaint.alpha = (a * electric).toInt()
            canvas.drawPath(spiralPath, spiralPaint)
        }
        canvas.restore()

        // Dark lid shadow hugging the top of the eye.
        if (anger < 0.5f) {
            lidPaint.strokeWidth = w * 0.09f
            lidPaint.color = 0xFF2A2A2A.toInt()
            lidPaint.alpha = (200 * alpha * (1f - anger * 2f)).toInt().coerceIn(0, 255)
            val pad = w * 0.06f
            oval.set(x - w / 2f - pad, y - eh / 2f - pad, x + w / 2f + pad, y + eh / 2f + pad)
            canvas.drawArc(oval, 200f, 140f, false, lidPaint)
        }
    }

    /** A thick, arched brow: dark grey, turning red when angry. */
    private fun drawBrow(canvas: Canvas, ex: Float, ey: Float, w: Float, h: Float, side: Int, alpha: Float) {
        val length = w * 1.05f
        val thick = h * 0.16f
        val bx = ex + jitterX * w * 0.12f
        val by = ey - h * 0.5f * open - h * 0.24f - browRaise * h * 0.12f + anger * h * 0.18f +
            if (electric > 0.05f) jitterY * h * 0.1f else 0f
        browPaint.color = blend(0xFF5C5C5C.toInt(), 0xFFD9443A.toInt(), anger)
        browPaint.alpha = (255 * browAlpha.coerceAtLeast(0.75f) * alpha).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.rotate(-side * browTilt, bx, by)
        // Crescent: a high arc on top, a shallower one underneath.
        val l = bx - length / 2f
        val r = bx + length / 2f
        browPath.reset()
        browPath.moveTo(l, by + thick * 0.4f)
        browPath.quadTo(bx, by - thick * 1.3f, r, by + thick * 0.4f)
        browPath.quadTo(bx, by - thick * 0.1f, l, by + thick * 0.4f)
        browPath.close()
        canvas.drawPath(browPath, browPaint)
        canvas.restore()
    }

    companion object {
        private const val BLINK_SECONDS = 0.16f

        /** Mixes two ARGB colors; [t] = 0 gives [a], 1 gives [b]. */
        fun blend(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
