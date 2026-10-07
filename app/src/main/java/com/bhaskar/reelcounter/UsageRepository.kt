package com.bhaskar.reelcounter

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Social media apps whose screen time is shown. Keep in sync with <queries> in the manifest. */
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

data class AppUsage(val packageName: String, val label: String, val foregroundMs: Long, val opens: Int)

/**
 * Reads screen time and opens from Android's usage events: the same data Digital Wellbeing
 * shows. Needs the user to allow Usage access for this app.
 */
object UsageRepository {

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

    /**
     * Foreground time and opens for each social app between [start] and [end] (epoch millis),
     * most-used first. An open is an app coming to the front after a different app was.
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND/BACKGROUND are ACTIVITY_RESUMED/PAUSED on API 29+.
    fun socialUsage(ctx: Context, start: Long, end: Long): List<AppUsage> {
        val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(start, end)
        val event = UsageEvents.Event()
        val resumedAt = HashMap<String, Long>()
        val time = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        var lastResumedPkg: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (pkg in SocialApps.NAMES) {
                        if (pkg != lastResumedPkg) {
                            opens.merge(pkg, 1, Int::plus)
                            resumedAt[pkg] = event.timeStamp
                        } else {
                            resumedAt.putIfAbsent(pkg, event.timeStamp)
                        }
                    }
                    lastResumedPkg = pkg
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    resumedAt.remove(pkg)?.let { time.merge(pkg, event.timeStamp - it, Long::plus) }
                }
            }
        }
        // An app still in front at the end of the range counts up to the end.
        resumedAt.forEach { (pkg, t) -> time.merge(pkg, end - t, Long::plus) }

        return (time.keys + opens.keys)
            .map { AppUsage(it, SocialApps.NAMES.getValue(it), time[it] ?: 0L, opens[it] ?: 0) }
            .filter { it.foregroundMs >= 1000 || it.opens > 0 }
            .sortedByDescending { it.foregroundMs }
    }
}
