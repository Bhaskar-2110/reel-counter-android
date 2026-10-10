package com.bhaskar.reelcounter

import android.animation.ValueAnimator
import android.content.Context
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A damped spring toward [t]; lower [zeta] bounces more. */
class Spring(var x: Float, private val k: Float, zeta: Float) {
    var v = 0f
    var t = x
    private val c = 2f * zeta * sqrt(k)

    fun step(dt: Float) {
        val a = -k * (x - t) - c * v
        v += a * dt
        x += v * dt
    }

    /** Jumps to the target and stops. */
    fun snap() {
        x = t
        v = 0f
    }
}

/** The lid, squash and pupil springs of one eye. The right eye is a touch softer ([k]). */
class EyeSprings(k: Float) {
    val open = Spring(0f, 190 * k, .78f)
    val tilt = Spring(0f, 160 * k, .75f)
    val upC = Spring(0f, 160 * k, .8f)
    val low = Spring(0f, 170 * k, .8f)
    val lowC = Spring(0f, 150 * k, .8f)
    val sx = Spring(1f, 240 * k, .5f)
    val sy = Spring(1f, 240 * k, .5f)
    val pup = Spring(1f, 300 * k, .55f)
    val all = arrayOf(open, tilt, upC, low, lowC, sx, sy, pup)
}

/**
 * Iris's face as one living rig (design_iris_docs/IRIS_EYES_SPEC.md; numbers ported from the
 * demo's `Rig`). The face is ~25 springs; a mood is only a target for them, so moods never cut,
 * they glide. On top run blinks, gaze, breathing and a habit per mood.
 *
 * The base mood comes from [progress] (share of the limit), blended continuously; events
 * ([onReel], [trigger]) override it for a while. Call [update] every frame, then draw with
 * [IrisRenderer].
 */
class IrisRig(var reduceMotion: Boolean = false) {

    /** Share of today's limit used, 0..1+; picks the base mood. */
    var progress = 0f

    /** A mood to hold instead of the progress ramp (previews, pause and block screens). */
    var locked: Mood? = null

    val eyes = arrayOf(EyeSprings(1f), EyeSprings(0.9f))

    val gx = Spring(0f, 520f, .82f)
    val gy = Spring(0f, 520f, .82f)
    val fx = Spring(0f, 150f, .45f)
    val fy = Spring(0f, 130f, .5f)
    val fr = Spring(0f, 120f, .45f)
    val fs = Spring(1f, 200f, .5f)
    // Jelly: the squash spring is soft and lightly damped so Iris keeps jiggling after a bump,
    // toned down after seeing it on a phone (8 Oct): at big sizes stronger looked broken,
    // and [jelly] leans her top behind her base when she moves sideways (a skew), then sways back.
    val sq = Spring(0f, 190f, .24f)
    val jelly = Spring(0f, 150f, .2f)
    val tint = Spring(0f, 40f, 1f)
    val wet = Spring(0f, 30f, 1f)
    val hl = Spring(1f, 120f, .6f)
    private val cr = Spring(0f, 25f, 1f)
    private val cg = Spring(0f, 25f, 1f)
    private val cb = Spring(0f, 25f, 1f)
    val num = Spring(0f, 260f, .42f)
    val glow = Spring(1f, 60f, .9f)
    private val globals = arrayOf(gx, gy, fx, fy, fr, fs, sq, jelly, tint, wet, hl, cr, cg, cb, num, glow)

    /** Seconds since the rig started. */
    var t = 0f
        private set

    /** The mood showing now (an override, the locked mood or the nearest ramp anchor). */
    var mood = Mood.HAPPY
        private set

    /** How closed the eyes are from blinking, 0..1. */
    var blink = 0f
        private set

    /** The current mood colour, gliding between moods. */
    val color: Int
        get() = (0xFF shl 24) or (cr.x.roundToInt().coerceIn(0, 255) shl 16) or
            (cg.x.roundToInt().coerceIn(0, 255) shl 8) or cb.x.roundToInt().coerceIn(0, 255)

