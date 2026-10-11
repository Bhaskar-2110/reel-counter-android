package com.bhaskar.reelcounter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything about one app on one page (design "App page", like Digital Wellbeing's app page):
 * today's reels and minutes against its limits, then every rule for the app, strongest first:
 * block, reel limit, daily timer, focus hours. Switches flip in place; limits open a sheet.
 */
class AppDetailActivity : PageActivity() {

    private lateinit var platform: Platform

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val requested = intent.getStringExtra(EXTRA_PLATFORM)?.let { name -> Platform.entries.firstOrNull { it.name == name } }
        platform = requested ?: Platform.INSTAGRAM
        super.onCreate(savedInstanceState)
        if (requested == null) finish()
    }

    override fun title(): CharSequence = platform.shortName

    override fun build() {
        val today = LocalDate.now()
        val reels = CountStore.appCount(this, today, platform)
        val timeMs = CountStore.appTime(this, today, platform)
        val blocked = Blocking.blockAlways(this, platform)
        val reelLimit = CountStore.appLimit(this, platform)
        val timer = CountStore.appTimeLimitMinutes(this, platform)
        val tags = ruleTags(this, platform)

        // Header: icon, name, rules on
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val icon = Ui.appIcon(context, platform)
            if (icon != null) {
                addView(ImageView(context).apply { setImageDrawable(icon) },
                    LinearLayout.LayoutParams(Ui.dp(context, 52f), Ui.dp(context, 52f)))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (icon != null) Ui.dp(context, 14f) else Ui.dp(context, 4f), 0, 0, 0)
                addView(Ui.text(context, R.style.Rr_Text_ScreenTitle, platform.shortName).apply { fontVariationSettings = "'wght' 800" })
                addView(Ui.text(context, R.style.Rr_Text_Muted,
                    if (tags.isEmpty()) getString(R.string.no_rules_yet) else resources.getQuantityString(R.plurals.rules_on, tags.size, tags.size)))
            })
        }, Ui.gap(this, 4f))

        // Two meters: reels against the reel limit, minutes against the timer.
        val stats = card(12f)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(meterCell(
            getString(R.string.reels_today_label_cap),
            reels.toString(),
            if (!blocked && reelLimit > 0) " / $reelLimit" else "",
            when {
                blocked -> getString(R.string.blocked_all_day)
                reelLimit > 0 -> null
                else -> getString(R.string.no_reel_limit)
            },
            if (reelLimit > 0) reels / reelLimit.toFloat() else 0f,
            MoodColors.forProgress(this, CountStore.limitProgress(this, platform)),
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@AppDetailActivity, 14f) })
        grid.addView(meterCell(
            getString(R.string.time_in_app),
            Ui.minutes(timeMs),
            if (timer > 0) " / ${timer}m" else "",
            if (timer > 0) null else getString(R.string.no_timer),
            if (timer > 0) timeMs / (timer * 60_000f) else 0f,
            attrColor(R.attr.rrAccent),
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(grid)

        // Rules, strongest first
        section(getString(R.string.rules_for, platform.shortName))
        val rules = list()
        val whole = platform.isWholeApp
        Ui.switchRow(
            rules, R.drawable.ic_block,
            if (whole) getString(R.string.block_whole_app) else getString(R.string.block_reels_of, platform.reelWord(this)),
            if (whole) getString(R.string.block_whole_app_sub) else getString(R.string.block_reels_sub, platform.reelWord(this)),
            blocked,
        ) { Blocking.setBlockAlways(this, platform, it) }
        val limitRow = Ui.setRow(
            rules, R.drawable.ic_limit, getString(R.string.reel_limit),
            if (blocked) getString(R.string.reel_limit_not_needed) else getString(R.string.reel_limit_sub),
            listOf(Ui.value(this, if (reelLimit > 0) resources.getQuantityString(R.plurals.n_reels, reelLimit, reelLimit) else getString(R.string.none)), Ui.chevron(this)),
        ) { reelLimitSheet(this, platform) }
        if (blocked) {
            limitRow.alpha = 0.45f
            limitRow.isEnabled = false
        }
        Ui.setRow(
            rules, R.drawable.ic_timer, getString(R.string.daily_timer), getString(R.string.daily_timer_sub),
            listOf(Ui.value(this, if (timer > 0) getString(R.string.n_min, timer) else getString(R.string.off)), Ui.chevron(this)),
        ) { timerSheet(this, platform) }
        val focusNames = Blocking.schedules(this).filter { it.enabled && it.locks(platform) }.map { it.name }
        Ui.setRow(
            rules, R.drawable.ic_focus, getString(R.string.focus_hours),
            if (focusNames.isEmpty()) getString(R.string.not_in_schedule) else getString(R.string.locked_during, Ui.joinNames(this, focusNames)),
            listOf(Ui.chevron(this)),
        ) { push(Intent(this, FocusActivity::class.java)) }

        section(R.string.more)
        val more = list()
        if (!whole) {
            Ui.switchRow(more, R.drawable.ic_user, getString(R.string.detail_allow_first), getString(R.string.detail_allow_first_hint),
                Blocking.allowFirst(this, platform)) { Blocking.setAllowFirst(this, platform, it) }
        }
        Ui.switchRow(more, R.drawable.ic_eye, getString(R.string.detail_count_app), getString(R.string.detail_count_app_hint),
            CountStore.isTracked(this, platform)) { CountStore.setTracked(this, platform, it) }

        // This week
        val week = card(12f)
        week.addView(Ui.text(this, R.style.Rr_Text_CardTitle, getString(R.string.this_week)))
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val counts = days.map { CountStore.appCount(this, it, platform) }
        val mood = MoodColors.forProgress(this, CountStore.limitProgress(this, platform))
        val sky = (moodColor(1) and 0x00FFFFFF) or (0xBF shl 24)
        week.addView(WeekBarsView(this).apply {
            setData(
                counts.toIntArray(),
                days.mapIndexed { i, d -> if (i == 6) getString(R.string.today) else d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) },
                IntArray(7) { if (it == 6) mood else sky },
                reelLimit,
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 100f)).apply { topMargin = Ui.dp(this@AppDetailActivity, 10f) })
        val opens = CountStore.appOpens(this, today, platform)
        val counted = Stats.counted(this, days).size.coerceAtLeast(1)
        week.addView(Ui.text(this, R.style.Rr_Text_Muted,
            getString(R.string.app_week_line, resources.getQuantityString(R.plurals.opens_today, opens, opens), counts.sum() / counted)), Ui.gap(this, 8f))

        suggestion(days.dropLast(1).filter { !it.isBefore(CountStore.firstDay(this)) })
    }

    /** One of the two meters at the top: label, big number, and a bar or a note. */
    private fun meterCell(label: String, big: String, small: String, note: String?, share: Float, color: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, R.style.Rr_Text_Muted, label))
            addView(TextView(context, null, 0, R.style.Rr_Text_Big).apply {
                textSize = 28f
                text = android.text.SpannableStringBuilder(big).apply {
                    val s = length
                    append(small)
                    setSpan(android.text.style.AbsoluteSizeSpan(14, true), s, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(attrColor(R.attr.rrText3)), s, length, 0)
                }
            }, Ui.gap(context, 6f))
            if (note != null) {
                addView(Ui.text(context, R.style.Rr_Text_Sub, note), Ui.gap(context, 6f))
            } else {
                // Turns coral when close to the limit.
                addView(Ui.meter(context, share, if (share >= 0.85f) moodColor(3) else color), Ui.gap(context, 8f).apply {
                    height = Ui.dp(context, 6f)
                })
            }
        }

    /**
     * Suggested limits from real use: about three quarters of the average on past days (today
     * isn't over yet), rounded to 5.
     */
    private fun suggestion(pastDays: List<LocalDate>) {
        if (pastDays.size < 2) return
        val reelsAvg = pastDays.map { CountStore.appCount(this, it, platform) }.average()
        val minutesAvg = pastDays.map { CountStore.appTime(this, it, platform) / 60_000.0 }.average()
        if (reelsAvg < 4 && minutesAvg < 5) return
        val reels = round5(reelsAvg * 0.75)
        val minutes = round5(minutesAvg * 0.75)
        val c = card(10f)
        c.addView(Ui.text(this, R.style.Rr_Text_CardTitle, getString(R.string.suggested_limits)))
        c.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.detail_suggestion, reelsAvg.roundToInt(), minutesAvg.roundToInt(), reels, minutes)), Ui.gap(this, 6f))
        c.addView(Ui.link(this, getString(R.string.detail_use_suggestion)) {
            CountStore.setAppLimit(this, platform, reels)
            CountStore.setAppTimeLimitMinutes(this, platform, minutes)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@AppDetailActivity, 4f) })
    }

    private fun round5(v: Double) = ((v / 5).roundToInt() * 5).coerceAtLeast(5)

    companion object {
        private const val EXTRA_PLATFORM = "platform"

        fun intent(ctx: Context, platform: Platform) =
            Intent(ctx, AppDetailActivity::class.java).putExtra(EXTRA_PLATFORM, platform.name)

        /** Tags for an app's rules, like "Reels blocked", "15 reels", "30 min". */
        fun ruleTags(ctx: Context, platform: Platform): List<String> {
            val tags = ArrayList<String>()
            if (Blocking.blockAlways(ctx, platform)) {
                tags += ctx.getString(if (platform.isWholeApp) R.string.tag_app_blocked else R.string.tag_reels_blocked, platform.reelWord(ctx))
            } else {
                CountStore.appLimit(ctx, platform).takeIf { it > 0 }?.let { tags += ctx.resources.getQuantityString(R.plurals.n_reels, it, it) }
            }
            CountStore.appTimeLimitMinutes(ctx, platform).takeIf { it > 0 }?.let { tags += ctx.getString(R.string.n_min, it) }
            return tags
        }

        /** "Limit for Instagram": quick picks, None to 60, or a typed number. */
        fun reelLimitSheet(activity: Activity, platform: Platform) {
            val ctx = activity
            val current = CountStore.appLimit(ctx, platform)
            val picks = listOf(0, 5, 10, 15, 20, 30, 45, 60)
            lateinit var sheet: com.google.android.material.bottomsheet.BottomSheetDialog
            sheet = Ui.sheet(activity, ctx.getString(R.string.limit_for, platform.shortName)) { box ->
                val count = CountStore.appCount(ctx, LocalDate.now(), platform)
                box.addView(Ui.text(ctx, R.style.Rr_Text_Muted,
                    ctx.getString(R.string.limit_sheet_sub, count, platform.reelWord(ctx))), Ui.gap(ctx, 6f))
                box.addView(Ui.opts(ctx, picks, current, 4,
                    { if (it == 0) ctx.getString(R.string.none) else it.toString() }) {
                    CountStore.setAppLimit(ctx, platform, it)
                }, Ui.gap(ctx, 14f))
                val own = if (current > 0 && current !in picks) ctx.getString(R.string.limit_own_n, current) else ctx.getString(R.string.limit_type)
                box.addView(Ui.link(ctx, own) {
                    Ui.askNumber(ctx, ctx.getString(R.string.limit_for, platform.shortName), CountStore.appLimit(ctx, platform), 1, 500) {
                        CountStore.setAppLimit(ctx, platform, it)
                        sheet.dismiss()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ui.dp(ctx, 12f)
                    gravity = Gravity.CENTER_HORIZONTAL
                })
            }
        }

        /** "Daily timer for Instagram": quick picks, Off to 3 hours. */
        fun timerSheet(activity: Activity, platform: Platform) {
            val ctx = activity
            val current = CountStore.appTimeLimitMinutes(ctx, platform)
            Ui.sheet(activity, ctx.getString(R.string.timer_for, platform.shortName)) { box ->
                val used = (CountStore.appTime(ctx, LocalDate.now(), platform) / 60_000).toInt()
                box.addView(Ui.text(ctx, R.style.Rr_Text_Muted,
                    ctx.getString(R.string.timer_sheet_sub, used, platform.shortName)), Ui.gap(ctx, 6f))
                box.addView(Ui.opts(ctx, listOf(0, 15, 30, 45, 60, 90, 120, 180), current, 4,
                    { if (it == 0) ctx.getString(R.string.off) else if (it < 60) "${it}m" else if (it % 60 == 0) "${it / 60}h" else "%.1fh".format(it / 60f) }) {
                    CountStore.setAppTimeLimitMinutes(ctx, platform, it)
                }, Ui.gap(ctx, 14f))
            }
        }
    }
}

/** "Reels", "Shorts", "Spotlight", or "the whole app" for apps that are only short videos. */
fun Platform.reelWord(ctx: Context): String = ctx.getString(
    when (this) {
        Platform.INSTAGRAM -> R.string.word_reels
        Platform.YOUTUBE -> R.string.word_shorts
        Platform.SNAPCHAT -> R.string.word_spotlight
        else -> R.string.word_whole_app
    },
)
