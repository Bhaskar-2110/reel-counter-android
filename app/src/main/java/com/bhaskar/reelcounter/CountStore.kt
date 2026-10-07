package com.bhaskar.reelcounter

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** One combined reel count per day (all apps together) and the user's settings, kept in SharedPreferences. */
object CountStore {
    private const val PREFS = "reel_counts"
    private const val KEY_LIMIT = "daily_limit"
    private const val KEY_BLOCK = "block_at_limit"
    private const val KEY_BADGE = "show_badge"
    private const val KEY_PAUSE = "pause_on_open"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(day: LocalDate) = "total|$day"

    fun total(ctx: Context, day: LocalDate): Int = prefs(ctx).getInt(key(day), 0)

    /** Adds one reel to today's count and returns the new total. */
    fun increment(ctx: Context): Int {
        val k = key(LocalDate.now())
        val p = prefs(ctx)
        val total = p.getInt(k, 0) + 1
        p.edit().putInt(k, total).apply()
        return total
    }

    fun resetDay(ctx: Context, day: LocalDate) =
        prefs(ctx).edit().remove(key(day)).remove(opensKey(day)).apply()

    // Social app opens and the pause screen shown on opening

    /** Opens per day that never show a pause screen. */
    const val FREE_OPENS = 5
    private val PAUSE_STEPS = listOf(3, 5, 10, 15, 20, 25, 30)

    private fun opensKey(day: LocalDate) = "opens|$day"

    fun opens(ctx: Context, day: LocalDate): Int = prefs(ctx).getInt(opensKey(day), 0)

    /** Records one more social app open today and returns today's new open count. */
    fun incrementOpens(ctx: Context): Int {
        val k = opensKey(LocalDate.now())
        val p = prefs(ctx)
        val opens = p.getInt(k, 0) + 1
        p.edit().putInt(k, opens).apply()
        return opens
    }

    /** Seconds the pause screen waits on the [opens]-th open of the day: 0 for free opens, then 3, 5, 10 … 30. */
    fun pauseSecondsFor(opens: Int): Int =
        if (opens <= FREE_OPENS) 0
        else PAUSE_STEPS[minOf(opens - FREE_OPENS - 1, PAUSE_STEPS.lastIndex)]

    fun pauseOnOpen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PAUSE, true)
    fun setPauseOnOpen(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_PAUSE, value).apply()

    fun limit(ctx: Context): Int = prefs(ctx).getInt(KEY_LIMIT, 30)
    fun setLimit(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_LIMIT, value).apply()


    fun blockAtLimit(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BLOCK, true)
    fun setBlockAtLimit(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_BLOCK, value).apply()

    fun showBadge(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BADGE, true)
    fun setShowBadge(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_BADGE, value).apply()
}
