package com.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The picture-in-picture setup guide (design/reel-counting-permission.html .pip): a caption, a
 * tiny copy of Android's Accessibility settings with the row to tap highlighted and a finger
 * tapping it, and a 3-part progress bar. Loops on its own: the app can't see which Settings page
 * the user is on. Drawn in the design's 176×252 units and scaled to the window.
 */
class GuideView(ctx: Context) : View(ctx) {

    private val reduceMotion = CountStore.reduceMotion(ctx)
    private val titles = listOf(R.string.guide_t1, R.string.guide_t2, R.string.guide_t3).map { ctx.getString(it) }
    private fun s(id: Int) = context.getString(id)

    var step = 0
        private set
    private var stepStart = SystemClock.uptimeMillis()
    private var pausedAt: Long? = null
    private var doneAt: Long? = null

    val paused: Boolean get() = pausedAt != null

    private val mint = 0xFF4FE3B0.toInt()
    private val bg = 0xFF0B0D17.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val display = resources.getFont(R.font.bricolage_grotesque)
    private val body = resources.getFont(R.font.inter)
    private val rect = RectF()
    private val path = Path()
    private val capShade = LinearGradient(0f, 0f, 0f, 62f, intArrayOf(bg, bg, 0x000B0D17), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        describe()
    }

    // Playback

    private fun now() = SystemClock.uptimeMillis()
    private fun elapsed() = (pausedAt ?: now()) - stepStart

    fun go(i: Int) {
        if (doneAt != null) return
        step = i.mod(STEPS.size)
        stepStart = now()
        if (pausedAt != null) pausedAt = stepStart
        describe()
        postInvalidateOnAnimation()
    }

    fun next() = go(step + 1)
    fun prev() = go(step - 1)

    /** Pauses or plays; returns true when now paused. */
    fun togglePause(): Boolean {
        val p = pausedAt
        if (p != null) {
            stepStart += now() - p
            pausedAt = null
        } else {
            pausedAt = now()
        }
        postInvalidateOnAnimation()
        return paused
    }

    /** "Counting is on. Taking you back…" */
    fun showDone() {
        if (doneAt != null) return
        doneAt = now()
        contentDescription = s(R.string.guide_done)
        announceForAccessibility(contentDescription)
        postInvalidateOnAnimation()
    }

    private fun describe() {
        contentDescription = s(R.string.guide_label) + ". " + s(R.string.guide_step).format(step + 1) + ": " + titles[step]
    }

    // Drawing

    override fun onDraw(canvas: Canvas) {
        if (doneAt == null && pausedAt == null && elapsed() >= STEPS[step]) go(step + 1)
        val k = min(width / W, height / H)
        canvas.drawColor(bg)
        canvas.save()
        canvas.translate((width - W * k) / 2f, (height - H * k) / 2f)
        canvas.scale(k, k)
        val done = doneAt
        if (done != null) {
            drawDone(canvas, now() - done)
        } else {
            val t = elapsed()
            canvas.save()
            canvas.translate(0f, 58f)
            canvas.scale(STAGE_SCALE, STAGE_SCALE)
            canvas.clipRect(0f, 0f, 300f, 330f)
            drawStage(canvas, t)
            canvas.restore()
            drawCaption(canvas)
            drawDots(canvas, t)
        }
        canvas.restore()
        if ((done == null && pausedAt == null) || (done != null && now() - done < 500)) postInvalidateOnAnimation()
    }

    private fun drawCaption(canvas: Canvas) {
        fill.shader = capShade
        canvas.drawRect(0f, 0f, W, 62f, fill)
        fill.shader = null
        text.typeface = body
        text.fontVariationSettings = "'wght' 700"
        text.textSize = 9.5f
        text.letterSpacing = .12f
        text.color = mint
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(s(R.string.guide_step).format(step + 1).uppercase(), 10f, 18f, text)
        text.typeface = display
        text.fontVariationSettings = "'wght' 700"
        text.textSize = 13.5f
        text.letterSpacing = 0f
        text.color = 0xFFF2F3F8.toInt()
        wrap(canvas, titles[step], 10f, 35f, W - 20f, 16f, 2)
    }

    private fun drawDots(canvas: Canvas, t: Long) {
        val gap = 4f
        val w = (W - 20f - gap * 2) / 3f
        val y = H - 9f - 3f
        for (i in 0..2) {
            val x = 10f + i * (w + gap)
            rect.set(x, y, x + w, y + 3f)
            fill.color = 0x33FFFFFF
            canvas.drawRoundRect(rect, 1.5f, 1.5f, fill)
            val share = when {
                i < step -> 1f
                i == step -> if (reduceMotion) 1f else (t.toFloat() / STEPS[step]).coerceIn(0f, 1f)
                else -> 0f
            }
            if (share > 0f) {
                rect.right = x + w * share
                fill.color = mint
                canvas.drawRoundRect(rect, 1.5f, 1.5f, fill)
            }
        }
    }