    /** Falling tears: x in units of eye offset side (-1/1), y below their start in units of S. */
    val tears = Array(MAX_TEARS) { Tear() }

    class Tear {
        var side = 0
        var y = 0f
        var vy = 0f
        var alpha = 0f
    }

    private val pose = Pose()
    private var override: Mood? = null

    /** When the limit was reached ([t]), or -1 while under it. */
    private var limitAt = -1f
    private var overrideUntil = 0f
    private val recentReels = ArrayDeque<Float>()

    private var blinkStart = -1f
    private var blinkClose = 0f
    private var blinkHold = 0f
    private var blinkOpen = 0f
    private var nextBlink = rnd(.5f, 2.5f)
    private var squeezeUntil = 0f

    private var saccX = 0f
    private var saccY = 0f
    private var nextSaccade = 0f
    private var glanceX = 0f
    private var glanceY = 0f
    private var glanceUntil = -1f
    private var pointerX = 0f
    private var pointerY = 0f
    private var pointerAt = -99f

    private var flip = 1f
    private var huffAt = 0f
    private var trembleUntil = 0f
    private var shakeAt = -99f
    private var jitterAt = 0f
    private val jitter = FloatArray(7)
    private var tearAt = rnd(1f, 3f)

    // Giggles: a burst of quick squishes when happy.
    private var giggleAt = rnd(2f, 4f)
    private var gigglesLeft = 0
    private var nextGiggle = 0f
    private var giggleStrength = 1f

    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false

    init {
        pose.set(Mood.HAPPY)
        setColor(pose.color)
    }

    private fun setColor(c: Int) {
        cr.x = ((c shr 16) and 0xFF).toFloat(); cr.t = cr.x
        cg.x = ((c shr 8) and 0xFF).toFloat(); cg.t = cg.x
        cb.x = (c and 0xFF).toFloat(); cb.t = cb.x
    }

    /** Shows [m] for [seconds] (infinite = until [clear]) instead of the base mood. */
    fun trigger(m: Mood, seconds: Float = Float.POSITIVE_INFINITY) {
        override = m
        overrideUntil = t + seconds
    }

    /** Ends the override, or only a [m] override when given. */
    fun clear(m: Mood? = null) {
        if (m == null || override == m) override = null
    }

    val isSleeping get() = override == Mood.SLEEPY

    /** Wakes Iris if she's asleep, with a startle and a hop. */
    fun wake() {
        if (override == Mood.SLEEPY) {
            trigger(Mood.SURPRISED, .7f)
            fy.v -= 1.2f
            squeezeUntil = 0f
        }
    }

    /** Pops the number, as when the count changes. */
    fun popNumber() {
        num.x = 1f
        num.v = 0f
    }

    /**
     * A reel was counted and [newProgress] is the share of the limit now: hop, squash, glance
     * at the number, and Surprised on crossing a band, Surprised-then-huff on reaching the
     * limit, or Electrocuted after 3 reels within 6 seconds.
     */
    fun onReel(newProgress: Float) {
        val prev = progress
        progress = newProgress
        popNumber()
        wake()
        fy.v -= 1.4f
        sq.v += 5f
        // The eyes squish with the body: stretched on the hop.
        for (e in eyes) {
            e.sy.v += 2.5f
            e.sx.v -= 1.5f
        }
        glance(0f, 1f, .45f)
        recentReels.addLast(t)
        while (recentReels.isNotEmpty() && t - recentReels.first() > 6f) recentReels.removeFirst()
        val crossed = BANDS.any { prev < it && newProgress >= it }
        when {
            prev < 1f && newProgress >= 1f -> {
                trigger(Mood.SURPRISED, .8f)
                huffAt = t + .9f
            }
            // Another reel after the limit: a flash of anger, then back to sad.
            prev >= 1f -> trigger(Mood.ANGRY, 3f)
            recentReels.size >= 3 -> trigger(Mood.ELECTRIC, 2.4f)
            crossed -> trigger(Mood.SURPRISED, .9f)
        }
    }

