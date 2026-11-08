package com.reelcounter

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

private const val MINT = 0xFF4FE3B0.toInt()

/** One drum of a lock: its name under the window, its face labels, and how many distinct values they loop through. */
class DialSpec(val name: String, val labels: List<String>, val period: Int = labels.size, val start: Int = 0)

/**
 * Onboarding's combination lock (design/reel-limit-lock.html): a padlock body with rolling 3D
 * dials and a steel shackle that sinks while the hold button fills and snaps shut when the
 * promise is locked. The lock is drawn dark in every theme, as in the design.
 *
 * Two locks use it (see [reelLock] and [timeLock]): the daily reel limit (hundreds, tens, ones)
 * and the daily social media time limit (hours, minutes in 15s). [valueOf] turns the dials'
 * values into the number; [indicesOf] does the reverse for the quick picks.
 */
class ReelLockView(
    ctx: Context,
    specs: List<DialSpec>,
    private val valueOf: (IntArray) -> Int,
    private val indicesOf: (Int) -> IntArray,
    private val onChange: (Int) -> Unit,
) : ViewGroup(ctx), DialView.Listener {

    private val dp = resources.displayMetrics.density
    private val compact = resources.configuration.screenHeightDp in 1 until 700
    private val reduceMotion = CountStore.reduceMotion(ctx)
    private val light = !AppTheme.isDark(ctx)

    val dials = specs.mapIndexed { i, spec -> DialView(ctx, i, spec, if (compact) 138f else 156f, this) }

    var locked = false
        private set

    /** Click sounds per dial (design); null = haptics only. */
    var sounds: LockSounds? = null

    // Lock materials (design --lock-*, --steel-*): the light theme only lifts them a little.
    private val lockTop = if (light) 0xFF2A2E48.toInt() else 0xFF262B44.toInt()
    private val lockBot = if (light) 0xFF191C30.toInt() else 0xFF151827.toInt()
    private val lockRim = if (light) 0xFF3A4066.toInt() else 0xFF353B60.toInt()
    private val lockHi = if (light) 0x17FFFFFF else 0x12FFFFFF
    private val steel = if (light) intArrayOf(0xFF4A506C.toInt(), 0xFFD9DDEE.toInt(), 0xFF8A90AE.toInt(), 0xFFD9DDEE.toInt(), 0xFF4A506C.toInt())
    else intArrayOf(0xFF40455F.toInt(), 0xFFC9CEE3.toInt(), 0xFF7B819F.toInt(), 0xFFC9CEE3.toInt(), 0xFF40455F.toInt())
    private val shadowColor = if (light) 0x4714162B else 0x8C000000.toInt()

    private val shackleSpace = (if (compact) 60f else 80f) * dp
    private val bodyMaxW = 340f * dp
    private val bodyPadX = 16f * dp
    private val bodyPadTop = 16f * dp
    private val wellPad = 8f * dp
    private val gapPx = 8f * dp
    private val placesGap = 12f * dp
    private val placesH = 12f * dp
    private val bodyPadBottom = 14f * dp
    private val bottomRoom = 26f * dp // shadow and glow under the body

    /** Shackle offset in dp: −24 open, 0 shut (design .shackle translateY). */
    private var shackleY = -24f
    private var shackleAnim: ValueAnimator? = null
    private var glow = 0f
    private var glowAnim: ValueAnimator? = null

    private val body = RectF()
    private val well = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val steelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 24f * dp
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = 0x40FFFFFF
    }
    private val placesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.inter)
        fontVariationSettings = "'wght' 600"
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f, resources.displayMetrics)
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
        color = 0xFF7F84A3.toInt()
    }
    private val shacklePath = Path()
    private val marker = Path()
    private val placeNames = specs.map { it.name }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        dials.forEach { addView(it) }
        // Shadows on shapes need hardware support from Android 9; older phones skip the glow.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** The number on the dials (000 / 0:00 included; callers check validity). */
    val value: Int get() = valueOf(IntArray(dials.size) { dials[it].digit })

    override fun onDialTick(dial: DialView) {
        Haptics.dialTick(context, dial.place)
        sounds?.dial(dial.place)
        onChange(value)
    }

    /** Rolls each dial the short way to [v]'s values (the quick-pick chips). */
    fun rollTo(v: Int) {
        if (locked) return
        indicesOf(v).forEachIndexed { i, d -> dials.getOrNull(i)?.rollToDigit(d) }
    }

    /** Lands every moving dial on its number now (the hold button was pressed). */
    fun settle() = dials.forEach { it.settle() }

    /** The shackle follows the hold: [p] 0..1 sinks it 14 dp. */
    fun pressShackle(p: Float) {
        if (locked) return
        shackleAnim?.cancel()
        shackleY = -24f + 14f * p.coerceIn(0f, 1f)
        invalidate()
    }

    /** Let go too soon: the shackle springs back up. */
    fun releaseShackle() {
        if (locked) return
        animateShackle(-24f, 450, OvershootInterpolator(2.2f))
    }

    fun setLocked(on: Boolean) {
        if (locked == on) return
        locked = on
        dials.forEach { it.locked = on }
        if (on) {
            animateShackle(0f, 220, AccelerateInterpolator(1.6f))
            slam()
        } else {
            animateShackle(-24f, 450, OvershootInterpolator(2.2f))
        }
        glowAnim?.cancel()
        if (reduceMotion) {
            glow = if (on) 1f else 0f
            invalidate()
        } else {
            glowAnim = ValueAnimator.ofFloat(glow, if (on) 1f else 0f).apply {
                duration = 500
                addUpdateListener {
                    glow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    private fun animateShackle(to: Float, ms: Long, interp: android.animation.TimeInterpolator) {
        shackleAnim?.cancel()
        if (reduceMotion) {
            shackleY = to
            invalidate()
            return
        }
        shackleAnim = ValueAnimator.ofFloat(shackleY, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                shackleY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** design @keyframes slam: the body dips 5 dp and squashes a little, then settles. */
    private fun slam() {
        if (reduceMotion) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener {
                val t = it.animatedFraction
                val k = if (t < 0.3f) t / 0.3f else 1f - OvershootInterpolator(1.4f).getInterpolation((t - 0.3f) / 0.7f)
                translationY = 5f * dp * k
                scaleX = 1f + 0.01f * k
                scaleY = 1f - 0.015f * k
            }
            start()
        }
    }

    private fun bodyHeight() = bodyPadTop + wellPad * 2 + dials[0].wheelHeight + placesGap + placesH + bodyPadBottom

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (shackleSpace + bodyHeight() + bottomRoom).roundToInt()
        val bodyW = min(bodyMaxW, w.toFloat())
        val wheelW = ((bodyW - bodyPadX * 2 - wellPad * 2 - gapPx * (dials.size - 1)) / dials.size.toFloat()).roundToInt()
        dials.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(wheelW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(it.wheelHeight.roundToInt(), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = (r - l).toFloat()
        val bodyW = min(bodyMaxW, w)
        val left = (w - bodyW) / 2f
        body.set(left, shackleSpace, left + bodyW, shackleSpace + bodyHeight())
        well.set(body.left + bodyPadX, body.top + bodyPadTop, body.right - bodyPadX, body.top + bodyPadTop + wellPad * 2 + dials[0].wheelHeight)
        var x = well.left + wellPad
        val y = (well.top + wellPad).roundToInt()
        for (d in dials) {
            d.layout(x.roundToInt(), y, x.roundToInt() + d.measuredWidth, y + d.measuredHeight)
            x += d.measuredWidth + gapPx
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawShackle(canvas)
        drawBody(canvas)
    }

    private fun drawShackle(canvas: Canvas) {
        // design: a 190×130 box at the top, legs at x 38 and 152 running down to y 150, arc r 57.
        val s = dp
        val ox = body.centerX() - 95f * s
        val oy = shackleY * s
        shacklePath.reset()
        shacklePath.moveTo(ox + 38f * s, oy + 150f * s)
        shacklePath.lineTo(ox + 38f * s, oy + 72f * s)
        shacklePath.arcTo(ox + 38f * s, oy + 15f * s, ox + 152f * s, oy + 129f * s, 180f, 180f, false)
        shacklePath.lineTo(ox + 152f * s, oy + 150f * s)
        steelPaint.shader = LinearGradient(ox + 26f * s, 0f, ox + 164f * s, 0f, steel, floatArrayOf(0f, .22f, .5f, .78f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(shacklePath, steelPaint)
        canvas.save()
        canvas.translate(-6f * s, 0f)
        canvas.drawPath(shacklePath, shinePaint)
        canvas.restore()
    }

    private fun drawBody(canvas: Canvas) {
        val r = 30f * dp
        // Drop shadow, and the mint glow once locked (design box-shadow).
        fill.shader = null
        fill.color = lockBot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P || layerType == LAYER_TYPE_SOFTWARE) {
            fill.setShadowLayer(25f * dp, 0f, 16f * dp, shadowColor)
            canvas.drawRoundRect(body, r, r, fill)
            if (glow > 0f) {
                fill.setShadowLayer(24f * dp, 0f, 0f, ColorUtils.setAlphaComponent(MINT, (115 * glow).roundToInt()))
                canvas.drawRoundRect(body, r, r, fill)
            }
            fill.clearShadowLayer()
        }
        fill.shader = LinearGradient(0f, body.top, 0f, body.bottom, lockTop, lockBot, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(body, r, r, fill)
        fill.shader = null
        // Rim, tinted mint when locked; a highlight along the top edge.
        stroke.strokeWidth = dp
        stroke.color = ColorUtils.blendARGB(lockRim, ColorUtils.blendARGB(lockRim, MINT, .55f), glow)
        val half = dp / 2f
        canvas.drawRoundRect(body.left + half, body.top + half, body.right - half, body.bottom - half, r, r, stroke)
        stroke.color = lockHi
        canvas.drawLine(body.left + r, body.top + dp * 1.5f, body.right - r, body.top + dp * 1.5f, stroke)

        // The well the dials sit in.
        fill.color = 0xFF05060C.toInt()
        canvas.drawRoundRect(well, 18f * dp, 18f * dp, fill)
        stroke.color = 0x0AFFFFFF
        canvas.drawRoundRect(well, 18f * dp, 18f * dp, stroke)
        // Read-line markers on both sides.
        val m = 7f * dp
        val cy = well.centerY()
        fill.color = MINT
        marker.reset()
        marker.moveTo(well.left - 2f * dp, cy - m)
        marker.lineTo(well.left - 2f * dp + m, cy)
        marker.lineTo(well.left - 2f * dp, cy + m)
        marker.close()
        marker.moveTo(well.right + 2f * dp, cy - m)
        marker.lineTo(well.right + 2f * dp - m, cy)
        marker.lineTo(well.right + 2f * dp, cy + m)
        marker.close()
        canvas.drawPath(marker, fill)

        // HUNDREDS · TENS · ONES under each dial.
        val baseline = well.bottom + placesGap + placesH * 0.85f
        dials.forEachIndexed { i, d ->
            canvas.drawText(placeNames[i].uppercase(), d.left + d.width / 2f, baseline, placesPaint)
        }
    }
}

/**
 * One looping drum (design .wheel), 0–9 for the reel lock: drag or fling with momentum, snaps
 * with a spring, a tap above or below the read line moves it one step. Each passing number calls
 * [Listener.onDialTick]. [place] is its position (0 = left, strongest haptic).
 */
class DialView(ctx: Context, val place: Int, private val spec: DialSpec, heightDp: Float, private val listener: Listener) : View(ctx) {

    interface Listener {
        fun onDialTick(dial: DialView)
    }

    private val dp = resources.displayMetrics.density
    val wheelHeight = heightDp * dp
    private val faceH = 58f * dp
    private val faces = spec.labels.size
    private val stepDeg = 360f / faces
    private val radius = faceH / (2f * tan(Math.PI / faces).toFloat())
    private val perspective = 520f * dp
    private val reduceMotion = CountStore.reduceMotion(ctx)
    private val name = spec.name
    private val start = spec.start

    /** Continuous position in faces; the value read is round(off) mod the dial's period. */
    private var off = start.toFloat()
    private var vel = 0f
    private var target: Float? = null
    private var lastRound = start
    private var dragging = false
    private var y0 = 0f
    private var off0 = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var downT = 0L
    private var moved = 0f
    private var frameT = 0L
    private var running = false

    var locked = false
        set(v) {
            field = v
            invalidate()
        }

    /** The value index shown (0..period-1): the digit on 0–9 dials. */
    val digit: Int get() = off.roundToInt().mod(spec.period)

    private val label: String get() = spec.labels[off.roundToInt().mod(faces)]

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint().apply { strokeWidth = dp }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.bricolage_grotesque)
        fontVariationSettings = "'wght' 800"
        fontFeatureSettings = "tnum"
        textSize = 44f * dp
        textAlign = Paint.Align.CENTER
        color = 0xFFF2F3F8.toInt()
    }
    private val ribPaint = Paint().apply { color = 0x09FFFFFF }
    private val shadeV = Paint()
    private val shadeH = Paint()
    private val face = Path()

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = name
        ViewCompat.setStateDescription(this, label)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 12f * dp)
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val dark = 0xFF03040A.toInt()
        fun a(f: Float) = ColorUtils.setAlphaComponent(dark, (255 * f).roundToInt())
        shadeV.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(a(.96f), a(.55f), Color.TRANSPARENT, Color.TRANSPARENT, a(.55f), a(.96f)),
            floatArrayOf(0f, .22f, .40f, .60f, .78f, 1f), Shader.TileMode.CLAMP)
        shadeH.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0x73000000, Color.TRANSPARENT, Color.TRANSPARENT, 0x73000000),
            floatArrayOf(0f, .18f, .82f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        canvas.drawColor(0xFF03040A.toInt())
        val half = Math.toRadians(stepDeg / 2.0).toFloat()
        val rho = radius / cos(half)
        val base = floor(off).toInt()
        val fm = textPaint.fontMetrics
        val textDy = -(fm.ascent + fm.descent) / 2f
        val reach = faces / 4 + 1
        for (i in base - reach..base + reach) {
            val theta = Math.toRadians(((i - off) * stepDeg).toDouble()).toFloat()
            if (cos(theta) <= 0.05f) continue // the far side of the drum
            val a1 = theta - half
            val a2 = theta + half
            val s1 = perspective / (perspective - (rho * cos(a1) - radius))
            val s2 = perspective / (perspective - (rho * cos(a2) - radius))
            val top = cy + rho * sin(a1) * s1
            val bot = cy + rho * sin(a2) * s2
            if (bot <= top) continue
            face.reset()
            face.moveTo(cx - cx * s1, top)
            face.lineTo(cx + cx * s1, top)
            face.lineTo(cx + cx * s2, bot)
            face.lineTo(cx - cx * s2, bot)
            face.close()
            // Faces turn darker as they roll away from the read line.
            facePaint.color = ColorUtils.blendARGB(0xFF0E1020.toInt(), 0xFF03040A.toInt(), (1f - cos(theta)) * .8f)
            canvas.drawPath(face, facePaint)
            linePaint.color = 0x0FFFFFFF
            canvas.drawLine(cx - cx * s1, top, cx + cx * s1, top, linePaint)
            linePaint.color = 0x99000000.toInt()
            canvas.drawLine(cx - cx * s2, bot, cx + cx * s2, bot, linePaint)
            val sc = perspective / (perspective - (radius * cos(theta) - radius))
            canvas.save()
            canvas.translate(cx, (top + bot) / 2f)
            canvas.scale(sc, (bot - top) / faceH)
            canvas.drawText(spec.labels[i.mod(faces)], 0f, textDy, textPaint)
            canvas.restore()
        }
        // Ribs on the drum, curved-glass shading, and the read band.
        var x = 0f
        while (x < w) {
            canvas.drawRect(x, 0f, x + 2f * dp, h, ribPaint)
            x += 6f * dp
        }
        canvas.drawRect(0f, 0f, w, h, shadeV)
        canvas.drawRect(0f, 0f, w, h, shadeH)
        val bandTop = cy - 30f * dp
        val bandBot = cy + 30f * dp
        if (locked) {
            facePaint.color = ColorUtils.setAlphaComponent(MINT, 23)
            canvas.drawRect(0f, bandTop, w, bandBot, facePaint)
        }
        linePaint.color = if (locked) ColorUtils.setAlphaComponent(MINT, 153) else 0x14FFFFFF
        canvas.drawLine(0f, bandTop, w, bandTop, linePaint)
        canvas.drawLine(0f, bandBot, w, bandBot, linePaint)
        if (isFocused && !locked) {
            stroke.color = MINT
            canvas.drawRoundRect(dp, dp, w - dp, h - dp, 12f * dp, 12f * dp, stroke)
        }
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }

    private fun moved() {
        val r = off.roundToInt()
        if (r != lastRound) {
            lastRound = r
            ViewCompat.setStateDescription(this, label)
            listener.onDialTick(this)
        }
        invalidate()
    }

    // Motion: a spring to the target (design k 170, c 23), run on the display's frames.

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = min(0.034f, (now - frameT) / 1e9f)
            frameT = now
            if (step(dt)) postOnAnimation(this) else running = false
        }
    }

    private fun kick() {
        if (running) return
        running = true
        frameT = System.nanoTime()
        postOnAnimation(frame)
    }

    private fun step(dt: Float): Boolean {
        if (dragging) return false
        val t = target ?: return false
        if (reduceMotion) {
            off = t
            vel = 0f
            target = null
            moved()
            return false
        }
        val a = -170f * (off - t) - 23f * vel
        vel += a * dt
        off += vel * dt
        if (abs(off - t) < .002f && abs(vel) < .02f) {
            off = t
            vel = 0f
            target = null
        }
        moved()
        return target != null
    }

    fun nudge(n: Int) {
        if (locked) return
        target = ((target ?: off).roundToInt() + n).toFloat()
        kick()
    }

    fun rollToDigit(d: Int) {
        val base = (target ?: off).roundToInt()
        target = (base + LimitLock.shortestRoll(base.mod(spec.period), d, spec.period)).toFloat()
        kick()
    }

    /** Jump to where it's heading. */
    fun settle() {
        val t = target ?: return
        off = t
        vel = 0f
        target = null
        moved()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        super.onDetachedFromWindow()
    }

    // Touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked || !isEnabled) return false
        val now = System.nanoTime()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                y0 = e.y
                lastY = e.y
                off0 = off
                downT = now
                lastT = now
                moved = 0f
                vel = 0f
                target = null
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dt = max(1e-3f, (now - lastT) / 1e9f)
                val inst = -((e.y - lastY) / faceH) / dt
                vel = vel * .6f + inst * .4f
                lastY = e.y
                lastT = now
                moved = max(moved, abs(e.y - y0))
                off = off0 - (e.y - y0) / faceH
                moved()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (e.actionMasked == MotionEvent.ACTION_UP && moved < 5f * dp && (now - downT) < 300_000_000L) {
                    // A tap: above the read line steps back, below steps on.
                    val dir = when {
                        e.y < height * .38f -> -1
                        e.y > height * .62f -> 1
                        else -> 0
                    }
                    target = (off.roundToInt() + dir).toFloat()
                    vel = 0f
                    performClick()
                } else {
                    if ((now - lastT) > 80_000_000L) vel = 0f
                    vel = vel.coerceIn(-28f, 28f)
                    target = (off + vel * .16f).roundToInt().toFloat()
                }
                kick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (locked) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> nudge(1)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(-1)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (spec.period == 10) rollToDigit(keyCode - KeyEvent.KEYCODE_0) else return super.onKeyDown(keyCode, event)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    // Accessibility: an adjustable 0–9 control named "Hundreds" / "Tens" / "Ones".

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, (spec.period - 1).toFloat(), digit.toFloat())
        if (!locked) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!locked) {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { nudge(1); settle(); return true }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { nudge(-1); settle(); return true }
                android.R.id.accessibilityActionSetProgress -> {
                    val v = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
                    rollToDigit(v.roundToInt().coerceIn(0, spec.period - 1))
                    settle()
                    return true
                }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}

