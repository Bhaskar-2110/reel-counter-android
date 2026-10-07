package com.bhaskar.reelcounter

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** Big animated [Eyes] on their own, for the block screen, pause screen and main screen. */
class EyesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val eyes = Eyes()
    private var lastFrame = 0L

    fun setMood(mood: Mood, tiredness: Float = 0f) {
        eyes.mood = mood
        eyes.tiredness = tiredness
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        eyes.update(dt)

        // Eyes a little wider than tall, like the reference face, with a small mouth below.
        val eyeH = height * 0.46f
        val eyeW = eyeH * 1.16f
        eyes.draw(canvas, width / 2f, height * 0.46f, eyeW, eyeH, eyeW * 0.32f)
        eyes.drawMouth(canvas, width / 2f, height * 0.86f, eyeW * 0.75f)
        postInvalidateOnAnimation()
    }
}