    private fun glance(x: Float, y: Float, seconds: Float) {
        glanceX = x
        glanceY = y
        glanceUntil = t + seconds
    }

    /**
     * A finger is at ([nx], [ny]) from the island's centre, in units of 320dp; the eyes follow
     * it for 2.5 s.
     */
    fun lookAt(nx: Float, ny: Float) {
        pointerX = nx
        pointerY = ny
        pointerAt = t
    }

    /** Looks toward ([x], [y]) (-1..1) and holds it, for still pictures. */
    fun hold(x: Float, y: Float) = glance(x, y, Float.POSITIVE_INFINITY)

    /** A tap at ([lx], [ly]) from the centre in units of S: each mood reacts its own way. */
    fun poke(lx: Float, ly: Float) {
        wake()
        val m = mood
        val dir = if (lx == 0f) 1f else sign(lx)
        sq.v += if (ly > 0) -7f else 7f
        fx.v += -lx * 2f
        squeezeUntil = t + if (m == Mood.ANGRY) .12f else .22f
        when (m) {
            Mood.HAPPY -> { fr.v += 3f; trigger(Mood.HAPPY, .9f); giggleStrength = 1f; giggle(4) }
            Mood.CURIOUS -> trigger(Mood.SURPRISED, .6f)
            Mood.WORRIED -> { fx.v += -dir * 2.5f; glance(-dir, .3f, .7f) }
            Mood.SAD -> glance(-dir * .8f, .7f, 1.2f)
            Mood.ANGRY -> shakeAt = t
            Mood.PLEADING -> fs.v += 1.5f
            Mood.ELECTRIC -> fx.v += 4f
            else -> {}
        }
    }

    /** Being dragged: the finger is ([dx], [dy]) ahead, in units of S. */
    fun drag(dx: Float, dy: Float) {
        if (!dragging) wake()
        dragging = true
        dragX = dx
        dragY = dy
    }

    /** Let go after a drag: a jelly bounce and a blink. */
    fun release() {
        if (!dragging) return
        dragging = false
        sq.v += 5f
        squeezeUntil = t + .12f
    }

    /** Kicks the scale, e.g. when the pause screen opens. */
    fun kick(scaleVelocity: Float) {
        fs.v += scaleVelocity
    }

    private fun currentPose(): Pose {
        if (override != null && t > overrideUntil) override = null
        val o = override ?: locked
        if (progress < 1f) limitAt = -1f else if (limitAt < 0f) limitAt = t
        when {
            o != null -> pose.set(o)
            // Past the limit Iris is angry for a while, then settles into disappointment.
            limitAt >= 0f && t - limitAt > SAD_AFTER_LIMIT -> pose.set(Mood.SAD)
            else -> rampPose(progress)
        }
        return pose
    }

    private fun rampPose(p0: Float) {
        val p = p0.coerceIn(0f, 1f)
        var i = 0
        while (i < RAMP.size - 2 && p > RAMP_AT[i + 1]) i++
        val a = RAMP_AT[i]
        val b = RAMP_AT[i + 1]
        val k = ((p - a) / (if (b - a == 0f) 1f else b - a)).coerceIn(0f, 1f)
        pose.mix(RAMP[i], RAMP[i + 1], k * k * (3 - 2 * k))
    }

