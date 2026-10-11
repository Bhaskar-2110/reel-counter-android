package com.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Where things sit on Iris's island, in pixels, relative to its centre. */
class IrisLayout(
    val w: Float,
    val h: Float,
    val corner: Float,
    /** Distance from the centre line to each eye's centre. */
    val eyeX: Float,
    val eyeY: Float,
    val eyeW: Float,
    val eyeH: Float,
    /** Size that motion is measured in (the spec's S). */
    val s: Float,
    val numY: Float = .27f * s,
    val numSize: Float = .2f * s,
) {
    companion object {
        /** The spec's S × S island; without a number (in the app) the eyes sit in the middle. */
        fun square(size: Float, withNumber: Boolean = false) = IrisLayout(
            // Rounder than the spec's 0.30, for a jelly-bean feel.
            w = size, h = size, corner = .36f * size,
            eyeX = .175f * size, eyeY = if (withNumber) -.1f * size else 0f,
            eyeW = .28f * size, eyeH = .36f * size, s = size,
        )
    }
}

/** The line under the eyes on the floating counter, rolling between count and time. */
class NumberLine {
    var text = ""
    var previous = ""

    /** 0 = just switched, 1 = settled. */
    var roll = 1f
    var timer = false
}

/**
 * Draws an [IrisRig] (design_iris_docs/IRIS_EYES_SPEC.md, ported from the demo's `draw` and
 * `drawEye`). No brows, mouths, emoji or outlines: every feeling is lids, pupils and posture.
 * Paths and paints are made once and reused every frame.
 */
class IrisRenderer(context: Context) {

    private val bricolage: Typeface =
        ResourcesCompat.getFont(context, R.font.bricolage_grotesque) ?: Typeface.DEFAULT_BOLD

