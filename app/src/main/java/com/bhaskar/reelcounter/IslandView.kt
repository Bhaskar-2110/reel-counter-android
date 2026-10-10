package com.bhaskar.reelcounter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * The floating counter: Iris's face exactly as in design_iris_docs, a dark glass square (56dp
 * as designed, or the size picked in Settings)
 * with her eyes and, under them, a line that rolls between today's reel count and today's time
 * in the app. The face is an [IrisRig]: it glides between moods, blinks, looks around, hops on
 * each reel, stretches when dragged and reacts when poked.
 *
 * The view fills a fixed-size overlay window and draws the face with its top at [islandTop],
 * so the window never has to be resized while the face moves. The window sits just below the
 * camera until the user drags it somewhere else.
 */
class IslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Top of the island in this view; negative means centered in the view (the preview). */
    var islandTop = -1f

    constructor(context: Context, islandTop: Float) : this(context) {
        this.islandTop = islandTop
    }

    private val dp = resources.displayMetrics.density
    private val rig = IrisRig(IrisRig.reduceMotion(context))
    private val renderer = IrisRenderer(context)
    private val line = NumberLine()
    private var layout = IrisLayout.square(CountStore.counterSizeDp(context) * dp, withNumber = true)

    /** The face's size in dp (Settings → Counter size). */
    var sizeDp: Int
        get() = (layout.s / dp).toInt()
        set(value) {
            layout = IrisLayout.square(value * dp, withNumber = true)
        }

    /** Holds a mood instead of following the count (the preview's mood chips), or null. */
    var lockedMood: Mood?
        get() = rig.locked
        set(value) {
            rig.locked = value
        }

    private var count = -1
    private var sessionStart = 0L
    private var time = 0f
    private var countShownAt = 0f

    private var appear = 0f
    private var appearV = 0f
    private var appearTarget = 1f
    private var onHidden: (() -> Unit)? = null
    private var lastFrame = 0L

    private var dragging = false
    private var lagX = 0f
    private var lagY = 0f

    /**
     * Updates the count, when the user opened the app's timer ([sessionStart],
     * SystemClock.elapsedRealtime, or 0 to show only the count) and the share of the limit used.
     */
    fun setState(count: Int, sessionStart: Long = 0L, progress: Float = 0f) {
        if (this.count >= 0 && count != this.count) {
            countShownAt = time
            rig.popNumber()
        }
        this.count = count
        this.sessionStart = sessionStart
        rig.progress = progress
    }

    /** A reel was just counted, taking the share of the limit to [progress]. */
    fun onReel(progress: Float) {
        rig.onReel(progress)
        countShownAt = time
    }

    /** True after a long while with no reel: Iris dozes off; false wakes her. */
    var sleepy: Boolean
        get() = rig.isSleeping
        set(value) {
            if (value && !rig.isSleeping) rig.trigger(Mood.SLEEPY) else if (!value) rig.wake()
        }

    /** Iris pleads for [seconds], leaning in (after "Continue anyway", or near a time limit). */
    fun plead(seconds: Float) {
        rig.trigger(Mood.PLEADING, seconds)
        rig.kick(2f)
    }

    /** Kicks the scale, e.g. when previewing Pleading. */
    fun kick() = rig.kick(2f)

    /** When the user last touched the counter (SystemClock.elapsedRealtime), or 0. */
    var lastTouchAt = 0L
        private set

    /** A tap at ([x], [y]) in this view; only taps on the face itself count. */
    fun poke(x: Float, y: Float) {
        lastTouchAt = SystemClock.elapsedRealtime()
        val lx = (x - width / 2f) / layout.s
        val ly = (y - centerY()) / layout.s
        if (abs(lx) <= .6f && abs(ly) <= .6f) rig.poke(lx, ly)
    }

    private val location = IntArray(2)

    /** A finger is at ([rawX], [rawY]) on screen; Iris looks at it for a moment. */
    fun watch(rawX: Float, rawY: Float) {
        getLocationOnScreen(location)
        rig.lookAt((rawX - location[0] - width / 2f) / (320 * dp), (rawY - location[1] - centerY()) / (320 * dp))
    }

    /** The preview in Settings stays put: tap to poke, drag to stretch, as in the demo. */
    private val touch = IrisTouch(rig)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        touch.onTouchEvent(this, event, width / 2f, centerY(), layout.s)

    /** The island is being dragged; the finger just moved by ([dx], [dy]) pixels. */
    fun dragBy(dx: Float, dy: Float) {
        lastTouchAt = SystemClock.elapsedRealtime()
        dragging = true
        lagX += dx
        lagY += dy
    }

    /** The drag ended. */
    fun release() {
        dragging = false
        lagX = 0f
        lagY = 0f
        rig.release()
    }

    /**
     * When true (the preview), the line swaps between count and timer every [SWAP_SECONDS].
     * Otherwise each new reel shows the count for [COUNT_HOLD_SECONDS], then the line switches to
     * the timer until the next reel, so every swipe shows both.
     */
    var cycle = false

    /** The count or today's time; switches and count changes roll, the timer's ticks don't. */
    private fun updateLine() {
        val timer = sessionStart > 0L && if (cycle) {
            (time / SWAP_SECONDS).toInt() % 2 == 1
        } else {
            time - countShownAt > COUNT_HOLD_SECONDS
        }
        val text = if (timer) formatSession((SystemClock.elapsedRealtime() - sessionStart) / 1000) else count.coerceAtLeast(0).toString()
        if (line.text.isEmpty()) {
            line.text = text
            line.timer = timer
            return
        }
        // Switching between count and time rolls; a new count only pops (the rig's number spring).
        if (timer != line.timer) {
            line.previous = line.text
            line.roll = if (appear < 0.5f) 1f else 0f
            line.timer = timer
        }
        line.text = text
    }

    private fun formatSession(seconds: Long): String = when {
        seconds < 3600 -> "%d:%02d".format(seconds / 60, seconds % 60)
        else -> "%d:%02d h".format(seconds / 3600, (seconds % 3600) / 60)
    }

    /** Shrinks the island back into a dot, then calls [then]. */
    fun dismiss(then: () -> Unit) {
        appearTarget = 0f
        onHidden = then
    }

    private fun centerY() = if (islandTop < 0f) height / 2f else islandTop + layout.h / 2f

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        time += dt
        val l = layout

        // Grows out of a dot with an underdamped spring, and shrinks back into one.
        appearV += (170f * (appearTarget - appear) - 15f * appearV) * dt
        appear += appearV * dt
        if (appearTarget == 0f && appear < 0.03f && abs(appearV) < 2f) {
            onHidden?.invoke()
            onHidden = null
            return
        }

        // While dragged, the island stretches toward where the finger is heading.
        if (dragging) rig.drag(lagX / l.s, lagY / l.s)
        val decay = exp(-dt * 10f)
        lagX *= decay
        lagY *= decay

        rig.update(dt)
        updateLine()
        line.roll = min(1f, line.roll + dt * 5f)

        val a = appear.coerceAtLeast(0f)
        renderer.draw(
            canvas, rig, l,
            cx = width / 2f,
            cy = centerY(),
            grow = 0.15f + 0.85f * min(a, 1.2f),
            visible = (a * 1.4f).coerceIn(0f, 1f),
            number = if (a > 0.55f) line else null,
        )
        postInvalidateOnAnimation()
    }

    companion object {
        /** Seconds between swapping the reel count and the app timer. */
        private const val SWAP_SECONDS = 4f
        private const val COUNT_HOLD_SECONDS = 3f
    }
}
