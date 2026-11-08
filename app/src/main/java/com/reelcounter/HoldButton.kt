package com.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.core.graphics.ColorUtils
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Hold to lock it in" (design/reel-limit-lock.html .hold): an accent bar fills left to right
 * while the button is held for [LimitLock.HOLD_MS]; letting go early drains it. Once full the
 * button turns into a solid "Continue". TalkBack users get it in one double-tap (holding is hard
 * with a screen reader).
 */
class HoldButton(ctx: Context, private val listener: Listener) : View(ctx) {

    interface Listener {
        fun onHoldStart()
        fun onHoldProgress(progress: Float)
        /** Each haptic pulse while holding (for the design's matching click). */
        fun onPulse(progress: Float) {}
        fun onLetGoEarly()
        fun onLocked()
        fun onContinue()
    }

    private val dp = resources.displayMetrics.density
    private val accent = ctx.attrColor(R.attr.rrAccent)
    private val onAccent = ctx.attrColor(R.attr.rrOnAccent)
    private val text = ctx.attrColor(R.attr.rrText)
    private val raised = ctx.attrColor(R.attr.rrRaised)
    private val outline = ctx.attrColor(R.attr.rrLine)
    private val holdLabel = ctx.getString(R.string.lock_hold)
    private val doneLabel = ctx.getString(R.string.onb_continue)
    private val icon = ctx.getDrawable(R.drawable.ic_lock)!!.mutate()

    var progress = 0f
        private set
    var done = false
        private set
    private var down = false
    private var continueTap = false
    private var sincePulse = 0f
    private var frameT = 0L
    private var running = false

    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.inter)
        fontVariationSettings = "'wght' 700"
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    }

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (60 * dp).roundToInt())

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled || done) 1f else .45f
        if (!enabled) release()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = 18f * dp
        val inset = border.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)
        bg.color = if (done) accent else raised
        canvas.drawRoundRect(rect, r, r, bg)
        if (!done && progress > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, width * progress, height.toFloat())
            bg.color = accent
            canvas.drawRoundRect(rect, r, r, bg)
            canvas.restore()
        }
        border.color = when {
            done -> accent
            !isEnabled -> outline
            else -> ColorUtils.blendARGB(outline, accent, .55f)
        }
        canvas.drawRoundRect(rect, r, r, border)
        if (done) {
            drawLabel(canvas, doneLabel, onAccent, withIcon = false)
            return
        }
        drawLabel(canvas, holdLabel, text, withIcon = true)
        if (progress > 0f) {
            // The part of the label under the fill turns dark-on-accent.
            canvas.save()
            canvas.clipRect(0f, 0f, width * progress, height.toFloat())
            drawLabel(canvas, holdLabel, onAccent, withIcon = true)
            canvas.restore()
        }
    }

    private fun drawLabel(canvas: Canvas, s: String, color: Int, withIcon: Boolean) {
        val iconSize = (20 * dp).roundToInt()
        val gap = 10 * dp
        val textW = label.measureText(s)
        val total = textW + if (withIcon) iconSize + gap else 0f
        var x = (width - total) / 2f
        val cy = height / 2f
        if (withIcon) {
            icon.setTint(color)
            icon.setBounds(x.roundToInt(), (cy - iconSize / 2f).roundToInt(), x.roundToInt() + iconSize, (cy + iconSize / 2f).roundToInt())
            icon.draw(canvas)
            x += iconSize + gap
        }
        label.color = color
        val fm = label.fontMetrics
        canvas.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, label)
    }

    // Holding

    private fun press() {
        if (done || !isEnabled || down) return
        down = true
        sincePulse = 1f
        listener.onHoldStart()
        if (!CountStore.reduceMotion(context)) animate().scaleX(.975f).scaleY(.975f).setDuration(200).start()
        kick()
    }

    private fun release() {
        if (!down) return
        down = false
        animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        if (!done && progress < 1f) listener.onLetGoEarly()
        kick()
    }

    /** Fills it now (TalkBack's double-tap, or the hold reaching the end). */
    fun lockNow() {
        if (done) return
        done = true
        down = false
        progress = 0f
        animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        alpha = 1f
        updateDescription()
        listener.onLocked()
        invalidate()
    }

    /** Back to "Hold to lock it in" ("Change the number"). */
    fun reset() {
        done = false
        progress = 0f
        alpha = if (isEnabled) 1f else .45f
        updateDescription()
        listener.onHoldProgress(0f)
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = if (done) doneLabel else holdLabel
    }

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
        if (done) return false
        if (down) {
            progress = min(1f, progress + dt * 1000f / LimitLock.HOLD_MS)
            sincePulse += dt
            if (sincePulse * 1000f >= LimitLock.pulseIntervalMs(progress)) {
                sincePulse = 0f
                Haptics.holdPulse(context, progress)
                listener.onPulse(progress)
            }
            if (progress >= 1f) {
                lockNow()
                return false
            }
        } else if (progress > 0f) {
            progress = (progress - dt * 2.4f).coerceAtLeast(0f)
        }
        listener.onHoldProgress(progress)
        invalidate()
        return down || progress > 0f
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        down = false
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // The press that fills the bar must not also count as tapping Continue.
                continueTap = done
                if (!done) press()
            }
            MotionEvent.ACTION_UP -> {
                if (continueTap && done && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick()
                continueTap = false
                release()
            }
            MotionEvent.ACTION_CANCEL -> {
                continueTap = false
                release()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (done) listener.onContinue()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isConfirm(keyCode) && isEnabled) {
            if (event.repeatCount == 0) {
                continueTap = done
                if (!done) press()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isConfirm(keyCode) && isEnabled) {
            if (done) {
                if (continueTap) performClick()
            } else {
                release()
            }
            continueTap = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isConfirm(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_SPACE || k == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
        info.isEnabled = isEnabled
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && isEnabled) {
            if (done) performClick() else lockNow()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