    fun update(dt: Float) {
        t += dt
        val p = currentPose()
        val m = p.mood
        mood = m
        val a = if (reduceMotion) 0f else 1f

        var tx = 0f
        var ty = p.fy
        var tr = p.fr
        var ts = p.fs
        var tsq = 0f
        var extraOpen = 0f
        var asym = p.asym
        var j0 = 0f; var j1 = 0f; var j2 = 0f; var j3 = 0f

        ts += sin(t * 1.5f) * .01f * a
        // Jelly: a slow squishy breath, and she stretches tall moving up and squats moving down.
        tsq += sin(t * 2.3f) * .015f * a + (fy.v * .05f).coerceIn(-.12f, .12f) * a
        jelly.t = (-fx.v * .05f).coerceIn(-.14f, .14f) * a
        updateGiggles(m, a)
        when (m) {
            Mood.HAPPY -> { tr += sin(t * 1.7f) * .035f * a; ty -= abs(sin(t * 1.7f)) * .01f * a }
            Mood.CURIOUS -> {
                // The head tilt (and which eye is bigger) flips every ~9 s.
                if ((sin(t * .35f) > 0) == (flip < 0)) flip = -flip
                tr *= flip
                asym *= flip
            }
            Mood.WORRIED -> tx += sin(t * 31f) * .0025f * a
            Mood.SAD -> ty += sin(t * .9f) * .008f * a
            Mood.SLEEPY -> {
                // Nods off over ~3 s, then jerks awake.
                val ph = (t * .22f) % 1f
                if (ph < .72f) {
                    val k = ph / .72f
                    extraOpen = k * k * .4f
                    ty += k * k * .035f * a
                    tr += -k * .04f * a
                } else {
                    extraOpen = -.14f
                }
            }
            Mood.PLEADING -> tr += sin(t * 2.4f) * .03f * a
            Mood.ANGRY -> if (override == null) {
                if (t > huffAt) {
                    huffAt = t + rnd(3.2f, 4.6f)
                    if (a > 0f) {
                        fs.v += 1.6f
                        sq.v -= 4f
                    }
                    trembleUntil = t + .5f
                }
                if (t < trembleUntil) tx += (Random.nextFloat() - .5f) * .016f * a
            }
            Mood.ELECTRIC -> {
                if (t > jitterAt) {
                    jitterAt = t + .05f
                    for (i in 0 until 4) jitter[i] = rnd(-.13f, .13f)
                    jitter[4] = rnd(-.02f, .02f)
                    jitter[5] = rnd(-.9f, .9f)
                    jitter[6] = rnd(-.9f, .9f)
                }
                j0 = jitter[0] * a; j1 = jitter[1] * a; j2 = jitter[2] * a; j3 = jitter[3] * a
                tx += jitter[4] * a
            }
            else -> {}
        }
        // Angry "no" shake after a poke.
        val sinceShake = t - shakeAt
        if (sinceShake < .7f) tr += sin(sinceShake * 28f) * .09f * (1 - sinceShake / .7f)

        if (dragging) {
            val dx = dragX.coerceIn(-.6f, .6f)
            val dy = dragY.coerceIn(-.6f, .6f)
            tx += dx * .4f
            ty += dy * .4f
            tr += dx * .25f
            tsq += min(hypot(dragX, dragY), .6f) * .12f * (if (abs(dragY) > abs(dragX)) -1f else 1f)
            extraOpen -= .05f
        }

        // Gaze: a held glance, electric darting, a finger, or saccades.
        var lx: Float
        var ly: Float
        if (t < glanceUntil) {
            lx = glanceX; ly = glanceY
        } else if (m == Mood.ELECTRIC) {
            lx = jitter[5]; ly = jitter[6]
        } else if (t - pointerAt < 2.5f) {
            val d = hypot(pointerX, pointerY)
            val s = if (d > 0f) min(1f, d) / d else 0f
            lx = pointerX * s
            ly = pointerY * s + p.py * .4f
            if (m == Mood.SAD || m == Mood.SLEEPY) ly = max(ly, p.py * .6f)
        } else {
            if (t > nextSaccade) saccade(m)
            lx = saccX; ly = saccY + p.py
        }
        if (a > 0f) {
            lx += sin(t * 13.1f) * .015f
            ly += cos(t * 11.7f) * .015f
        }
        gx.t = lx.coerceIn(-1f, 1f)
        gy.t = ly.coerceIn(-1f, 1f)
        tr += gx.x * .03f

        updateBlink(m)

        for ((i, e) in eyes.withIndex()) {
            val side = if (i == 0) -1f else 1f
            val big = 1 + .08f * asym * -side
            val squint = asym * side > 0
            e.open.t = p.open + extraOpen + if (squint) .12f * abs(asym) else 0f
            e.tilt.t = p.tilt
            e.upC.t = p.upC
            e.low.t = p.low + if (squint) .05f * abs(asym) else 0f
            e.lowC.t = p.lowC
            e.sx.t = p.sx * big + if (i == 0) j0 else j2
            e.sy.t = p.sy * big + if (i == 0) j1 else j3
            e.pup.t = p.pup * (1 + if (m == Mood.PLEADING) sin(t * 1.3f + i) * .03f * a else 0f)
        }
        fx.t = tx; fy.t = ty; fr.t = tr; fs.t = ts; sq.t = tsq
        tint.t = p.tint
        wet.t = p.wet
        hl.t = p.hl + if (m == Mood.PLEADING) sin(t * 7f) * .08f * a else 0f
        cr.t = ((p.color shr 16) and 0xFF).toFloat()
        cg.t = ((p.color shr 8) and 0xFF).toFloat()
        cb.t = (p.color and 0xFF).toFloat()
        num.t = 0f
        glow.t = 1f + (if (m == Mood.ANGRY) .6f + sin(t * 5f) * .4f * a else 0f) +
            (if (m == Mood.ELECTRIC) Random.nextFloat() * .8f * a else 0f)

        // Springs, substepped at 240 Hz for stability.
        val n = max(1, ceil(dt * 240f).toInt())
        val h = dt / n
        repeat(n) {
            for (e in eyes) for (s in e.all) s.step(h)
            for (s in globals) s.step(h)
        }

        updateTears(dt, a)
    }

