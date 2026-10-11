package com.reelcounter

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import androidx.appcompat.app.AppCompatDelegate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reel counts (today's total across apps and each app's own count), app opens and the user's
 * settings, kept in SharedPreferences.
 */
object CountStore {
    private const val PREFS = "reel_counts"
    private const val KEY_LIMIT = "daily_limit"
    private const val KEY_BLOCK = "block_at_limit"
    private const val KEY_BADGE = "show_badge"
    private const val KEY_PAUSE = "pause_on_open"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_FIRST_DAY = "first_day"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_GUESS = "guess"
    private const val KEY_ISLAND_OFFSET = "counter_offset_dp"
    private const val KEY_ISLAND_X = "counter_x"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_COUNTER_SIZE = "counter_size_dp"
    private const val KEY_WIDGETS = "widgets_enabled"
    private const val KEY_ISLAND_Y = "counter_y"
    private const val KEY_FREE_OPENS = "free_opens"
    private const val KEY_ONGOING = "ongoing_notification"
    private const val KEY_PRUNED = "pruned_on"
    private const val KEY_THEME_CHOICE = "theme_choice"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(day: LocalDate) = "total|$day"
    private fun appKey(day: LocalDate, platform: Platform) = "app|$day|${platform.name}"
    private fun appLimitKey(platform: Platform) = "app_limit|${platform.name}"
    private fun appTimeKey(day: LocalDate, platform: Platform) = "time|$day|${platform.name}"
    private fun appTimeLimitKey(platform: Platform) = "time_limit|${platform.name}"

    // Reel counts

    fun total(ctx: Context, day: LocalDate): Int = prefs(ctx).getInt(key(day), 0)

    fun appCount(ctx: Context, day: LocalDate, platform: Platform): Int =
        prefs(ctx).getInt(appKey(day, platform), 0)

    /** Adds one reel to today's total, to [platform]'s own count and to this hour's count. */
    fun increment(ctx: Context, platform: Platform) {
        val today = LocalDate.now()
        val hour = hourKey(today, java.time.LocalTime.now().hour)
        val p = prefs(ctx)
        p.edit()
            .putInt(key(today), p.getInt(key(today), 0) + 1)
            .putInt(appKey(today, platform), p.getInt(appKey(today, platform), 0) + 1)
            .putInt(hour, p.getInt(hour, 0) + 1)
            .apply()
    }

    private fun hourKey(day: LocalDate, hour: Int) = "hour|$day|$hour"

    /** Reels on [day] in each hour, midnight first (recorded from 8 Oct 2026 on). */
    fun hourly(ctx: Context, day: LocalDate): IntArray {
        val p = prefs(ctx)
        return IntArray(24) { p.getInt(hourKey(day, it), 0) }
    }

    // Time in each app

    /** Milliseconds spent in [platform] on [day]. */
    fun appTime(ctx: Context, day: LocalDate, platform: Platform): Long =
        prefs(ctx).getLong(appTimeKey(day, platform), 0L)

    /**
     * Adds time spent in [platform] between [startMillis] and [endMillis] (wall clock). A span
     * that crosses midnight is split between the two days, so each day starts again from zero.
     */
    fun addAppTime(ctx: Context, platform: Platform, startMillis: Long, endMillis: Long) {
        if (endMillis <= startMillis) return
        val zone = ZoneId.systemDefault()
        val p = prefs(ctx)
        val editor = p.edit()
        var from = startMillis
        while (from < endMillis) {
            val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(endMillis, nextMidnight)
            val key = appTimeKey(day, platform)
            editor.putLong(key, p.getLong(key, 0L) + (to - from))
            from = to
        }
        editor.apply()
    }

    // Limits

    /** The daily limit for all apps together. */
    fun limit(ctx: Context): Int = prefs(ctx).getInt(KEY_LIMIT, 30)
    fun setLimit(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_LIMIT, value).apply()

    /**
     * Minutes a day [platform] may be used, or 0 for no time limit. Once reached, the whole app
     * is blocked until midnight.
     */
    fun appTimeLimitMinutes(ctx: Context, platform: Platform): Int = prefs(ctx).getInt(appTimeLimitKey(platform), 0)
    fun setAppTimeLimitMinutes(ctx: Context, platform: Platform, minutes: Int) =
        prefs(ctx).edit().putInt(appTimeLimitKey(platform), minutes).apply()

    /** [platform]'s own daily limit, or 0 when it only counts toward the overall limit. */
    fun appLimit(ctx: Context, platform: Platform): Int = prefs(ctx).getInt(appLimitKey(platform), 0)
    fun setAppLimit(ctx: Context, platform: Platform, value: Int) =
        prefs(ctx).edit().putInt(appLimitKey(platform), value).apply()

