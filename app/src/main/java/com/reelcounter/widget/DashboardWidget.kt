package com.reelcounter.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.reelcounter.R
import com.reelcounter.Ui

/**
 * Dashboard widget (4×2): Home in miniature. Iris in her ring with today's count and mood on
 * the left; the top three apps and the week's bars on the right.
 */
class DashboardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        update(context, manager, ids)

    companion object {
        private val ROWS = intArrayOf(R.id.appRow0, R.id.appRow1, R.id.appRow2)
        private val ICONS = intArrayOf(R.id.appIcon0, R.id.appIcon1, R.id.appIcon2)
        private val NAMES = intArrayOf(R.id.appName0, R.id.appName1, R.id.appName2)
        private val COUNTS = intArrayOf(R.id.appCount0, R.id.appCount1, R.id.appCount2)

        fun update(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val data = WidgetData.load(ctx)
            val theme = WidgetTheme.of(ctx)
            val mood = theme.mood(data.progress)
            val views = RemoteViews(ctx.packageName, R.layout.widget_dashboard).apply {
                setInt(R.id.widgetRoot, "setBackgroundResource", theme.background)
                setImageViewBitmap(R.id.ring, WidgetRenderer.ringWithIris(ctx, data.mood, data.progress, theme, size = 220))
                setTextViewText(R.id.count, ctx.getString(R.string.widget_count, data.total, data.limit))
                setTextColor(R.id.count, theme.text)
                setTextViewText(R.id.moodText, WidgetData.moodLine(ctx, data) + "\n" +
                    ctx.getString(R.string.widget_in_reels, Ui.minutes(data.timeMs)))
                setTextColor(R.id.moodText, theme.muted)
                setTextColor(R.id.topLabel, theme.muted)

                val top = data.topApps.take(ROWS.size)
                setViewVisibility(R.id.noApps, if (top.isEmpty()) View.VISIBLE else View.GONE)
                setTextColor(R.id.noApps, theme.muted)
                ROWS.indices.forEach { i ->
                    val app = top.getOrNull(i)
                    setViewVisibility(ROWS[i], if (app == null) View.GONE else View.VISIBLE)
                    if (app != null) {
                        WidgetRenderer.appIcon(ctx, app.first)?.let { setImageViewBitmap(ICONS[i], it) }
                        setTextViewText(NAMES[i], app.first.shortName)
                        setTextViewText(COUNTS[i], app.second.toString())
                        setTextColor(NAMES[i], theme.text)
                        setTextColor(COUNTS[i], theme.text)
                    }
                }

                setImageViewBitmap(R.id.weekChart, WidgetRenderer.weekBars(data.week, data.limit, theme, mood))
                setTextViewText(R.id.weekText, ctx.getString(R.string.widget_days_under, data.daysUnder, data.daysCounted))
                setTextColor(R.id.weekText, theme.muted)
                setContentDescription(R.id.widgetRoot, ctx.getString(R.string.widget_today_cd, data.total, data.limit))
                setOnClickPendingIntent(R.id.widgetRoot, WidgetData.openApp(ctx))
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
