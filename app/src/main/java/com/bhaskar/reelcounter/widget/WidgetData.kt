package com.bhaskar.reelcounter.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.bhaskar.reelcounter.CountStore
import com.bhaskar.reelcounter.MainActivity
import com.bhaskar.reelcounter.Mood
import com.bhaskar.reelcounter.Platform
import com.bhaskar.reelcounter.R
import com.bhaskar.reelcounter.UsageActivity
import java.time.LocalDate

/** Today's numbers for the widgets, read once per refresh. */
data class WidgetData(
    val total: Int,
    val limit: Int,
    /** Nearest limit reached so far, overall or an app's own: 1 = reached. */
    val progress: Float,
    val timeMs: Long,
    val opens: Int,
    val nextPauseSeconds: Int,
    val topApps: List<AppToday>,
    val streak: CountStore.Streak,
) {
    data class AppToday(val name: String, val reels: Int, val timeMs: Long)

    val mood: Mood get() = Mood.forProgress(progress)

    companion object {
        fun load(ctx: Context): WidgetData {
            val today = LocalDate.now()
            val opens = CountStore.opens(ctx, today)
            val apps = Platform.entries
                .map { AppToday(it.shortName, CountStore.appCount(ctx, today, it), CountStore.appTime(ctx, today, it)) }
                .filter { it.reels > 0 || it.timeMs >= 60_000 }
                .sortedWith(compareByDescending<AppToday> { it.timeMs }.thenByDescending { it.reels })
            return WidgetData(
                total = CountStore.total(ctx, today),
                limit = CountStore.limit(ctx),
                progress = Platform.entries.maxOf { CountStore.limitProgress(ctx, it) },
                timeMs = apps.sumOf { it.timeMs },
                opens = opens,
                nextPauseSeconds = CountStore.pauseSecondsFor(opens + 1),
                topApps = apps.take(2),
                streak = CountStore.streak(ctx),
            )
        }

        // Helpers shared by both widgets

        /** Puts the three glance frames of the eyes, in today's mood, into the eye flipper. */
        fun showEyes(ctx: Context, views: RemoteViews, mood: Mood) {
            val frames = WidgetRenderer.eyeFrames(ctx, mood)
            views.setImageViewBitmap(R.id.eyes0, frames[0])
            views.setImageViewBitmap(R.id.eyes1, frames[1])
            views.setImageViewBitmap(R.id.eyes2, frames[2])
        }

        /**
         * Shows the progress view in the colour for how close the limit is (green, amber from 80%,
         * red at the limit), filled to [progress]. Widgets can't recolour a progress view, so each
         * colour is its own view and only one is visible.
         */
        fun showProgress(views: RemoteViews, progress: Float, ok: Int, warn: Int, over: Int) {
            val shown = when {
                progress >= 1f -> over
                progress >= 0.8f -> warn
                else -> ok
            }
            for (id in intArrayOf(ok, warn, over)) {
                views.setViewVisibility(id, if (id == shown) View.VISIBLE else View.GONE)
            }
            views.setProgressBar(shown, 100, (progress.coerceIn(0f, 1f) * 100).toInt(), false)
        }

        /** "🔥 5-day streak", or "💔 0-day streak" on a day a limit was reached. */
        fun streakLine(ctx: Context, streak: CountStore.Streak): String =
            (if (streak.brokenToday) "💔 " else "🔥 ") +
                ctx.resources.getQuantityString(R.plurals.streak_days, streak.current, streak.current)

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun openScreenTime(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, UsageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun formatDuration(ctx: Context, ms: Long): String {
            val minutes = ms / 60_000
            return when {
                minutes < 1 -> ctx.getString(R.string.duration_under_minute)
                minutes < 60 -> ctx.getString(R.string.duration_minutes, minutes.toInt())
                else -> ctx.getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
            }
        }
    }
}