    /**
     * How close [platform] is to being blocked today, as a fraction of whichever limit is
     * nearer: the overall one or the app's own. 1 or more means the limit is reached.
     */
    fun limitProgress(ctx: Context, platform: Platform?): Float {
        val today = LocalDate.now()
        var progress = total(ctx, today) / limit(ctx).toFloat()
        if (platform != null) {
            val own = appLimit(ctx, platform)
            if (own > 0) progress = maxOf(progress, appCount(ctx, today, platform) / own.toFloat())
        }
        return progress
    }

    /** True when [platform]'s Reels/Shorts should be blocked right now: a limit was reached, with blocking on. */
    fun isOverLimit(ctx: Context, platform: Platform): Boolean =
        blockAtLimit(ctx) && limitProgress(ctx, platform) >= 1f

    // Onboarding and tracked apps

    /**
     * True once onboarding is finished on this phone. Kept in [DEVICE_PREFS], which Android
     * never backs up: counts and settings may come back from a backup after a reinstall or on a
     * new phone, but permissions don't, so onboarding must run again to ask for them.
     */
    fun isOnboarded(ctx: Context): Boolean = devicePrefs(ctx).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(ctx: Context, value: Boolean = true) {
        // Finishing (or restarting) the tour drops any half-way progress.
        devicePrefs(ctx).edit().putBoolean(KEY_ONBOARDED, value).remove(KEY_ONB_PROGRESS).commit()
    }

    /**
     * Where the user is in onboarding, so it resumes there when the phone closed the app while
     * they were in Settings granting a permission (OnePlus and others do this often). Stored as
     * "step|guess|limit|usageSkipped|agreed"; null when not started.
     */
    fun onboardingProgress(ctx: Context): String? = devicePrefs(ctx).getString(KEY_ONB_PROGRESS, null)
    fun setOnboardingProgress(ctx: Context, value: String) =
        devicePrefs(ctx).edit().putString(KEY_ONB_PROGRESS, value).apply()

    private const val KEY_ONB_PROGRESS = "onboarding_progress"