    /** Starts a burst of [count] giggles. */
    private fun giggle(count: Int) {
        if (reduceMotion) return
        gigglesLeft = count
        nextGiggle = t
    }

    /**
     * Iris giggles now and then: a few quick squishes, alternating side to side, with a little
     * hop and her lower lids pushing up. Each giggle kicks the jelly springs, so she wobbles on
     * afterwards. Happy giggles most and hardest; curious and pleading giggle shyly; worried
     * gives the odd nervous giggle. Sad, angry, sleepy, surprised and electrocuted don't.
     */
    private fun updateGiggles(m: Mood, a: Float) {
        if (a == 0f) return
        val style = GIGGLES[m]
        if (gigglesLeft == 0 && style != null && t > giggleAt) {
            giggleStrength = style.strength
            giggle(Random.nextInt(style.minPulses, style.maxPulses + 1))
        }
        if (gigglesLeft > 0 && t >= nextGiggle) {
            val dir = if (gigglesLeft % 2 == 0) 1f else -1f
            val k = giggleStrength
            sq.v += 3f * k * dir
            fr.v += 1.2f * k * dir
            fy.v -= .6f * k
            for (e in eyes) e.low.v += 3f * k
            gigglesLeft--
            nextGiggle = t + .12f
            if (gigglesLeft == 0) {
                val next = style ?: GIGGLES.getValue(Mood.HAPPY)
                giggleAt = t + rnd(next.minGap, next.maxGap)
                giggleStrength = 1f
            }
        }
    }

    /** How a mood giggles: pulses per burst, seconds between bursts, and how hard. */
    private class GiggleStyle(val minPulses: Int, val maxPulses: Int, val minGap: Float, val maxGap: Float, val strength: Float)

    private fun saccade(m: Mood) {
        when (m) {
            Mood.WORRIED -> { saccX = if (saccX > 0) rnd(-.9f, -.5f) else rnd(.5f, .9f); saccY = rnd(-.2f, .3f); nextSaccade = t + rnd(.35f, .9f) }
            Mood.CURIOUS -> { saccX = rnd(-.95f, .95f); saccY = rnd(-.6f, .5f); nextSaccade = t + rnd(.8f, 2.2f) }
            Mood.ANGRY -> {
                val side = Random.nextFloat() < .2f
                saccX = if (side) rnd(-.7f, .7f) else 0f
                saccY = 0f
                nextSaccade = t + if (side) rnd(.3f, .6f) else rnd(1.5f, 3f)
            }
            Mood.SAD -> { saccX = rnd(-.4f, .4f); saccY = 0f; nextSaccade = t + rnd(1.8f, 3.5f) }
            Mood.SLEEPY -> { saccX = rnd(-.3f, .3f); saccY = 0f; nextSaccade = t + rnd(2.5f, 4f) }
            Mood.PLEADING -> { saccX = rnd(-.15f, .15f); saccY = 0f; nextSaccade = t + rnd(1.2f, 2.5f) }
            else -> { saccX = rnd(-.55f, .55f); saccY = rnd(-.35f, .35f); nextSaccade = t + rnd(.9f, 2.6f) }
        }
    }

