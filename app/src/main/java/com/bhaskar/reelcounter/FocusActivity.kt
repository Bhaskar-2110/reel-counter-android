package com.bhaskar.reelcounter

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Focus hours (design): each schedule as a card that reads like a sentence (name, days, the
 * times biggest, the apps it locks) with a switch that pauses it without deleting it; then
 * "Add focus hours" and the emergency unlock allowance.
 */
class FocusActivity : PageActivity() {

    override fun title(): CharSequence = getString(R.string.focus_hours)

    override fun build() {
        intro(getString(R.string.focus_intro))
        val now = LocalDateTime.now()
        for (s in Blocking.schedules(this)) {
            val c = card(10f)
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(context, R.style.Rr_Text_CardTitle, s.name).apply { textSize = 16f })
                addView(Ui.text(context, R.style.Rr_Text_Sub,
                    if (s.isActive(now)) getString(R.string.schedule_active_now, MainActivity.timeText(context, s.endMinute)) else daysText(context, s.days)).apply {
                    if (s.isActive(now)) setTextColor(attrColor(R.attr.rrAccent))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(Ui.switch(this, s.enabled, s.name) { on -> Blocking.saveSchedule(this, s.copy(enabled = on)) })
            c.addView(head)
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(Ui.text(context, R.style.Rr_Text_Big, MainActivity.timeText(context, s.startMinute)).apply { textSize = 26f })
                addView(Ui.text(context, R.style.Rr_Text_Muted, getString(R.string.to)).apply {
                    setPadding(Ui.dp(context, 10f), 0, Ui.dp(context, 10f), Ui.dp(context, 3f))
                })
                addView(Ui.text(context, R.style.Rr_Text_Big, MainActivity.timeText(context, s.endMinute)).apply { textSize = 26f })
            }, Ui.gap(this, 10f))
            c.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.locks_apps, appsText(this, s))), Ui.gap(this, 8f))
            c.addView(Ui.link(this, getString(R.string.edit)) { push(FocusEditActivity.intent(this, s.id)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@FocusActivity, 4f) })
        }
        content.addView(MaterialButton(this).also { b ->
            b.setText(R.string.add_focus_hours)
            b.setOnClickListener { push(FocusEditActivity.intent(this, null)) }
            MainActivity.styleSecondary(this, b)
        }, Ui.gap(this, 12f))
        Ui.setRow(list(12f), R.drawable.ic_timer, getString(R.string.emergency_unlock),
            getString(R.string.emergency_unlock_sub, Blocking.UNLOCK_MINUTES),
            listOf(Ui.value(this, getString(R.string.n_left_today, Blocking.unlocksLeft(this)))))
    }

    companion object {
        fun daysText(ctx: Context, days: Int): String = when (days) {
            NoReelSchedule.EVERY_DAY -> ctx.getString(R.string.schedule_every_day)
            NoReelSchedule.WEEKDAYS -> ctx.getString(R.string.mon_to_fri)
            NoReelSchedule.WEEKENDS -> ctx.getString(R.string.schedule_weekends)
            0 -> ctx.getString(R.string.no_days)
            else -> DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }
                .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }

        fun appsText(ctx: Context, s: NoReelSchedule): String {
            if (s.apps == NoReelSchedule.ALL_APPS) return ctx.getString(R.string.all_social_apps)
            val names = Platform.entries.filter { s.locks(it) }.map { it.shortName }
            return if (names.isEmpty()) ctx.getString(R.string.no_apps) else names.joinToString(", ")
        }
    }
}
