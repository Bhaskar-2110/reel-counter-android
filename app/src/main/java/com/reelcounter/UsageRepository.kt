package com.reelcounter

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/** Social media apps highlighted in screen time. Keep in sync with <queries> in the manifest. */
object SocialApps {
    val NAMES: Map<String, String> = linkedMapOf(
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.facebook.lite" to "Facebook Lite",
        "com.google.android.youtube" to "YouTube",
        "com.snapchat.android" to "Snapchat",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.twitter.android" to "X",
        "com.instagram.barcelona" to "Threads",
        "com.reddit.frontpage" to "Reddit",
        "com.pinterest" to "Pinterest",
        "com.linkedin.android" to "LinkedIn",
        "in.mohalla.sharechat" to "ShareChat",
        "in.mohalla.video" to "Moj",
        "com.eterno.shortvideos" to "Josh",
        "com.whatsapp" to "WhatsApp",
        "org.telegram.messenger" to "Telegram",
    )
}

/**
 * One app's use over a period.
 *
 * @param opens times the app came to the front after a different app or the home screen
 * @param longestMs the longest single stretch in the app
 * @param lastUsed when the app was last in front (epoch millis), 0 if unknown
 */
data class AppUsage(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val opens: Int,
    val notifications: Int = 0,
    val longestMs: Long = 0L,
    val lastUsed: Long = 0L,
)

/**
 * The whole phone's use over one day.
 *
 * @param notifications total notifications, or -1 when this phone doesn't share them with apps
 * @param firstPickup first unlock or screen-on of the day (epoch millis), or null
 * @param hourlyMs screen time in each hour of the day, starting at midnight
 */
data class DayReport(
    val apps: List<AppUsage>,
    val totalMs: Long,
    val unlocks: Int,
    val notifications: Int,
    val firstPickup: Long?,
    val hourlyMs: LongArray,
) {
    /** The reel apps Rokoreels watches (Platform), so the list matches its "Only the apps Rokoreels watches" note. */
    val social get() = apps.filter { Platform.forPackage(it.packageName) != null }
}

/**
 * Reads screen time from Android's usage events: the same data Digital Wellbeing shows. Needs
 * the user to allow Usage access for this app.
 */
object UsageRepository {

    // Event types that are public from Android 9 (API 28); notification events (12) aren't
    // public, so they're counted only when the phone reports them.
    private const val NOTIFICATION_INTERRUPTION = 12
    private const val SCREEN_INTERACTIVE = 15
    private const val KEYGUARD_HIDDEN = 18
    private const val HOUR_MS = 3_600_000L

    fun hasAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Social apps only, most used first, between [start] and [end] (epoch millis). */
    fun socialUsage(ctx: Context, start: Long, end: Long): List<AppUsage> = dayReport(ctx, start, end).social

    /**
     * Everything Android's usage data says about the period from [start] (a local midnight) to
     * [end]. Apps are the ones with a launcher icon, most used first; the home screen and this
     * app are left out.
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND/BACKGROUND are ACTIVITY_RESUMED/PAUSED on API 29+.
    fun dayReport(ctx: Context, start: Long, end: Long): DayReport {
        val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(start, end)
        val event = UsageEvents.Event()
        val resumedAt = HashMap<String, Long>()
        val time = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val notifications = HashMap<String, Int>()
        val longest = HashMap<String, Long>()
        val lastUsed = HashMap<String, Long>()
        // Per app, so hours only add up apps that make it into the list (and the total).
        val hourlyByPkg = HashMap<String, LongArray>()
        var unlocks = 0
        var notificationTotal = 0
        var firstPickup: Long? = null
        var lastResumedPkg: String? = null
        var visitPkg: String? = null
        var visitMs = 0L

        fun addSpan(pkg: String, from: Long, to: Long) {
            if (to <= from) return
            time.merge(pkg, to - from, Long::plus)
            lastUsed[pkg] = maxOf(lastUsed[pkg] ?: 0L, to)
            if (pkg == visitPkg) visitMs += to - from
            // Spread the span over the hours it covers.
            var t = from
            while (t < to) {
                val hour = ((t - start) / HOUR_MS).toInt().coerceIn(0, 23)
                val hourEnd = start + (hour + 1) * HOUR_MS
                val stop = minOf(to, hourEnd)
                hourlyByPkg.getOrPut(pkg) { LongArray(24) }[hour] += stop - t
                t = if (stop > t) stop else to
            }
        }

        fun endVisit() {
            visitPkg?.let { longest[it] = maxOf(longest[it] ?: 0L, visitMs) }
            visitPkg = null
            visitMs = 0L
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (pkg != lastResumedPkg) {
                        opens.merge(pkg, 1, Int::plus)
                        endVisit()
                        visitPkg = pkg
                        resumedAt[pkg] = event.timeStamp
                    } else {
                        resumedAt.putIfAbsent(pkg, event.timeStamp)
                    }
                    lastResumedPkg = pkg
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    resumedAt.remove(pkg)?.let { addSpan(pkg, it, event.timeStamp) }
                }
                KEYGUARD_HIDDEN -> {
                    unlocks++
                    if (firstPickup == null) firstPickup = event.timeStamp
                }
                SCREEN_INTERACTIVE -> if (firstPickup == null) firstPickup = event.timeStamp
                NOTIFICATION_INTERRUPTION -> {
                    notificationTotal++
                    notifications.merge(pkg, 1, Int::plus)
                }
            }
        }
        // An app still in front at the end of the range counts up to the end.
        resumedAt.forEach { (pkg, t) -> addSpan(pkg, t, end) }
        endVisit()

        val pm = ctx.packageManager
        val hidden = homeScreenPackages(pm) + ctx.packageName + "com.android.systemui" + "android"
        val labels = HashMap<String, String?>()
        fun labelFor(pkg: String): String? = labels.getOrPut(pkg) {
            SocialApps.NAMES[pkg] ?: try {
                if (pm.getLaunchIntentForPackage(pkg) == null) null
                else pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        val apps = (time.keys + opens.keys)
            .filter { it !in hidden }
            .mapNotNull { pkg ->
                val label = labelFor(pkg) ?: return@mapNotNull null
                AppUsage(
                    packageName = pkg,
                    label = label,
                    foregroundMs = time[pkg] ?: 0L,
                    opens = opens[pkg] ?: 0,
                    notifications = notifications[pkg] ?: 0,
                    longestMs = longest[pkg] ?: 0L,
                    lastUsed = lastUsed[pkg] ?: 0L,
                )
            }
            .filter { it.foregroundMs >= 1000 || it.opens > 0 }
            .sortedByDescending { it.foregroundMs }

        return DayReport(
            apps = apps,
            totalMs = apps.sumOf { it.foregroundMs },
            unlocks = unlocks,
            notifications = if (notificationTotal > 0) notificationTotal else -1,
            firstPickup = firstPickup,
            hourlyMs = LongArray(24) { h -> apps.sumOf { hourlyByPkg[it.packageName]?.get(h) ?: 0L } },
        )
    }

    private fun homeScreenPackages(pm: PackageManager): Set<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .toSet()
}
