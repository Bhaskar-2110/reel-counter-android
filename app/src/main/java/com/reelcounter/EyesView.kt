package com.reelcounter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Iris in her dark glass island, for the Today screen, onboarding, and the pause and block
 * screens. Tap her to poke her, drag her to stretch her; while a finger moves anywhere on
 * screen (see [watch]) her eyes follow it.
 */
class EyesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val rig = IrisRig(IrisRig.reduceMotion(context))
    private val renderer = IrisRenderer(context)
    private val dp = resources.displayMetrics.density
    private var layout: IrisLayout? = null
    private var lastFrame = 0L
    private val location = IntArray(2)

    /** Holds [mood]; Iris glides into it. */
    fun setMood(mood: Mood) {
        if (mood == Mood.PLEADING && rig.locked != Mood.PLEADING) rig.kick(2f)
        rig.locked = mood
    }

    /** Follows the count instead: [progress] is the share of the limit used (1 = reached). */
    fun setProgress(progress: Float) {
        rig.locked = null
        rig.progress = progress
    }

    /** A reel was counted while showing; Iris hops and may react. */
    fun onReel(progress: Float) {
        rig.locked = null
        rig.onReel(progress)
    }

    private val touch = IrisTouch(rig)

    /** A finger is at ([rawX], [rawY]) on screen; Iris looks at it for a moment. */
    fun watch(rawX: Float, rawY: Float) {
        getLocationOnScreen(location)
        rig.lookAt((rawX - location[0] - width / 2f) / (320 * dp), (rawY - location[1] - height / 2f) / (320 * dp))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = layout ?: return false
        return touch.onTouchEvent(this, event, width / 2f, height / 2f, l.s)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The island takes the middle; the rest is room for its round glow and movement.
        layout = IrisLayout.square(min(w, h) * ISLAND_SHARE)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val l = layout ?: return
        rig.update(dt)
        renderer.draw(canvas, rig, l, width / 2f, height / 2f, softGlow = min(width, height) / 2f)
        postInvalidateOnAnimation()
    }

    private companion object {
        /** How much of the view the island fills. */
        const val ISLAND_SHARE = 0.62f
    }
}
