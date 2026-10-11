package com.reelcounter

import android.content.Context
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The numbers behind Progress, worked out from the daily counts. The pure parts take plain
 * arrays so they can be tested without a phone.
 */
object Stats {

    /** Days in [totals] under [limit]. */
    fun daysUnder(totals: List<Int>, limit: Int) = totals.count { it < limit }

    /**
     * Percentage change from [previous] to [current] (e.g. -18 for 18% fewer), or null when there
     * is nothing to compare with.
     */
    fun changePercent(current: Double, previous: Double): Int? =
        if (previous <= 0.0) null else ((current - previous) / previous * 100).roundToInt()

    /**
     * Start hour of the [width]-hour stretch with the most reels, or null when [hours] is all
     * zero. Wraps past midnight (11 pm to 2 am is one stretch).
     */
    fun heaviestWindow(hours: IntArray, width: Int = 3): Int? {
        if (hours.all { it == 0 }) return null
        var best = 0
        var bestSum = -1
        for (start in hours.indices) {
            val sum = (0 until width).sumOf { hours[(start + it) % hours.size] }
            if (sum > bestSum) {
                bestSum = sum
                best = start
            }
        }
        return best
    }

    /**
     * The heaviest stretch of up to [width] hours as (start hour, length), with empty hours at
     * either end left off: all reels in one hour gives length 1. Null without any reels.
     */
    fun heaviestStretch(hours: IntArray, width: Int = 3): Pair<Int, Int>? {
        var start = heaviestWindow(hours, width) ?: return null
        var length = width
        while (length > 1 && hours[start % hours.size] == 0) {
            start = (start + 1) % hours.size
            length--
        }
        while (length > 1 && hours[(start + length - 1) % hours.size] == 0) length--
        return start to length
    }

    /**
     * Days in a row (ending at the last element) under the limit, given [under] per day, oldest
     * first; and the longest such run.
     */
    fun runs(under: List<Boolean>): Pair<Int, Int> {
        var run = 0
        var best = 0
        for (u in under) {
            run = if (u) run + 1 else 0
            best = maxOf(best, run)
        }
        return run to best
    }

    /** Minutes saved: how much less a day than before, times the days, never negative. */
    fun minutesSaved(currentPerDay: Double, previousPerDay: Double, days: Int): Long =
        ((previousPerDay - currentPerDay) * days).coerceAtLeast(0.0).roundToInt().toLong()

    // From the phone's data

    /** Each day from [from] to [to] inclusive. */
    fun days(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    fun totals(ctx: Context, days: List<LocalDate>) = days.map { CountStore.total(ctx, it) }

    /** Reels in each hour, summed over [days]. */
    fun hours(ctx: Context, days: List<LocalDate>): IntArray {
        val out = IntArray(24)
        days.forEach { d -> CountStore.hourly(ctx, d).forEachIndexed { h, v -> out[h] += v } }
        return out
    }

    /** Minutes in reel apps on each of [days]. */
    fun minutes(ctx: Context, days: List<LocalDate>) = days.map { d ->
        Platform.entries.sumOf { CountStore.appTime(ctx, d, it) } / 60_000.0
    }

    /** Only the days the app was counting: an install mid-week shouldn't look like a perfect week. */
    fun counted(ctx: Context, days: List<LocalDate>): List<LocalDate> {
        val first = CountStore.firstDay(ctx)
        return days.filter { !it.isBefore(first) }
    }

    /** Time in all reel apps today, in milliseconds. */
    fun timeTodayMs(ctx: Context): Long = Platform.entries.sumOf { CountStore.appTime(ctx, LocalDate.now(), it) }
}
