package com.bhaskar.reelcounter

import android.content.Context

/**
 * The mood ramp from the design: one colour at a time, chosen by how close the user is to their
 * limit. Calm and cool first, warm and loud only near the end.
 */
object MoodColors {

    /** Step of the ramp for [progress] (1 = limit reached): 0 calm … 4 angry (design moodIdx). */
    fun index(progress: Float): Int = when {
        progress >= 1f -> 4
        progress >= 0.85f -> 3
        progress >= 0.6f -> 2
        progress >= 0.3f -> 1
        else -> 0
    }

    /** The current theme's mood colour for [progress]. */
    fun forProgress(ctx: Context, progress: Float): Int = ctx.moodColor(index(progress))

    /** The Night Garden mood colour for [progress], for things that are always dark (island, overlays). */
    fun nightForProgress(progress: Float): Int = when (index(progress)) {
        4 -> 0xFFFF4D5E.toInt()
        3 -> 0xFFFF8A65.toInt()
        2 -> 0xFFFFC24D.toInt()
        1 -> 0xFF5CC8FF.toInt()
        else -> 0xFF4FE3B0.toInt()
    }

    /** Iris's mood word on Home: calm, curious, worried, sad, angry. */
    fun wordFor(progress: Float): Int = when (index(progress)) {
        4 -> R.string.mood_word_angry
        3 -> R.string.mood_word_sad
        2 -> R.string.mood_word_worried
        1 -> R.string.mood_word_curious
        else -> R.string.mood_word_calm
    }

    /** What Iris says on the widgets for [progress]. */
    fun sayingFor(progress: Float): Int = when (index(progress)) {
        4 -> R.string.says_angry
        3 -> R.string.says_sad
        2 -> R.string.says_worried
        1 -> R.string.says_curious
        else -> R.string.says_calm
    }

    /**
     * Weight of the hero numbers: 600 when calm, up to 800 at the limit, so the number literally
     * gets louder as Iris worries.
     */
    fun heroWeight(progress: Float): Int = 600 + 50 * index(progress)
}
