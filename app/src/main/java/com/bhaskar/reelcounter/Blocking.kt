package com.bhaskar.reelcounter

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Why an app's Reels/Shorts (or, for focus hours, the whole app) are blocked right now. */
sealed class BlockReason {
    /** The daily reel limit for all apps, or the app's own, is reached (with blocking on). */
    object Limit : BlockReason()

    /** The user turned on "Block reels" for this app. */
    object Always : BlockReason()

    /** Inside focus hours that lock this app, until [until]. The whole app is closed. */
    data class Focus(val name: String, val until: LocalTime) : BlockReason()
}

/**
 * Focus hours: the chosen [apps] (bits by [Platform] ordinal) are locked from [startMinute] to
 * [endMinute] (minutes after midnight) on [days] (bit 0 = Monday … bit 6 = Sunday). A schedule
 * that ends before it starts runs over midnight, like Sleep 11 pm–7 am; then the morning part
 * belongs to the day it started on.
 *
 * Saved as "id;name;start;end;days;enabled;apps". Schedules saved before focus hours had app
 * picks have no apps field and lock every app.
 */
data class NoReelSchedule(
    val id: Int,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Int,
    val enabled: Boolean,
    val apps: Int = ALL_APPS,
) {
    fun runsOn(day: DayOfWeek) = days and (1 shl (day.value - 1)) != 0

    fun locks(platform: Platform) = apps and (1 shl platform.ordinal) != 0

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

    fun encode() = listOf(
        id, name.replace(";", " ").replace("|", " "), startMinute, endMinute, days, if (enabled) 1 else 0, apps,
    ).joinToString(";")

    companion object {
        const val EVERY_DAY = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKENDS = 0b1100000
        val ALL_APPS = (1 shl Platform.entries.size) - 1

        fun appsMask(platforms: Collection<Platform>) = platforms.fold(0) { m, p -> m or (1 shl p.ordinal) }

        fun decode(s: String): NoReelSchedule? {
            val p = s.split(';')
            if (p.size < 6) return null
            return NoReelSchedule(
                p[0].toIntOrNull() ?: return null, p[1],
                p[2].toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: return null,
                p[3].toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: return null,
                p[4].toIntOrNull() ?: return null, p[5] == "1",
                p.getOrNull(6)?.toIntOrNull() ?: ALL_APPS,
            )
        }

        /** Ready-made focus hours from the design, off until the user turns one on. */
        fun templates(ctx: Context) = listOf(
            NoReelSchedule(
                1, ctx.getString(R.string.focus_work), 10 * 60, 18 * 60, WEEKDAYS, false,
                appsMask(listOf(Platform.INSTAGRAM, Platform.YOUTUBE, Platform.SNAPCHAT)),
            ),
            NoReelSchedule(2, ctx.getString(R.string.focus_sleep), 23 * 60, 7 * 60, EVERY_DAY, false),
        )
    }
}

/** Blocking rules beyond the daily limit: per-app switches, focus hours and emergency unlocks. */
object Blocking {
    private const val KEY_SCHEDULES = "schedules"
    private const val KEY_UNLOCK_UNTIL = "unlock_until"

    /** Emergency unlocks a day, and how long each lasts. */
    const val UNLOCKS_PER_DAY = 2
    const val UNLOCK_MINUTES = 5

    /** "Block reels" for [platform]: its Reels/Shorts stay off, whatever the count. */
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

    // Focus hours

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cached: List<NoReelSchedule> = emptyList()

    /** The saved focus hours (parsed once per change: the service asks several times a second). */
    fun schedules(ctx: Context): List<NoReelSchedule> {
        val saved = CountStore.prefs(ctx).getString(KEY_SCHEDULES, null)
            ?: return NoReelSchedule.templates(ctx)
        if (saved != cachedRaw) {
            cached = saved.split('|').mapNotNull(NoReelSchedule::decode)
            cachedRaw = saved
        }
        return cached
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

    fun nextScheduleId(ctx: Context) = (schedules(ctx).maxOfOrNull { it.id } ?: 0) + 1

    /** Focus hours running now (for any app), or null. */
    fun activeSchedule(ctx: Context, now: LocalDateTime = LocalDateTime.now()) =
        schedules(ctx).firstOrNull { it.isActive(now) }

    /** Focus hours locking [platform] right now, unless an emergency unlock is running. */
    fun activeFocus(ctx: Context, platform: Platform, now: LocalDateTime = LocalDateTime.now()): NoReelSchedule? {
        if (isUnlocked(ctx)) return null
        return schedules(ctx).firstOrNull { it.locks(platform) && it.isActive(now) }
    }

    // Emergency unlock: 5 minutes, twice a day, for the focus-hours block screen.

    fun unlocksLeft(ctx: Context, today: LocalDate = LocalDate.now()): Int =
        (UNLOCKS_PER_DAY - CountStore.prefs(ctx).getInt("unlocks|$today", 0)).coerceAtLeast(0)

    fun isUnlocked(ctx: Context): Boolean =
        System.currentTimeMillis() < CountStore.prefs(ctx).getLong(KEY_UNLOCK_UNTIL, 0L)

    /** Uses one of today's unlocks; false when none are left. */
    fun emergencyUnlock(ctx: Context): Boolean {
        val today = LocalDate.now()
        if (unlocksLeft(ctx, today) <= 0) return false
        val p = CountStore.prefs(ctx)
        p.edit()
            .putInt("unlocks|$today", p.getInt("unlocks|$today", 0) + 1)
            .putLong(KEY_UNLOCK_UNTIL, System.currentTimeMillis() + UNLOCK_MINUTES * 60_000L)
            .apply()
        return true
    }

    // Reels

    /** Why [platform]'s Reels/Shorts are blocked now (not the whole app), or null when they aren't. */
    fun reason(ctx: Context, platform: Platform): BlockReason? {
        if (blockAlways(ctx, platform)) return BlockReason.Always
        if (CountStore.isOverLimit(ctx, platform)) return BlockReason.Limit
        return null
    }

    /** How many rules [platform] has: block, reel limit, timer, and focus hours that lock it. */
    fun ruleCount(ctx: Context, platform: Platform): Int = listOf(
        blockAlways(ctx, platform),
        CountStore.appLimit(ctx, platform) > 0,
        CountStore.appTimeLimitMinutes(ctx, platform) > 0,
    ).count { it }
}
