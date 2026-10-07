package com.bhaskar.reelcounter

import android.content.Context

/**
 * The mood ramp from the design: one colour at a time, chosen by how close the user is to their
 * limit. Calm and cool first, warm and loud only near the end.
 */
object MoodColors {

    /** The theme's mood colour (light or dark variant) for [progress] (1 = limit reached). */
    fun forProgress(ctx: Context, progress: Float): Int = ctx.getColor(
        when {
            progress >= 1f -> R.color.mood_angry
            progress >= 0.85f -> R.color.mood_sad
            progress >= 0.6f -> R.color.mood_worried
            progress >= 0.3f -> R.color.mood_curious
            else -> R.color.mood_calm
        },
    )

    /** The Night Garden mood colour for [progress], for things that are always dark (island, widgets). */
    fun nightForProgress(progress: Float): Int = when {
        progress >= 1f -> 0xFFFF4D5E.toInt()
        progress >= 0.85f -> 0xFFFF8A65.toInt()
        progress >= 0.6f -> 0xFFFFC24D.toInt()
        progress >= 0.3f -> 0xFF5CC8FF.toInt()
        else -> 0xFF4FE3B0.toInt()
    }

    /** What Iris says on the Today screen for [progress]. */
    fun sayingFor(progress: Float): Int = when {
        progress >= 1f -> R.string.says_angry
        progress >= 0.85f -> R.string.says_sad
        progress >= 0.6f -> R.string.says_worried
        progress >= 0.3f -> R.string.says_curious
        else -> R.string.says_calm
    }

    /**
     * Weight of the hero number: it thickens from 500 to 800 as the limit nears ("weight
     * follows mood").
     */
    fun heroWeight(progress: Float): Int = (500 + 300 * progress.coerceIn(0f, 1f)).toInt()
}