    private fun updateBlink(m: Mood) {
        var b = 0f
        if (blinkStart < 0f && t > nextBlink) {
            val slow = when (m) {
                Mood.SLEEPY -> 3f
                Mood.SAD -> 1.6f
                else -> 1f
            }
            blinkStart = t
            blinkClose = .07f * slow
            blinkHold = .04f * slow
            blinkOpen = .13f * slow
        }
        if (blinkStart >= 0f) {
            val e = t - blinkStart
            when {
                e < blinkClose -> b = smooth(e / blinkClose)
                e < blinkClose + blinkHold -> b = 1f
                e < blinkClose + blinkHold + blinkOpen -> b = 1 - smooth((e - blinkClose - blinkHold) / blinkOpen)
                else -> {
                    blinkStart = -1f
                    val double = Random.nextFloat() < .18f && m != Mood.SLEEPY
                    nextBlink = t + when {
                        double -> .12f
                        m == Mood.ANGRY -> rnd(3.5f, 6f)
                        m == Mood.SLEEPY -> rnd(2f, 3.5f)
                        m == Mood.ELECTRIC -> rnd(.3f, .8f)
                        else -> rnd(1.8f, 5.2f)
                    }
                }
            }
        }
        if (t < squeezeUntil) b = 1f
        blink = b
    }

    private fun updateTears(dt: Float, a: Float) {
        if (a > 0f && wet.x > .6f && t > tearAt) {
            tearAt = t + rnd(2.2f, 4.2f)
            tears.firstOrNull { it.alpha <= 0f }?.apply {
                side = if (Random.nextBoolean()) -1 else 1
                y = 0f
                vy = 0f
                alpha = 1f
            }
        }
        for (d in tears) {
            if (d.alpha <= 0f) continue
            d.vy += .55f * dt
            d.y += d.vy * dt
            if (d.y > .25f) d.alpha -= dt * 2.5f
        }
    }

    /**
     * Settles every spring on its target with the eyes open, for still pictures (widgets):
     * call after setting the mood and [hold].
     */
    fun settle() {
        update(0.016f)
        for (e in eyes) for (s in e.all) s.snap()
        for (s in globals) s.snap()
        blink = 0f
        for (d in tears) d.alpha = 0f
    }

    companion object {
        private val RAMP = arrayOf(Mood.HAPPY, Mood.HAPPY, Mood.CURIOUS, Mood.WORRIED, Mood.SAD, Mood.ANGRY)
        private val RAMP_AT = floatArrayOf(0f, .18f, .42f, .70f, .90f, 1f)

        /** Crossing these shares of the limit plays Surprised. */
        private val BANDS = floatArrayOf(.3f, .6f, .85f)
        private const val MAX_TEARS = 4

        private val GIGGLES = mapOf(
            Mood.HAPPY to GiggleStyle(3, 5, 3f, 6f, 1f),
            Mood.CURIOUS to GiggleStyle(2, 3, 5f, 9f, .75f),
            Mood.PLEADING to GiggleStyle(2, 3, 5f, 9f, .6f),
            Mood.WORRIED to GiggleStyle(2, 2, 7f, 11f, .5f),
        )

        /** Seconds of anger at the limit before Iris turns sad. */
        private const val SAD_AFTER_LIMIT = 8f

        private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
        private fun smooth(x: Float) = x * x * (3 - 2 * x)

        /** Reduce motion: the in-app setting, or animations turned off in the system. */
        fun reduceMotion(ctx: Context): Boolean =
            CountStore.reduceMotion(ctx) || !ValueAnimator.areAnimatorsEnabled()
    }
}
