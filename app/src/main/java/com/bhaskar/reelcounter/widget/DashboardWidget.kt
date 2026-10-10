package com.bhaskar.reelcounter.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.bhaskar.reelcounter.CountStore
import com.bhaskar.reelcounter.Platform
import com.bhaskar.reelcounter.R
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Dashboard widget (4×4): today's summary, every app's reels and time against its own limits,
 * and the last 7 days. The eyes are a single still picture here, to leave room for the chart.
 */
class DashboardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        update(context, manager, ids)

    companion object {
        private val ROWS = intArrayOf(R.id.appRow0, R.id.appRow1, R.id.appRow2, R.id.appRow3, R.id.appRow4, R.id.appRow5)
        private val ICONS = intArrayOf(R.id.appIcon0, R.id.appIcon1, R.id.appIcon2, R.id.appIcon3, R.id.appIcon4, R.id.appIcon5)
        private val NAMES = intArrayOf(R.id.appName0, R.id.appName1, R.id.appName2, R.id.appName3, R.id.appName4, R.id.appName5)
        private val STATS = intArrayOf(R.id.appStat0, R.id.appStat1, R.id.appStat2, R.id.appStat3, R.id.appStat4, R.id.appStat5)

        fun update(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val data = WidgetData.load(ctx)
            val today = LocalDate.now()
            val views = RemoteViews(ctx.packageName, R.layout.widget_dashboard).apply {
                setImageViewBitmap(R.id.eyesStill, WidgetRenderer.stillEyes(ctx, data.mood, width = 200, height = 100))
                setTextViewText(
                    R.id.limitText,
                    if (data.progress >= 1f) ctx.getString(R.string.widget_limit_reached)
                    else ctx.getString(R.string.widget_of_limit, data.total, data.limit),
                )
                WidgetData.showProgress(this, data.progress, R.id.barOk, R.id.barWarn, R.id.barOver)
                WidgetData.showNudge(ctx, this, data)
                setTextViewText(
                    R.id.summaryText,
                    ctx.getString(R.string.widget_summary, WidgetData.formatDuration(ctx, data.timeMs), data.opens),
                )
                setTextViewText(R.id.streakText, WidgetData.streakLine(ctx, data.streak))

                // One row per app that's installed or was used today.
                var row = 0
                for (platform in Platform.entries) {
                    if (row == ROWS.size) break
                    val icon = WidgetRenderer.appIcon(ctx, platform, size = 48)
                    val reels = CountStore.appCount(ctx, today, platform)
                    val timeMs = CountStore.appTime(ctx, today, platform)
                    if (icon == null && reels == 0 && timeMs < 60_000) continue
                    setViewVisibility(ROWS[row], View.VISIBLE)
                    if (icon != null) setImageViewBitmap(ICONS[row], icon)
                    setTextViewText(NAMES[row], platform.shortName)
                    setTextViewText(STATS[row], statLine(ctx, platform, reels, timeMs))
                    val share = maxOf(
                        CountStore.appLimit(ctx, platform).let { if (it > 0) reels / it.toFloat() else 0f },
                        CountStore.appTimeLimitMinutes(ctx, platform).let { if (it > 0) timeMs / (it * 60_000f) else 0f },
                    )
                    setTextColor(
                        STATS[row],
                        ctx.getColor(
                            when {
                                share >= 1f -> R.color.widget_over
                                share >= 0.8f -> R.color.widget_warn
                                else -> R.color.widget_muted
                            },
                        ),
                    )
                    row++
                }
                for (unused in row until ROWS.size) setViewVisibility(ROWS[unused], View.GONE)

                val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
                val labels = days.mapIndexed { i, d ->
                    if (i == days.lastIndex) ctx.getString(R.string.today)
                    else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }
                setImageViewBitmap(
                    R.id.weekChart,
                    WidgetRenderer.weekChart(ctx, days.map { CountStore.total(ctx, it) }, labels, data.limit),
                )

                setOnClickPendingIntent(R.id.widgetRoot, WidgetData.openApp(ctx))
                setOnClickPendingIntent(R.id.summaryText, WidgetData.openScreenTime(ctx))
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        /** "12/20 reels · 40m/30m": reels and time, each with the app's own limit when it has one. */
        private fun statLine(ctx: Context, platform: Platform, reels: Int, timeMs: Long): String {
            val reelLimit = CountStore.appLimit(ctx, platform)
            val timeLimit = CountStore.appTimeLimitMinutes(ctx, platform)
            val reelPart = if (reelLimit > 0) "$reels/$reelLimit" else reels.toString()
            val timePart = WidgetData.formatDuration(ctx, timeMs) +
                if (timeLimit > 0) " / " + ctx.getString(R.string.duration_minutes, timeLimit) else ""
            return ctx.getString(R.string.widget_app_stat, reelPart, timePart)
        }
    }
}
