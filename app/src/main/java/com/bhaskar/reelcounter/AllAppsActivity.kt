package com.bhaskar.reelcounter

import java.time.LocalDate

/** Every tracked app with today's time, rules and reels, most reels first (Home → All apps). */
class AllAppsActivity : PageActivity() {

    override fun title(): CharSequence = getString(R.string.all_apps)

    override fun build() {
        intro(getString(R.string.all_apps_intro))
        val today = LocalDate.now()
        val list = list()
        Ui.listedPlatforms(this)
            .map { it to CountStore.appCount(this, today, it) }
            .sortedByDescending { it.second }
            .forEach { (platform, reels) ->
                val tags = AppDetailActivity.ruleTags(this, platform)
                val time = Ui.minutes(CountStore.appTime(this, today, platform))
                val sub = buildString {
                    append(time)
                    append(" · ")
                    append(if (tags.isEmpty()) getString(R.string.no_rules) else tags.joinToString(" · "))
                    if (!CountStore.isTracked(this@AllAppsActivity, platform)) append(" · ").append(getString(R.string.not_counted))
                }
                MainActivity.appRow(this, list, platform, sub, time = null, count = reels.toString(), chevron = true) {
                    push(AppDetailActivity.intent(this, platform))
                }
            }
    }
}
