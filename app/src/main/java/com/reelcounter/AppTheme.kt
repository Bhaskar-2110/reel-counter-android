package com.reelcounter

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatDelegate

/**
 * The four themes from the design: System (follows the phone's dark mode), Dark · Night Garden
 * (the default), Light · Paper and Royal · Purple and gold. Dark and Light are the DayNight
 * resources; Royal is a dark theme with its own colours (Theme.ReelCounter.Royal).
 */
enum class AppTheme(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    ROYAL(AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        /** Sets the app-wide night mode for the saved theme; call before any screen opens. */
        fun applyNightMode(ctx: Context) {
            val mode = CountStore.theme(ctx).nightMode
            if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
        }

        /** True when [ctx] currently draws dark (Dark, Royal, or System at night). */
        fun isDark(ctx: Context): Boolean = when (CountStore.theme(ctx)) {
            DARK, ROYAL -> true
            LIGHT -> false
            SYSTEM -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }
}

private val typedValue = TypedValue()

/** A colour from the current theme, e.g. `attrColor(R.attr.rrText)`. */
fun Context.attrColor(@AttrRes attr: Int): Int = synchronized(typedValue) {
    theme.resolveAttribute(attr, typedValue, true)
    if (typedValue.resourceId != 0) getColor(typedValue.resourceId) else typedValue.data
}

/** The theme's mood colour for each step of the ramp: calm, curious, worried, sad, angry. */
fun Context.moodColor(index: Int): Int = attrColor(
    when (index.coerceIn(0, 4)) {
        0 -> R.attr.rrM0
        1 -> R.attr.rrM1
        2 -> R.attr.rrM2
        3 -> R.attr.rrM3
        else -> R.attr.rrM4
    },
)
