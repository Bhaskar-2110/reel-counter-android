package com.bhaskar.reelcounter.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.bhaskar.reelcounter.CountStore
import com.bhaskar.reelcounter.R

/**
 * Today widget (4×2): the eyes and count, how close you are to your limit, time in reel apps
 * today, opens and the next pause, your top two apps, and your streak.
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        update(context, manager, ids)

    companion object {
        fun update(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val data = WidgetData.load(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_today).apply {
                WidgetData.showEyes(ctx, this, data.mood)
                setTextViewText(R.id.count, data.total.toString())

                setTextViewText(
                    R.id.limitText,
                    if (data.progress >= 1f) ctx.getString(R.string.widget_limit_reached)
                    else ctx.getString(R.string.widget_of_limit, data.total, data.limit),
                )
                WidgetData.showProgress(this, data.progress, R.id.barOk, R.id.barWarn, R.id.barOver)

                setTextViewText(R.id.timeText, ctx.getString(R.string.widget_time_today, WidgetData.formatDuration(ctx, data.timeMs)))
                setTextViewText(
                    R.id.opensText,
                    if (data.opens < CountStore.FREE_OPENS) {
                        ctx.getString(R.string.widget_opens_free, data.opens, CountStore.FREE_OPENS - data.opens)
                    } else {
                        ctx.getString(R.string.widget_opens_paused, data.opens, data.nextPauseSeconds)
                    },
                )
                setTextViewText(
                    R.id.topApps,
                    data.topApps.joinToString("\n") {
                        ctx.getString(R.string.widget_top_app, it.name, it.reels, WidgetData.formatDuration(ctx, it.timeMs))
                    },
                )
                setTextViewText(R.id.streakText, WidgetData.streakLine(ctx, data.streak))

                setOnClickPendingIntent(R.id.widgetRoot, WidgetData.openApp(ctx))
                setOnClickPendingIntent(R.id.timeText, WidgetData.openScreenTime(ctx))
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