    private fun drawDone(canvas: Canvas, t: Long) {
        // design @keyframes pop: from half size, with a little overshoot (ease-out-back).
        val x = min(1f, t / 400f) - 1f
        val pop = if (reduceMotion) 1f else .5f + .5f * (1f + 2.70158f * x * x * x + 1.70158f * x * x)
        val cx = W / 2f
        val cy = H / 2f - 18f
        canvas.save()
        canvas.scale(pop, pop, cx, cy)
        fill.color = mint
        canvas.drawCircle(cx, cy, 24f, fill)
        stroke.color = 0xFF062A1F.toInt()
        stroke.strokeWidth = 3.4f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(cx - 9.5f, cy + 0.5f)
        path.lineTo(cx - 3.5f, cy + 6.5f)
        path.lineTo(cx + 9f, cy - 6f)
        canvas.drawPath(path, stroke)
        canvas.restore()
        text.typeface = display
        text.fontVariationSettings = "'wght' 700"
        text.textSize = 14f
        text.letterSpacing = 0f
        text.color = 0xFFF2F3F8.toInt()
        text.textAlign = Paint.Align.CENTER
        s(R.string.guide_done).split('\n').forEachIndexed { i, line -> canvas.drawText(line, cx, cy + 46f + i * 17.5f, text) }
    }

    /** The mini Settings screen for the current step, in 300×330 units (design .pip-stage). */
    private fun drawStage(canvas: Canvas, t: Long) {
        canvas.drawColor(0xFF000000.toInt())
        when (step) {
            0 -> {
                bar(canvas, s(R.string.mini_accessibility))
                group(canvas, 48f, listOf(Row(s(R.string.mini_menu), "›"), Row(s(R.string.mini_button), "›"), Row(s(R.string.mini_lock_shortcut), "›")))
                text.typeface = Typeface.DEFAULT
                text.textSize = 12f
                text.color = 0xFF8E8E93.toInt()
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(s(R.string.mini_more), 26f, 180f + 20f, text)
                val hl = group(canvas, 208f, listOf(Row(s(R.string.onb_access_crumb1), "›", hl = true)))
                highlight(canvas, hl[0], t)
                finger(canvas, hl[0], t - 1100)
            }
            1 -> {
                bar(canvas, s(R.string.onb_access_crumb1))
                val off = s(R.string.mini_off)
                val rows = group(canvas, 48f, listOf(
                    Row(s(R.string.mini_guided), "›"),
                    Row(s(R.string.mini_link_pc), "$off ›"),
                    Row(s(R.string.app_name), "$off ›", hl = true),
                    Row(s(R.string.mini_notes), "$off ›"),
                ))
                highlight(canvas, rows[2], t)
                finger(canvas, rows[2], t - 1100)
            }
            else -> {
                bar(canvas, s(R.string.app_name))
                val on = when {
                    t < 1500 -> 0f
                    reduceMotion -> 1f
                    else -> ((t - 1500) / 200f).coerceIn(0f, 1f)
                }
                val rows = group(canvas, 48f, listOf(
                    Row(s(R.string.app_name), null, hl = true, switch = on),
                    Row(s(R.string.mini_shortcut), null, switch = 0f),
                    Row(s(R.string.mini_app_info), "›"),
                ))
                highlight(canvas, rows[0], t)
                finger(canvas, rows[0], t - 1100)
                if (t >= 2200) {
                    val allow = dialog(canvas)
                    finger(canvas, allow, t - 2900)
                }
            }
        }
    }

    private fun bar(canvas: Canvas, title: String) {
        stroke.color = 0xFFECECEC.toInt()
        stroke.strokeWidth = 2f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(31f, 24f)
        path.lineTo(17f, 24f)
        path.moveTo(23f, 18f)
        path.lineTo(17f, 24f)
        path.lineTo(23f, 30f)
        canvas.drawPath(path, stroke)
        text.typeface = Typeface.DEFAULT
        text.textSize = 19f
        text.letterSpacing = 0f
        text.color = 0xFFECECEC.toInt()
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(title, 46f, 31f, text)
    }

    private class Row(val label: String, val right: String?, val hl: Boolean = false, val switch: Float? = null)

    /** A rounded settings group at [top]; returns each row's rectangle. */
    private fun group(canvas: Canvas, top: Float, rows: List<Row>): List<RectF> {
        val rowH = 44f
        rect.set(12f, top, 288f, top + rowH * rows.size)
        fill.color = 0xFF1C1C1E.toInt()
        canvas.drawRoundRect(rect, 16f, 16f, fill)
        val out = ArrayList<RectF>()
        rows.forEachIndexed { i, r ->
            val y = top + i * rowH
            if (i > 0) {
                fill.color = 0xFF2C2C2E.toInt()
                canvas.drawRect(26f, y, 274f, y + 1f, fill)
            }
            text.typeface = Typeface.DEFAULT
            text.letterSpacing = 0f
            text.textSize = 15f
            text.color = 0xFFECECEC.toInt()
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(r.label, 26f, y + 27f, text)
            r.right?.let {
                text.textSize = 13f
                text.color = 0xFF8E8E93.toInt()
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText(it, 274f, y + 27f, text)
            }
            r.switch?.let { on -> switch(canvas, 234f, y + 10f, on) }
            out += RectF(26f, y, 274f, y + rowH)
        }
        return out
    }

