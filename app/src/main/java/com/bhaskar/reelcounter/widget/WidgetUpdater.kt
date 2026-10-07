package com.bhaskar.reelcounter.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.bhaskar.reelcounter.CountStore

/**
 * Refreshes every Reel Counter widget on the home screen. Called when counts, time or limits
 * change (see KeepAliveService), so widgets update as you scroll instead of only every 30 minutes.
 */
object WidgetUpdater {
    private val PROVIDERS = listOf(
        TodayWidget::class.java,
        DashboardWidget::class.java,
    )

    /**
     * Shows or hides the widgets in the launcher's widget list (Settings → Home-screen widgets).
     * They start hidden while they're being redesigned. Hiding them also removes any already on
     * the home screen.
     */
    fun setEnabled(ctx: Context, on: Boolean) {
        CountStore.setWidgetsEnabled(ctx, on)
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (provider in PROVIDERS) {
            ctx.packageManager.setComponentEnabledSetting(ComponentName(ctx, provider), state, PackageManager.DONT_KILL_APP)
        }
        if (on) updateAll(ctx)
    }

    fun updateAll(ctx: Context) {
        val manager = AppWidgetManager.getInstance(ctx) ?: return
        fun ids(widget: Class<*>) = manager.getAppWidgetIds(ComponentName(ctx, widget))

        ids(TodayWidget::class.java).takeIf { it.isNotEmpty() }?.let { TodayWidget.update(ctx, manager, it) }
        ids(DashboardWidget::class.java).takeIf { it.isNotEmpty() }?.let { DashboardWidget.update(ctx, manager, it) }
    }
}