    private val island = RectF()
    private val r = RectF()
    private val islandPath = Path()
    private val eyeClip = Path()
    private val lidPath = Path()
    private val line = Path()
    private val drop = Path()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val roundStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFF6B0.toInt()
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bricolage
        fontVariationSettings = "'wght' 800"
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bricolage
        fontVariationSettings = "'wght' 700"
        color = SLEEPY_Z
    }

    // Shaders only change with the colour or size, so they're rebuilt only then.
    private var shaderColor = 0
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var softColor = 0
    private var softRadius = 0f
    private var softAlpha = -1f
    private var shaderW = 0f
    private var shaderH = 0f

    /**
     * Draws [rig] with its island centred at ([cx], [cy]). [grow] scales the whole island (the
     * floating counter grows out of a dot), [visible] fades it and [number] is the line under
     * the eyes, if any. With a [softGlow] radius the mood glow is a round haze of that size
     * fading to nothing at its edge (in the app, so no square edge shows), instead of the
     * glow hugging the island.
     */
    fun draw(
        canvas: Canvas,
        rig: IrisRig,
        l: IrisLayout,
        cx: Float,
        cy: Float,
        grow: Float = 1f,
        visible: Float = 1f,
        number: NumberLine? = null,
        softGlow: Float = 0f,
    ) {
        if (visible <= 0.01f) return
        val s = l.s
        val color = rig.color
        val layer = if (visible < 0.99f) canvas.saveLayerAlpha(null, (255 * visible).toInt()) else -1

        if (softGlow > 0f) drawSoftGlow(canvas, cx, cy, softGlow, color, rig.glow.x)

        canvas.save()
        canvas.translate(cx + rig.fx.x * s, cy + rig.fy.x * s)
        canvas.rotate(Math.toDegrees(rig.fr.x.toDouble()).toFloat())
        val fs = rig.fs.x * grow
        canvas.scale(fs * (1 + rig.sq.x), fs * (1 - rig.sq.x))
        // Jelly lean: the base stays put and the top sways (a skew around the bottom edge).
        canvas.translate(0f, l.h / 2)
        canvas.skew(rig.jelly.x, 0f)
        canvas.translate(0f, -l.h / 2)

        island.set(-l.w / 2, -l.h / 2, l.w / 2, l.h / 2)
        val corner = min(l.corner, min(l.w, l.h) / 2)
        islandPath.reset()
        islandPath.addRoundRect(island, corner, corner, Path.Direction.CW)

        drawBody(canvas, rig, l, color, hugGlow = softGlow <= 0f)

        canvas.save()
        canvas.clipPath(islandPath)
        for (i in 0..1) drawEye(canvas, rig, l, i)
        drawTears(canvas, rig, l)
        if (number != null) drawNumber(canvas, rig, l, color, number)
        canvas.restore()

        if (rig.mood == Mood.ELECTRIC && !rig.reduceMotion) drawArcs(canvas, l)
        canvas.restore()

        if (rig.mood == Mood.SLEEPY) drawZs(canvas, rig, l, cx, cy)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** Dark glass with a mood-colour sheen, rim and glow. */
    private fun drawBody(canvas: Canvas, rig: IrisRig, l: IrisLayout, color: Int, hugGlow: Boolean) {
        val s = l.s
        if (color != shaderColor || l.w != shaderW || l.h != shaderH) {
            shaderColor = color
            shaderW = l.w
            shaderH = l.h
            bodyPaint.shader = LinearGradient(0f, -l.h / 2, 0f, l.h / 2, 0xFF1D2238.toInt(), 0xFF0F1220.toInt(), Shader.TileMode.CLAMP)
            sheenPaint.shader = RadialGradient(
                -.15f * l.w, -.42f * l.h, .7f * s,
                withAlpha(color, .16f), withAlpha(color, 0f), Shader.TileMode.CLAMP,
            )
            rimPaint.shader = rimShader(l, color)
        }
        if (hugGlow) {
            glowPaint.color = 0xFF12152A.toInt()
            glowPaint.setShadowLayer(.22f * s * BLUR, 0f, 0f, withAlpha(color, .38f * rig.glow.x.coerceIn(0f, 2f)))
            canvas.drawPath(islandPath, glowPaint)
        }
        canvas.drawPath(islandPath, bodyPaint)
        canvas.drawPath(islandPath, sheenPaint)
        rimPaint.strokeWidth = .022f * s
        canvas.drawPath(islandPath, rimPaint)
        canvas.save()
        canvas.clipPath(islandPath)
        fill.shader = null
        fill.color = 0x0DFFFFFF
        r.set(-.38f * l.w, -.47f * l.h, .38f * l.w, -.37f * l.h)
        canvas.drawRoundRect(r, .05f * s, .05f * s, fill)
        canvas.restore()
    }

    /** A round mood-colour haze behind the island, strongest in the middle, gone at [radius]. */
    private fun drawSoftGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, glow: Float) {
        val a = .42f * glow.coerceIn(0f, 2f)
        if (color != softColor || radius != softRadius || a != softAlpha) {
            softColor = color
            softRadius = radius
            softAlpha = a
            softPaint.shader = RadialGradient(
                0f, 0f, radius,
                intArrayOf(withAlpha(color, a), withAlpha(color, a * .55f), withAlpha(color, a * .18f), withAlpha(color, 0f)),
                floatArrayOf(0f, .45f, .75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, radius, softPaint)
        canvas.restore()
    }

    private fun rimShader(l: IrisLayout, color: Int) = LinearGradient(
        -l.w / 2, -l.h / 2, l.w / 2, l.h / 2,
        intArrayOf(withAlpha(color, .95f), withAlpha(color, .35f), withAlpha(color, .8f)),
        floatArrayOf(0f, .55f, 1f),
        Shader.TileMode.CLAMP,
    )

    /** One eye: lids from two curves, a rounded-square pupil, sparkles, lid shadows and a wet rim. */
    private fun drawEye(canvas: Canvas, rig: IrisRig, l: IrisLayout, i: Int) {
        val e = rig.eyes[i]
        val side = if (i == 0) -1f else 1f
        val s = l.s
        val gx = rig.gx.x
        val gy = rig.gy.x
        val ex = side * l.eyeX + gx * s * .02f
        val ey = l.eyeY + gy * s * .015f
        val w = l.eyeW * e.sx.x
        val h = l.eyeH * e.sy.x
        val w2 = w / 2 + 1
        val b = rig.blink

        val top = -h / 2
        val bot = h / 2
        val base = top + e.open.x * h
        var yIn = base + e.tilt.x * h * .5f
        var yOut = base - e.tilt.x * h * .5f
        var yB = bot - e.low.x * h
        var uc = (yIn + yOut) / 2 + e.upC.x * h * 2
        var lc = yB - e.lowC.x * h * 1.4f
        if (b > 0f) {
            val m = lerp((yIn + yOut) / 2, yB, .7f)
            val kc = -e.lowC.x * h * .35f + e.upC.x * h * .5f
            yIn = lerp(yIn, m, b); yOut = lerp(yOut, m, b); uc = lerp(uc, m + kc, b)
            yB = lerp(yB, m, b); lc = lerp(lc, m + kc, b)
        }
        // The right eye's inner corner is on its left.
        val yL = if (side > 0) yIn else yOut
        val yR = if (side > 0) yOut else yIn

        canvas.save()
        canvas.translate(ex, ey)
        eyeClip.reset()
        r.set(-w / 2, -h / 2, w / 2, h / 2)
        val er = min(w, h) * .39f
        eyeClip.addRoundRect(r, er, er, Path.Direction.CW)
        canvas.clipPath(eyeClip)
        lidPath.reset()
        lidPath.moveTo(-w2, yL)
        lidPath.quadTo(0f, uc, w2, yR)
        lidPath.lineTo(w2, yB)
        lidPath.quadTo(0f, lc, -w2, yB)
        lidPath.close()
        canvas.clipPath(lidPath)

        // White, warming to pink and red from the top when angry.
        val tint = rig.tint.x.coerceIn(0f, 1f)
        fill.shader = null
        fill.color = Mood.blend(EYE_WHITE, ANGRY_WHITE, tint)
        canvas.drawRect(-w, -h, w, h, fill)
        if (tint > .01f) {
            fill.shader = LinearGradient(
                0f, uc - h * .1f, 0f, uc + h * .45f,
                withAlpha(ANGRY_RED, .5f * tint), withAlpha(ANGRY_RED, 0f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(-w, -h, w, h, fill)
            fill.shader = null
        }

        // Pupil and sparkles.
        val pz = e.pup.x
        val pw = min(w * .56f * pz, w * .9f)
        val ph = min(h * .55f * pz, h * .88f)
        val px = gx.coerceIn(-1f, 1f) * (w - pw) / 2 * .85f
        val py = gy.coerceIn(-1f, 1f) * (h - ph) / 2 * .85f
        fill.color = PUPIL
        r.set(px - pw / 2, py - ph / 2, px + pw / 2, py + ph / 2)
        val pr = min(pw, ph) * .42f
        canvas.drawRoundRect(r, pr, pr, fill)
        val hl = max(.2f, rig.hl.x)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(px + pw * .2f - gx * pw * .04f, py - ph * .2f, min(pw, ph) * .2f * hl, fill)
        canvas.drawCircle(px - pw * .2f, py + ph * .24f, min(pw, ph) * .085f * hl, fill)

        // Soft shadow under the lids.
        line.reset(); line.moveTo(-w2, yL); line.quadTo(0f, uc, w2, yR)
        stroke.color = 0x380B0D17; stroke.strokeWidth = w * .16f; stroke.shader = null
        canvas.drawPath(line, stroke)
        line.reset(); line.moveTo(-w2, yB); line.quadTo(0f, lc, w2, yB)
        stroke.color = 0x1A0B0D17; stroke.strokeWidth = w * .1f
        canvas.drawPath(line, stroke)

        // Wet lower rim with a glint (sad, pleading, a little worried).
        val wet = rig.wet.x.coerceIn(0f, 1f)
        if (wet > .02f) {
            stroke.color = withAlpha(WET_BLUE, .6f * wet); stroke.strokeWidth = h * .2f * wet
            canvas.drawPath(line, stroke)
            line.reset()
            line.moveTo(-w * .32f, yB - h * .07f * wet)
            line.quadTo(0f, lc - h * .07f * wet, w * .32f, yB - h * .07f * wet)
            roundStroke.color = withAlpha(0xFFFFFFFF.toInt(), .75f * wet)
            roundStroke.strokeWidth = max(1f, w * .035f)
            canvas.drawPath(line, roundStroke)
        }
        canvas.restore()

        // A closed eye stays visible as a line instead of vanishing.
        val midU = (yL + 2 * uc + yR) / 4
        val midL = (yB + lc) / 2
        val gap = min(midL - midU, yB - max(yL, yR))
        val a = (1 - gap / (h * .09f)).coerceIn(0f, 1f)
        if (a > 0f) {
            line.reset()
            line.moveTo(-w * .42f, (yL + yB) / 2)
            line.quadTo(0f, (uc + lc) / 2, w * .42f, (yR + yB) / 2)
            roundStroke.color = withAlpha(EYE_WHITE, a)
            roundStroke.strokeWidth = w * .11f
            canvas.save()
            canvas.translate(ex, ey)
            canvas.drawPath(line, roundStroke)
            canvas.restore()
        }
    }

    private fun drawTears(canvas: Canvas, rig: IrisRig, l: IrisLayout) {
        val s = l.s
        val rad = s * .022f
        for (d in rig.tears) {
            if (d.alpha <= 0f) continue
            val x = d.side * (l.eyeX - .06f * s)
            val y = l.eyeY + l.eyeH * .42f + d.y * s
            drop.reset()
            drop.moveTo(x, y - rad * 2.2f)
            drop.quadTo(x + rad * 1.1f, y - rad * .2f, x, y + rad)
            drop.quadTo(x - rad * 1.1f, y - rad * .2f, x, y - rad * 2.2f)
            fill.shader = null
            fill.color = withAlpha(TEAR, .85f * d.alpha)
            canvas.drawPath(drop, fill)
            fill.color = withAlpha(0xFFFFFFFF.toInt(), .8f * d.alpha)
            canvas.drawCircle(x - rad * .3f, y - rad * .2f, rad * .28f, fill)
        }
    }

    /** The count (or time), popping when it changes and rolling when it switches. */
    private fun drawNumber(canvas: Canvas, rig: IrisRig, l: IrisLayout, color: Int, n: NumberLine) {
        val s = l.s
        val k = rig.num.x
        val shade = if (n.timer) Mood.blend(color, EYE_WHITE, .6f) else color
        numberPaint.textSize = l.numSize * if (n.text.length > 4) .84f else 1f
        val middle = -(numberPaint.ascent() + numberPaint.descent()) / 2
        val travel = l.numSize * 1.05f
        val eased = 1 - (1 - n.roll) * (1 - n.roll)

        canvas.save()
        canvas.clipRect(-l.w / 2, l.eyeY + l.eyeH / 2 + .03f * s, l.w / 2, l.h / 2 - .02f * s)
        canvas.translate(0f, l.numY + k * s * .08f)
        canvas.scale(1 - k * .25f, 1 - k * .25f)
        val pop = (1 - k * .8f).coerceIn(0f, 1f)
        if (n.roll < 1f && n.previous.isNotEmpty()) {
            text(canvas, n.previous, middle - travel * eased, shade, color, pop * (1 - eased), s)
        }
        text(canvas, n.text, middle + travel * (1 - eased), shade, color, pop * eased.coerceAtLeast(.15f), s)
        canvas.restore()
    }

    /** Text in [shade] with a soft glow in [glow], both faded to [alpha]. */
    private fun text(canvas: Canvas, text: String, y: Float, shade: Int, glow: Int, alpha: Float, s: Float) {
        numberPaint.color = withAlpha(shade, alpha)
        numberPaint.setShadowLayer(.06f * s * BLUR, 0f, 0f, withAlpha(glow, .6f * alpha))
        canvas.drawText(text, 0f, y, numberPaint)
    }

    /** Three jagged sparks crackling on the rim, new every frame. */
    private fun drawArcs(canvas: Canvas, l: IrisLayout) {
        val s = l.s
        arcPaint.strokeWidth = max(1f, s * .012f)
        arcPaint.setShadowLayer(s * .06f * BLUR, 0f, 0f, 0xFFFFE45C.toInt())
        repeat(3) {
            val edge = Random.nextInt(4)
            val vertical = edge == 1 || edge == 3
            val half = if (vertical) l.h / 2 else l.w / 2
            val u = rnd(-.3f, .3f) * (if (vertical) l.h else l.w)
            line.reset()
            for (j in 0 until 6) {
                val v = (u + j * .06f * s).coerceIn(-half, half)
                val off = (if (vertical) l.w / 2 else l.h / 2) + rnd(-.03f * s, .04f * s)
                val x = when (edge) { 0, 2 -> v; 1 -> off; else -> -off }
                val y = when (edge) { 0 -> -off; 2 -> off; else -> v }
                if (j == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            canvas.drawPath(line, arcPaint)
        }
    }

    /** Two small lilac z's drifting up from the top right. */
    private fun drawZs(canvas: Canvas, rig: IrisRig, l: IrisLayout, cx: Float, cy: Float) {
        val s = l.s
        canvas.save()
        canvas.translate(cx + rig.fx.x * s, cy + rig.fy.x * s)
        for (i in 0..1) {
            val ph = (rig.t * .35f + i * .5f) % 1f
            zPaint.alpha = (255 * sin(ph * Math.PI).toFloat() * .7f).toInt()
            zPaint.textSize = s * (.08f + ph * .05f)
            canvas.drawText("z", l.w / 2 - .08f * s + ph * .12f * s, -l.h / 2 + .08f * s - ph * .18f * s, zPaint)
        }
        canvas.restore()
    }

    companion object {
        const val EYE_WHITE = 0xFFF2F3F8.toInt()
        const val PUPIL = 0xFF0B0D17.toInt()
        private const val ANGRY_WHITE = 0xFFFFDEE2.toInt()
        private const val ANGRY_RED = 0xFFFF4D5E.toInt()
        private const val WET_BLUE = 0xFF8CD7FF.toInt()
        private const val TEAR = 0xFF96E1FF.toInt()
        private const val SLEEPY_Z = 0xFFA99BFF.toInt()

        /**
         * The demo's sizes are canvas `shadowBlur`s; Android's shadow radius for the same
         * softness is about 0.87 of that.
         */
        private const val BLUR = 0.87f

        fun withAlpha(color: Int, alpha: Float) =
            (color and 0x00FFFFFF) or ((255 * alpha).toInt().coerceIn(0, 255) shl 24)

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
    }
}