    private fun switch(canvas: Canvas, x: Float, y: Float, on: Float) {
        rect.set(x, y, x + 40f, y + 24f)
        fill.color = ColorUtils.blendARGB(0xFF3A3A3C.toInt(), 0xFF2F7BF6.toInt(), on)
        canvas.drawRoundRect(rect, 12f, 12f, fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x + 12f + 16f * on, y + 12f, 9f, fill)
    }

    /** design .m-row.hl: a mint outline that pulses. */
    private fun highlight(canvas: Canvas, r: RectF, t: Long) {
        val a = if (reduceMotion) 1f else (.675f + .325f * cos(2 * PI * t / 1200.0).toFloat())
        stroke.color = ColorUtils.setAlphaComponent(mint, (255 * a).roundToInt())
        stroke.strokeWidth = 2.5f
        rect.set(r.left - 8f, r.top + 3f, r.right + 8f, r.bottom - 3f)
        canvas.drawRoundRect(rect, 12f, 12f, stroke)
    }

    /** design .finger: fades in big, presses, lifts, over 1.1 s from [t] = 0. */
    private fun finger(canvas: Canvas, r: RectF, t: Long) {
        if (t < 0 || t > 1100 || reduceMotion) return
        val f = t / 1100f
        val scale = when {
            f < .35f -> 1.5f - .5f * (f / .35f)
            f < .55f -> 1f - .2f * ((f - .35f) / .2f)
            else -> .8f + .2f * ((f - .55f) / .45f)
        }
        val alpha = when {
            f < .35f -> f / .35f
            f < .75f -> 1f
            else -> 1f - (f - .75f) / .25f
        }
        val cx = r.left + r.width() * .7f
        val cy = r.centerY()
        fill.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (64 * alpha).roundToInt())
        canvas.drawCircle(cx, cy, (17f + 6f) * scale, fill)
        fill.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (217 * alpha).roundToInt())
        canvas.drawCircle(cx, cy, 17f * scale, fill)
    }

    /** The "Allow full control?" dialog at the bottom of step 3; returns the Allow button. */
    private fun dialog(canvas: Canvas): RectF {
        rect.set(12f, 213f, 288f, 318f)
        fill.color = 0xFF2A2A2C.toInt()
        canvas.drawRoundRect(rect, 18f, 18f, fill)
        text.typeface = Typeface.DEFAULT
        text.letterSpacing = 0f
        text.textSize = 13f
        text.color = 0xFFECECEC.toInt()
        text.textAlign = Paint.Align.LEFT
        wrap(canvas, s(R.string.mini_dialog), 26f, 240f, 248f, 16f, 2)
        val deny = RectF(26f, 271f, 146f, 304f)
        val allow = RectF(154f, 271f, 274f, 304f)
        fill.color = 0xFF3A3A3C.toInt()
        canvas.drawRoundRect(deny, 16f, 16f, fill)
        fill.color = 0xFF2F7BF6.toInt()
        canvas.drawRoundRect(allow, 16f, 16f, fill)
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFECECEC.toInt()
        canvas.drawText(s(R.string.mini_deny), deny.centerX(), deny.centerY() + 4.5f, text)
        text.color = 0xFFFFFFFF.toInt()
        canvas.drawText(s(R.string.mini_allow), allow.centerX(), allow.centerY() + 4.5f, text)
        return allow
    }

    /** Draws [str] word-wrapped to [maxW], at most [maxLines] lines. */
    private fun wrap(canvas: Canvas, str: String, x: Float, y: Float, maxW: Float, lineH: Float, maxLines: Int) {
        val lines = ArrayList<String>()
        var cur = ""
        for (w in str.split(' ')) {
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (text.measureText(next) > maxW && cur.isNotEmpty()) {
                lines += cur
                cur = w
            } else {
                cur = next
            }
        }
        if (cur.isNotEmpty()) lines += cur
        lines.take(maxLines).forEachIndexed { i, l -> canvas.drawText(l, x, y + i * lineH, text) }
    }

    companion object {
        /** Step lengths in ms: Downloaded apps, Rokoreels, switch on + Allow. */
        val STEPS = longArrayOf(3600, 3600, 5200)
        private const val W = 176f
        private const val H = 252f
        private const val STAGE_SCALE = .5867f
    }
}