    /** Settings that belong to this phone only; excluded from backup (res/xml/backup_rules.xml). */
    private fun devicePrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)

    const val DEVICE_PREFS = "device_state"

    // Health of the counting service, shown in Settings so problems can be spotted on the phone.
    // Kept in the device prefs so writing them doesn't redraw screens or widgets.

    /** When the counting service last started (epoch millis), or 0 if never. */
    fun serviceStartedAt(ctx: Context): Long = devicePrefs(ctx).getLong("service_started_at", 0L)
    fun markServiceStarted(ctx: Context) =
        devicePrefs(ctx).edit().putLong("service_started_at", System.currentTimeMillis()).apply()

    /** When the service last saw a supported app in front (epoch millis), and which one. */
    fun lastSeenAt(ctx: Context): Long = devicePrefs(ctx).getLong("last_seen_at", 0L)
    fun lastSeenApp(ctx: Context): String? = devicePrefs(ctx).getString("last_seen_app", null)
    fun markSeen(ctx: Context, platform: Platform) {
        val now = System.currentTimeMillis()
        // At most once a minute: this runs on every screen check.
        if (now - lastSeenAt(ctx) < 60_000 && lastSeenApp(ctx) == platform.shortName) return
        devicePrefs(ctx).edit().putLong("last_seen_at", now).putString("last_seen_app", platform.shortName).apply()
    }

    /** Whether [platform] is counted at all; chosen in onboarding ("Which apps?"). */
    fun isTracked(ctx: Context, platform: Platform): Boolean =
        prefs(ctx).getBoolean("tracked|${platform.name}", true)

    fun setTracked(ctx: Context, platform: Platform, tracked: Boolean) =
        prefs(ctx).edit().putBoolean("tracked|${platform.name}", tracked).apply()

    /** The user's onboarding guess of reels a day, or 0 if they never gave one. */
    fun guess(ctx: Context): Int = prefs(ctx).getInt(KEY_GUESS, 0)
    fun setGuess(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_GUESS, value).apply()

    // Streak: days in a row without reaching a limit

    /**
     * @param current days in a row, ending today, without reaching a limit (today counts while
     *   it's still under); 0 if a limit was reached today
     * @param best the longest such run so far
     * @param brokenToday true when a limit was reached today
     */
    data class Streak(val current: Int, val best: Int, val brokenToday: Boolean)

    /** Remembers that a limit (overall or an app's own) was reached on [day]. */
    fun markLimitHit(ctx: Context, day: LocalDate) =
        prefs(ctx).edit().putBoolean("hit|$day", true).apply()

    /** True when a limit was reached on [day]. */
    fun limitHitOn(ctx: Context, day: LocalDate): Boolean = limitHit(ctx, day)

    /** Days recorded before limit hits were tracked count as hit if the total reached the limit. */
    private fun limitHit(ctx: Context, day: LocalDate): Boolean =
        prefs(ctx).getBoolean("hit|$day", false) || total(ctx, day) >= limit(ctx)

    /** The first day with any recorded use, so days before the app was installed don't count. */
    fun firstDay(ctx: Context): LocalDate {
        val p = prefs(ctx)
        p.getString(KEY_FIRST_DAY, null)?.let { return LocalDate.parse(it) }
        val earliest = p.all.keys
            .mapNotNull { key -> Regex("\\d{4}-\\d{2}-\\d{2}").find(key)?.value }
            .minOrNull()
            ?.let(LocalDate::parse)
            ?: LocalDate.now()
        p.edit().putString(KEY_FIRST_DAY, earliest.toString()).apply()
        return earliest
    }

    fun streak(ctx: Context): Streak {
        val today = LocalDate.now()
        var day = firstDay(ctx)
        var run = 0
        var best = 0
        while (!day.isAfter(today)) {
            run = if (limitHit(ctx, day)) 0 else run + 1
            best = maxOf(best, run)
            day = day.plusDays(1)
        }
        return Streak(current = run, best = best, brokenToday = limitHit(ctx, today))
    }

    // Social app opens and the pause screen shown on opening

    /** Opens per day that never show a pause screen, unless the user picked another number. */
    const val FREE_OPENS = 5
    val FREE_OPENS_CHOICES = listOf(3, 5, 10)
    private val PAUSE_STEPS = listOf(3, 5, 10, 15, 20, 25, 30)

    private fun opensKey(day: LocalDate) = "opens|$day"
    private fun appOpensKey(day: LocalDate, platform: Platform) = "opens|$day|${platform.name}"

    fun opens(ctx: Context, day: LocalDate): Int = prefs(ctx).getInt(opensKey(day), 0)

    /** Times [platform] was opened on [day] (recorded from the Rokoreels redesign on). */
    fun appOpens(ctx: Context, day: LocalDate, platform: Platform): Int = prefs(ctx).getInt(appOpensKey(day, platform), 0)

    /** Records one more open of [platform] today and returns today's new open count across apps. */
    fun incrementOpens(ctx: Context, platform: Platform): Int {
        val today = LocalDate.now()
        val k = opensKey(today)
        val ak = appOpensKey(today, platform)
        val p = prefs(ctx)
        val opens = p.getInt(k, 0) + 1
        p.edit().putInt(k, opens).putInt(ak, p.getInt(ak, 0) + 1).apply()
        return opens
    }

    /** Opens a day before the pause screen starts (Settings → Pause before opening: 3, 5 or 10). */
    fun freeOpens(ctx: Context): Int = prefs(ctx).getInt(KEY_FREE_OPENS, FREE_OPENS)
    fun setFreeOpens(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_FREE_OPENS, value.coerceIn(1, 50)).apply()

    /** Seconds the pause screen waits on the [opens]-th open of the day: 0 for free opens, then 3, 5, 10 … 30. */
    fun pauseSecondsFor(opens: Int, free: Int = FREE_OPENS): Int =
        if (opens <= free) 0
        else PAUSE_STEPS[minOf(opens - free - 1, PAUSE_STEPS.lastIndex)]

    fun pauseSecondsFor(ctx: Context, opens: Int): Int = pauseSecondsFor(opens, freeOpens(ctx))

    fun pauseOnOpen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PAUSE, true)
    fun setPauseOnOpen(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_PAUSE, value).apply()

    /** The ongoing "N reels today" notification, which also keeps counting alive (More screen). */
    fun ongoingNotification(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONGOING, true)
    fun setOngoingNotification(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_ONGOING, value).apply()

    // Housekeeping

    /**
     * Drops per-hour, per-app and per-app-time keys older than [KEEP_DETAIL_DAYS], and everything
     * else dated older than [KEEP_DAYS]. SharedPreferences rewrites the whole file on every
     * save, so without this every counted reel would get slower as the months go by. Runs at most
     * once a day.
     */
    fun prune(ctx: Context, today: LocalDate = LocalDate.now()) {
        val p = prefs(ctx)
        if (p.getString(KEY_PRUNED, null) == today.toString()) return
        val stale = staleKeys(p.all.keys, today)
        val edit = p.edit().putString(KEY_PRUNED, today.toString())
        stale.forEach { edit.remove(it) }
        edit.apply()
    }

    /** The keys [prune] removes from [keys] on [today]; split out so it can be tested. */
    fun staleKeys(keys: Collection<String>, today: LocalDate): List<String> {
        val detailCutoff = today.minusDays(KEEP_DETAIL_DAYS)
        val cutoff = today.minusDays(KEEP_DAYS)
        return keys.filter { key ->
            val parts = key.split('|')
            if (parts.size < 2) return@filter false
            val day = try {
                LocalDate.parse(parts[1])
            } catch (e: Exception) {
                return@filter false
            }
            val detail = parts[0] == "hour" || parts[0] == "app" || parts[0] == "time" || (parts[0] == "opens" && parts.size > 2)
            day.isBefore(if (detail) detailCutoff else cutoff)
        }
    }

    /** Per-app and per-hour detail is kept for this many days (two calendar months fit). */
    const val KEEP_DETAIL_DAYS = 70L
    /** Daily totals, opens and limit hits are kept for about two years. */
    const val KEEP_DAYS = 730L

    // Other settings

    fun blockAtLimit(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BLOCK, true)
    fun setBlockAtLimit(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_BLOCK, value).apply()

    fun showBadge(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BADGE, true)
    fun setShowBadge(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_BADGE, value).apply()

    /**
     * The app's theme. New installs start in Dark (Night Garden), since late-night scrolling
     * happens in the dark. Earlier versions stored only a night mode; that's carried over.
     */
    fun theme(ctx: Context): AppTheme {
        val p = prefs(ctx)
        p.getString(KEY_THEME_CHOICE, null)?.let { name -> AppTheme.entries.firstOrNull { it.name == name }?.let { return it } }
        return when (p.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_YES)) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> AppTheme.SYSTEM
            AppCompatDelegate.MODE_NIGHT_NO -> AppTheme.LIGHT
            else -> AppTheme.DARK
        }
    }

    fun setTheme(ctx: Context, value: AppTheme) = prefs(ctx).edit().putString(KEY_THEME_CHOICE, value.name).apply()

    /**
     * How far to move the counter from its spot just below the camera, in dp; negative is up.
     * The setting is stored under a new key: the old one lined the eyes up beside the camera,
     * which no longer applies.
     */
    fun islandOffsetDp(ctx: Context): Int = prefs(ctx).getInt(KEY_ISLAND_OFFSET, 0)
    fun setIslandOffsetDp(ctx: Context, value: Int) {
        // Adjusting the camera line puts a dragged island back on the camera, where it shows.
        setIslandPosition(ctx, null)
        prefs(ctx).edit().putInt(KEY_ISLAND_OFFSET, value.coerceIn(-30, 30)).apply()
    }

    /** Size of the floating counter (Iris's face) in dp; the design's is 56. */
    fun counterSizeDp(ctx: Context): Int = prefs(ctx).getInt(KEY_COUNTER_SIZE, COUNTER_SIZE_DEFAULT)
    fun setCounterSizeDp(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_COUNTER_SIZE, value.coerceIn(COUNTER_SIZE_MIN, COUNTER_SIZE_MAX)).apply()

    const val COUNTER_SIZE_MIN = 48
    const val COUNTER_SIZE_DEFAULT = 56
    const val COUNTER_SIZE_MAX = 104

    /** Whether the home-screen widgets show in the launcher's widget list (More → Home-screen widgets). */
    fun widgetsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WIDGETS, true)
    fun setWidgetsEnabled(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_WIDGETS, value).apply()

    /** Iris keeps changing mood but drops the wobble, sway, tears and huffs. */
    fun reduceMotion(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_REDUCE_MOTION, false)
    fun setReduceMotion(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    /**
     * Where the user dragged the island's window to, in screen pixels, or null while it sits on
     * the camera.
     */
    fun islandPosition(ctx: Context): Point? {
        val p = prefs(ctx)
        if (!p.contains(KEY_ISLAND_X)) return null
        return Point(p.getInt(KEY_ISLAND_X, 0), p.getInt(KEY_ISLAND_Y, 0))
    }

    fun setIslandPosition(ctx: Context, position: Point?) {
        val edit = prefs(ctx).edit()
        if (position == null) edit.remove(KEY_ISLAND_X).remove(KEY_ISLAND_Y)
        else edit.putInt(KEY_ISLAND_X, position.x).putInt(KEY_ISLAND_Y, position.y)
        edit.apply()
    }
}
