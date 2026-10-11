package com.reelcounter

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import java.time.LocalDate

/**
 * Anonymous usage stats through Google Analytics for Firebase. On by default; More → Share
 * anonymous usage stats turns it off. No advertising ID (see the manifest).
 *
 * Only step names, app names, counts, times and limits are sent: never names, emails, or anything
 * the Accessibility service sees on screen. When signed in, events carry the Firebase user ID
 * (not the email; Google Analytics doesn't allow that), which Account's Firestore profile maps
 * to a person.
 */
object Analytics {

    private const val KEY_ENABLED = "analytics_enabled"
    private const val KEY_SUMMARY_DAY = "last_summary_day"

    private val main = Handler(Looper.getMainLooper())
    private var pendingLimit: Runnable? = null

    /** Applies the saved choice at app start. */
    fun init(ctx: Context) {
        fa(ctx)?.setAnalyticsCollectionEnabled(enabled(ctx))
        setUser(ctx, Account.current(ctx)?.uid)
    }

    /** Links the events that follow to the signed-in account's ID, or to nobody with null. */
    fun setUser(ctx: Context, uid: String?) {
        val fa = fa(ctx) ?: return
        fa.setUserId(uid)
        fa.setUserProperty("signed_in", if (uid != null) "yes" else "no")
    }

    fun enabled(ctx: Context): Boolean = CountStore.prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        CountStore.prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        fa(ctx)?.setAnalyticsCollectionEnabled(on)
    }

    // Onboarding

    fun onboardingStep(ctx: Context, step: String) = log(ctx, "onboarding_step", "step" to step)

    fun onboardingComplete(ctx: Context) = log(ctx, "onboarding_complete")

    /** [type]: usage, accessibility or notifications. */
    fun permissionGranted(ctx: Context, type: String) = log(ctx, "permission_granted", "type" to type)

    // Limits and blocking

    fun limitSet(ctx: Context, value: Int) {
        log(ctx, "limit_set", "value" to value.toLong())
        fa(ctx)?.setUserProperty("limit_bucket", when {
            value <= 20 -> "<=20"
            value <= 50 -> "21-50"
            value <= 100 -> "51-100"
            else -> "100+"
        })
    }

    /** The Settings stepper changes the limit on every tap: log only where it settles. */
    fun limitSetSoon(ctx: Context, value: Int) {
        pendingLimit?.let(main::removeCallbacks)
        val app = ctx.applicationContext
        pendingLimit = Runnable { limitSet(app, value) }.also { main.postDelayed(it, 2_000) }
    }

    /** At most once a day per app and [kind] (reels or time). */
    fun limitReached(ctx: Context, platform: Platform, kind: String) {
        val key = "limit_logged|${platform.name}|$kind"
        val today = LocalDate.now().toString()
        val device = ctx.getSharedPreferences(CountStore.DEVICE_PREFS, Context.MODE_PRIVATE)
        if (device.getString(key, null) == today) return
        device.edit().putString(key, today).apply()
        log(ctx, "limit_reached", "platform" to platform.name, "kind" to kind, "limit" to CountStore.limit(ctx).toLong())
    }

    fun blockShown(ctx: Context, platform: Platform, timeUp: Boolean, reason: BlockReason?) {
        val why = when {
            timeUp -> "time"
            reason is BlockReason.Always -> "always"
            reason is BlockReason.Focus -> "focus"
            else -> "limit"
        }
        log(ctx, "block_shown", "platform" to platform.name, "reason" to why)
    }

    fun pauseShown(ctx: Context, platform: Platform, opens: Int) =
        log(ctx, "pause_shown", "platform" to platform.name, "opens" to opens.toLong())

    /** [result]: close or continue. */
    fun pauseResult(ctx: Context, platform: Platform, result: String) =
        log(ctx, "pause_result", "platform" to platform.name, "result" to result)

    // Account

    fun login(ctx: Context, newUser: Boolean) =
        log(ctx, if (newUser) FirebaseAnalytics.Event.SIGN_UP else FirebaseAnalytics.Event.LOGIN, FirebaseAnalytics.Param.METHOD to "google")

    fun logout(ctx: Context) = log(ctx, "logout")

    fun accountDeleted(ctx: Context) = log(ctx, "account_deleted")

    // Iris over reel apps

    /**
     * One visit with Iris on screen, sent when she leaves. Iris is an overlay, not an app
     * screen, so Firebase doesn't count this time as engagement (a custom engagement_time_msec
     * parameter isn't treated as its own `_et`, checked 11 Oct). "People the counter works for"
     * is therefore Events → iris_session → Total users, not Active users. Flickers under 5 s
     * are skipped; 30 min is the most one visit reports, so a stuck overlay can't inflate it.
     */
    fun irisSession(ctx: Context, platform: Platform, durationMs: Long, reels: Int) {
        if (durationMs < 5_000) return
        val ms = durationMs.coerceAtMost(30 * 60_000L)
        log(ctx, "iris_session",
            "platform" to platform.name,
            "seconds" to ms / 1000,
            "reels" to reels.coerceAtLeast(0).toLong())
    }

    // Daily summary

    /**
     * Yesterday's totals, sent once: from the counting service or Home, whichever runs first
     * that day. The "sent" day is device state, so a restored backup doesn't send it again.
     */
    fun reportYesterday(ctx: Context) {
        if (!enabled(ctx) || fa(ctx) == null) return
        val yesterday = LocalDate.now().minusDays(1)
        val device = ctx.getSharedPreferences(CountStore.DEVICE_PREFS, Context.MODE_PRIVATE)
        if (device.getString(KEY_SUMMARY_DAY, null) == yesterday.toString()) return
        device.edit().putString(KEY_SUMMARY_DAY, yesterday.toString()).apply()
        // Not counting yet yesterday (installed today): nothing to report.
        if (CountStore.firstDay(ctx).isAfter(yesterday)) return

        val params = ArrayList<Pair<String, Any>>()
        params += "day_total" to CountStore.total(ctx, yesterday).toLong()
        params += "limit" to CountStore.limit(ctx).toLong()
        params += "under_limit" to (if (CountStore.limitHitOn(ctx, yesterday)) 0L else 1L)
        params += "minutes" to Stats.minutes(ctx, listOf(yesterday)).first().toLong()
        val streak = CountStore.streak(ctx)
        // The streak counts today; leave it out when today already broke it.
        if (!streak.brokenToday) params += "streak" to (streak.current - 1).coerceAtLeast(0).toLong()
        for (p in Platform.entries) {
            if (CountStore.isTracked(ctx, p)) params += p.name.lowercase() to CountStore.appCount(ctx, yesterday, p).toLong()
        }
        log(ctx, "daily_summary", *params.toTypedArray())
    }

    private fun log(ctx: Context, name: String, vararg params: Pair<String, Any>) {
        val fa = fa(ctx) ?: return
        fa.logEvent(name, Bundle().apply {
            for ((k, v) in params) when (v) {
                is Long -> putLong(k, v)
                is Double -> putDouble(k, v)
                else -> putString(k, v.toString())
            }
        })
    }

    /** Null when the build has no google-services.json (Firebase isn't set up). */
    private fun fa(ctx: Context): FirebaseAnalytics? =
        if (FirebaseApp.getApps(ctx).isEmpty()) null else FirebaseAnalytics.getInstance(ctx)
}
