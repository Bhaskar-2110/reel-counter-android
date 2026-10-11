package com.bhaskar.reelcounter.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import com.bhaskar.reelcounter.AppTheme
import com.bhaskar.reelcounter.CountStore
import com.bhaskar.reelcounter.MainActivity
import com.bhaskar.reelcounter.Mood
import com.bhaskar.reelcounter.MoodColors
import com.bhaskar.reelcounter.Platform
import com.bhaskar.reelcounter.R
import com.bhaskar.reelcounter.Stats
import java.time.LocalDate

/** Today's numbers for the widgets, read once per refresh. */
data class WidgetData(
    val total: Int,
    val limit: Int,
    /** Share of the overall limit used today: 1 = reached. */
    val progress: Float,
    val timeMs: Long,
    /** Tracked apps with reels today, most first. */
    val topApps: List<Pair<Platform, Int>>,
    val week: List<Int>,
    val daysUnder: Int,
    val daysCounted: Int,
) {
    /** Iris's mood, from the same ramp as Home (calm → angry). */
    val mood: Mood get() = when (MoodColors.index(progress)) {
        0 -> Mood.HAPPY
        1 -> Mood.CURIOUS
        2 -> Mood.WORRIED
        3 -> Mood.SAD
        else -> Mood.ANGRY
    }

    companion object {
        fun load(ctx: Context): WidgetData {
            val today = LocalDate.now()
            val total = CountStore.total(ctx, today)
            val limit = CountStore.limit(ctx)
            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val counted = Stats.counted(ctx, days)
            return WidgetData(
                total = total,
                limit = limit,
                progress = total / limit.toFloat(),
                timeMs = Stats.timeTodayMs(ctx),
                topApps = Platform.entries
                    .filter { CountStore.isTracked(ctx, it) }
                    .map { it to CountStore.appCount(ctx, today, it) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second },
                week = Stats.totals(ctx, days),
                daysUnder = Stats.daysUnder(Stats.totals(ctx, counted), limit),
                daysCounted = counted.size.coerceAtLeast(1),
            )
        }

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** "Iris is curious · 12 left", or "Limit reached" (shorter than Home's sentence). */
        fun moodLine(ctx: Context, d: WidgetData): String =
            if (d.total >= d.limit) ctx.getString(R.string.home_limit_reached).trimEnd('.')
            else ctx.getString(R.string.widget_mood_line, ctx.getString(MoodColors.wordFor(d.progress)), d.limit - d.total)
    }
}

/**
 * The widget colours for the app's theme: Dark (Night Garden), Light (Paper) or Royal. System
 * follows the home screen's own dark mode.
 */
class WidgetTheme(
    val background: Int,
    val text: Int,
    val muted: Int,
    val track: Int,
    val moods: IntArray,
) {
    fun mood(progress: Float) = moods[MoodColors.index(progress)]

    companion object {
        private val DARK = WidgetTheme(
            R.drawable.widget_bg, 0xFFF2F3F8.toInt(), 0xFFA3A8C3.toInt(), 0xFF1E2235.toInt(),
            intArrayOf(0xFF4FE3B0.toInt(), 0xFF5CC8FF.toInt(), 0xFFFFC24D.toInt(), 0xFFFF8A65.toInt(), 0xFFFF4D5E.toInt()),
        )
        private val LIGHT = WidgetTheme(
            R.drawable.widget_bg_light, 0xFF14162B.toInt(), 0xFF4A4E68.toInt(), 0xFFE4E1D8.toInt(),
            intArrayOf(0xFF0B7A59.toInt(), 0xFF086BA3.toInt(), 0xFF9A6500.toInt(), 0xFFC24A25.toInt(), 0xFFC8283A.toInt()),
        )
        private val ROYAL = WidgetTheme(
            R.drawable.widget_bg_royal, 0xFFFFF7E3.toInt(), 0xFFEADCB8.toInt(), 0x21FFFFFF,
            intArrayOf(0xFF3DDC97.toInt(), 0xFF7FB2FF.toInt(), 0xFFFFB547.toInt(), 0xFFFF8A5C.toInt(), 0xFFFF4F79.toInt()),
        )

        fun of(ctx: Context): WidgetTheme = when (CountStore.theme(ctx)) {
            AppTheme.DARK -> DARK
            AppTheme.LIGHT -> LIGHT
            AppTheme.ROYAL -> ROYAL
            AppTheme.SYSTEM -> if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_NO
            ) LIGHT else DARK
        }
    }
}
