package com.bhaskar.reelcounter

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** Why an app's Reels/Shorts are blocked right now. */
sealed class BlockReason {
    /** The daily reel limit for all apps, or the app's own, is reached (with blocking on). */
    object Limit : BlockReason()

    /** The user turned on "Block reels always" for this app. */
    object Always : BlockReason()

    /** Inside a no-reel-hours schedule, until [until]. */
    data class Schedule(val name: String, val until: LocalTime) : BlockReason()
}

/**
 * No-reel hours: Reels/Shorts/Spotlight are blocked from [startMinute] to [endMinute] (minutes
 * after midnight) on [days] (bit 0 = Monday … bit 6 = Sunday). A schedule that ends before it
 * starts runs over midnight, like Bedtime 11 pm–7 am; then the morning part belongs to the
 * day it started on.
 */
data class NoReelSchedule(
    val id: Int,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Int,
    val enabled: Boolean,
) {
    fun runsOn(day: DayOfWeek) = days and (1 shl (day.value - 1)) != 0

    /** True when [now] falls inside this schedule. */
    fun isActive(now: LocalDateTime): Boolean {
        if (!enabled || days == 0) return false
        val minute = now.hour * 60 + now.minute
        return if (startMinute <= endMinute) {
            runsOn(now.dayOfWeek) && minute in startMinute until endMinute
        } else {
            (runsOn(now.dayOfWeek) && minute >= startMinute) ||
                (runsOn(now.dayOfWeek.minus(1)) && minute < endMinute)
        }
    }

    val end: LocalTime get() = LocalTime.of(endMinute / 60, endMinute % 60)
    val start: LocalTime get() = LocalTime.of(startMinute / 60, startMinute % 60)

    fun encode() = listOf(id, name.replace(";", " ").replace("|", " "), startMinute, endMinute, days, if (enabled) 1 else 0)
        .joinToString(";")

    companion object {
        const val EVERY_DAY = 0b1111111
        const val WEEKDAYS = 0b0011111

        fun decode(s: String): NoReelSchedule? {
            val p = s.split(';')
            if (p.size < 6) return null
            return NoReelSchedule(
                p[0].toIntOrNull() ?: return null, p[1],
                p[2].toIntOrNull() ?: return null, p[3].toIntOrNull() ?: return null,
                p[4].toIntOrNull() ?: return null, p[5] == "1",
            )
        }

        /** Ready-made schedules, all off until the user turns one on. */
        fun templates(ctx: Context) = listOf(
            NoReelSchedule(1, ctx.getString(R.string.schedule_bedtime), 23 * 60, 7 * 60, EVERY_DAY, false),
            NoReelSchedule(2, ctx.getString(R.string.schedule_morning), 6 * 60, 9 * 60, EVERY_DAY, false),
            NoReelSchedule(3, ctx.getString(R.string.schedule_study), 14 * 60, 16 * 60, WEEKDAYS, false),
        )
    }
}

/** Blocking rules beyond the daily limit: per-app switches and no-reel hours. */
object Blocking {
    private const val KEY_SCHEDULES = "schedules"

    /** "Block reels always" for [platform]: its Reels/Shorts stay off, whatever the count. */
    fun blockAlways(ctx: Context, platform: Platform) =
        CountStore.prefs(ctx).getBoolean("block_always|${platform.name}", false)

    fun setBlockAlways(ctx: Context, platform: Platform, on: Boolean) =
        CountStore.prefs(ctx).edit().putBoolean("block_always|${platform.name}", on).apply()

    /**
     * "Allow a reel someone sends you": when [platform]'s Reels are blocked, the first video of
     * each visit still plays (a reel opened from a chat), and swiping to the next one blocks.
     */
    fun allowFirst(ctx: Context, platform: Platform) =
        CountStore.prefs(ctx).getBoolean("allow_first|${platform.name}", false)

    fun setAllowFirst(ctx: Context, platform: Platform, on: Boolean) =
        CountStore.prefs(ctx).edit().putBoolean("allow_first|${platform.name}", on).apply()

    fun schedules(ctx: Context): List<NoReelSchedule> {
        val saved = CountStore.prefs(ctx).getString(KEY_SCHEDULES, null)
            ?: return NoReelSchedule.templates(ctx)
        return saved.split('|').mapNotNull(NoReelSchedule::decode)
    }

    fun saveSchedules(ctx: Context, list: List<NoReelSchedule>) =
        CountStore.prefs(ctx).edit().putString(KEY_SCHEDULES, list.joinToString("|") { it.encode() }).apply()

    fun saveSchedule(ctx: Context, schedule: NoReelSchedule) {
        val list = schedules(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == schedule.id }
        if (i >= 0) list[i] = schedule else list += schedule
        saveSchedules(ctx, list)
    }

    fun deleteSchedule(ctx: Context, id: Int) = saveSchedules(ctx, schedules(ctx).filterNot { it.id == id })

    fun activeSchedule(ctx: Context, now: LocalDateTime = LocalDateTime.now()) =
        schedules(ctx).firstOrNull { it.isActive(now) }

    /** Why [platform]'s Reels/Shorts are blocked now, or null when they aren't. */
    fun reason(ctx: Context, platform: Platform): BlockReason? {
        if (blockAlways(ctx, platform)) return BlockReason.Always
        activeSchedule(ctx)?.let { return BlockReason.Schedule(it.name, it.end) }
        if (CountStore.isOverLimit(ctx, platform)) return BlockReason.Limit
        return null
    }
}
