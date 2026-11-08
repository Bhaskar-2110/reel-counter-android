package com.reelcounter

import kotlin.math.floor

/**
 * The maths behind onboarding's combination lock (design/reel-limit-lock.html): three looping
 * 0–9 dials make a daily limit of 1–999, held for [HOLD_MS] to "lock it in". Pure, so it's tested.
 */
object LimitLock {
    const val DEFAULT = CountStore.LIMIT_DEFAULT
    const val HOLD_MS = 2500L
    /** A reel is about 20 seconds (100 reels ≈ 33 min); used wherever reels become minutes. */
    const val SECONDS_PER_REEL = 20
    val QUICK_PICKS = listOf(50, 100, 200)

    /** Hundreds, tens, ones of [value] (clamped to 0–999). */
    fun digits(value: Int): IntArray {
        val v = value.coerceIn(0, CountStore.LIMIT_MAX)
        return intArrayOf(v / 100, v / 10 % 10, v % 10)
    }

    /** The number the dials show; any int is taken modulo 10, as the dials loop. */
    fun value(hundreds: Int, tens: Int, ones: Int) = hundreds.mod(10) * 100 + tens.mod(10) * 10 + ones.mod(10)

    /** 000 isn't a limit. */
    fun isValid(value: Int) = value in CountStore.LIMIT_MIN..CountStore.LIMIT_MAX

    /** Minutes of reels a day, rounded half up, and at least 1 for any reels (never "0 min"). */
    fun minutes(reels: Int): Int {
        if (reels <= 0) return 0
        return ((reels * SECONDS_PER_REEL + 30) / 60).coerceAtLeast(1)
    }

    /** Colour level: up to 100 calm (mint), 200 curious (sky), 400 worried (amber), 700 sad (coral), above angry (red). */
    fun level(value: Int) = when {
        value <= 100 -> 0
        value <= 200 -> 1
        value <= 400 -> 2
        value <= 700 -> 3
        else -> 4
    }

    /** The shortest roll on a looping dial of [period] values from [from] to [to] (−4..5 on a 0–9 dial). */
    fun shortestRoll(from: Int, to: Int, period: Int = 10): Int {
        val d = (to - from).mod(period)
        return if (d > period / 2) d - period else d
    }

    /** How many of the oath's [words] are lit when the hold is [progress] (0..1) of the way. */
    fun litWords(progress: Float, words: Int): Int =
        floor(progress.coerceIn(0f, 1f) * (words + 0.999f)).toInt().coerceIn(0, words)

    /** Time between haptic pulses while holding: 260 ms at the start, 60 ms at the end. */
    fun pulseIntervalMs(progress: Float): Long = Math.round(260.0 - 200.0 * progress.coerceIn(0f, 1f))
}

/**
 * The maths behind onboarding's second lock, the daily social media time limit (2026-11-07,
 * same look as [LimitLock]): an hours dial (0–9) and a minutes dial (00, 15, 30, 45) make
 * 0:15–9:45. Pure, so it's tested.
 */
object TimeLock {
    const val DEFAULT = CountStore.TIME_LIMIT_DEFAULT
    const val STEP = 15
    /** Faces on the minutes drum: the four quarters twice round, so it rolls like the others. */
    val MINUTE_FACES = listOf(0, 15, 30, 45, 0, 15, 30, 45)
    val QUICK_PICKS = listOf(60, 120, 180)

    /** Hours dial index (0–9) and quarter index (0–3) for [minutes], rounded to 15 min. */
    fun dials(minutes: Int): IntArray {
        val m = (Math.round(minutes / STEP.toFloat()) * STEP).coerceIn(0, CountStore.TIME_LIMIT_MAX)
        return intArrayOf(m / 60, m % 60 / STEP)
    }

    /** Minutes for an hours index and a minutes-drum index (either loops). */
    fun value(hours: Int, minuteFace: Int) = hours.mod(10) * 60 + MINUTE_FACES[minuteFace.mod(MINUTE_FACES.size)]

    /** 0:00 isn't a limit. */
    fun isValid(minutes: Int) = minutes in CountStore.TIME_LIMIT_MIN..CountStore.TIME_LIMIT_MAX

    /** Colour level: up to 1 h calm, 2 h curious, 3 h worried, 5 h sad, more angry. */
    fun level(minutes: Int) = when {
        minutes <= 60 -> 0
        minutes <= 120 -> 1
        minutes <= 180 -> 2
        minutes <= 300 -> 3
        else -> 4
    }

    /** Hours and minutes, e.g. 150 → (2, 30). */
    fun split(minutes: Int) = minutes.coerceAtLeast(0).let { it / 60 to it % 60 }

    /** A week of it, in whole hours (rounded): 150 min/day → 18 h. */
    fun weekHours(minutes: Int) = Math.round(minutes * 7 / 60f)
}
