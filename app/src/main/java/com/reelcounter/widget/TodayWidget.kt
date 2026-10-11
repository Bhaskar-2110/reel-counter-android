package com.reelcounter.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.reelcounter.R

/** Today widget (2×2): Iris in her mood-coloured ring, "18 / 30" and one sentence. */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        update(context, manager, ids)

    companion object {
        fun update(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val data = WidgetData.load(ctx)
            val theme = WidgetTheme.of(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_today).apply {
                setInt(R.id.widgetRoot, "setBackgroundResource", theme.background)
                setImageViewBitmap(R.id.ring, WidgetRenderer.ringWithIris(ctx, data.mood, data.progress, theme))
                setTextViewText(R.id.count, ctx.getString(R.string.widget_count, data.total, data.limit))
                setTextColor(R.id.count, theme.text)
                setTextViewText(R.id.moodText, WidgetData.moodLine(ctx, data))
                setTextColor(R.id.moodText, theme.mood(data.progress))
                setContentDescription(R.id.widgetRoot, ctx.getString(R.string.widget_today_cd, data.total, data.limit))
                setOnClickPendingIntent(R.id.widgetRoot, WidgetData.openApp(ctx))
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
