package com.reelcounter

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touch on an Iris that stays in place (in the app), as in the demo: a tap pokes her when the
 * finger lifts, and dragging stretches her toward the finger until it lets go.
 */
class IrisTouch(private val rig: IrisRig) {

    private var downX = 0f
    private var downY = 0f
    private var tapX = 0f
    private var tapY = 0f
    private var active = false
    private var moved = false

    /** Handles [e] on [view] for an island centred at ([cx], [cy]) of size [s]. */
    fun onTouchEvent(view: View, e: MotionEvent, cx: Float, cy: Float, s: Float): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapX = (e.x - cx) / s
                tapY = (e.y - cy) / s
                // Only Iris herself takes the touch, not the space around her.
                if (abs(tapX) > .6f || abs(tapY) > .6f) return false
                active = true
                moved = false
                downX = e.x
                downY = e.y
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                val dx = (e.x - downX) / s
                val dy = (e.y - downY) / s
                if (hypot(dx, dy) > .05f) moved = true
                if (moved) rig.drag(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active) {
                active = false
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    rig.poke(tapX, tapY)
                    view.performClick()
                } else {
                    rig.release()
                }
                return true
            }
        }
        return active
    }
}