/** The daily reel limit lock: hundreds, tens, ones (0–9 each). */
fun reelLock(ctx: Context, value: Int, onChange: (Int) -> Unit): ReelLockView {
    val d = LimitLock.digits(value)
    val digits = (0..9).map { it.toString() }
    val names = listOf(R.string.lock_hundreds, R.string.lock_tens, R.string.lock_ones).map { ctx.getString(it) }
    return ReelLockView(ctx, names.mapIndexed { i, n -> DialSpec(n, digits, start = d[i]) },
        { LimitLock.value(it[0], it[1], it[2]) }, LimitLock::digits, onChange)
}

/** The daily social media time limit lock: hours (0–9) and minutes (00, 15, 30, 45). */
fun timeLock(ctx: Context, minutes: Int, onChange: (Int) -> Unit): ReelLockView {
    val d = TimeLock.dials(minutes)
    val hours = DialSpec(ctx.getString(R.string.lock_hours), (0..9).map { it.toString() }, start = d[0])
    val mins = DialSpec(ctx.getString(R.string.lock_minutes), TimeLock.MINUTE_FACES.map { "%02d".format(it) }, period = 4, start = d[1])
    return ReelLockView(ctx, listOf(hours, mins), { TimeLock.value(it[0], it[1]) }, TimeLock::dials, onChange)
}
