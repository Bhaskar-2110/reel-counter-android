package com.reelcounter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import com.reelcounter.databinding.ActivityMainBinding
import com.reelcounter.widget.WidgetUpdater
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home and Progress, the app's two tabs (design/Rokoreels App Flow.html).
 *
 * Home is one screen with no scrolling: Settings behind the gear (with a dot when something
 * needs fixing), a focus pill while focus hours run, the wordmark; Iris in the mood-coloured
 * ring; reels and minutes today; one sentence; and the top three apps.
 *
 * Progress answers five questions: am I under my limit, am I improving, is my streak alive,
 * when do I slip, and which app is the problem. Week or month, switched by a segmented control.
 */
class MainActivity : BaseActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private var tab = Tab.HOME
    private var month = false
    private var shownTotal = -1
    private var firstRender = true

    private enum class Tab { HOME, PROGRESS }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!CountStore.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            super.finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tab = savedInstanceState?.getString(KEY_TAB)?.let(Tab::valueOf) ?: Tab.HOME
        month = savedInstanceState?.getBoolean(KEY_MONTH) ?: false

        binding.wordmark.text = wordmark(this)
        binding.topApps.clipToOutline = true
        // Iris stretches and wobbles past her view's edges; let her draw there.
        (binding.root as android.view.ViewGroup).clipChildren = false
        binding.navHome.setOnClickListener { showTab(Tab.HOME) }
        binding.navProgress.setOnClickListener { showTab(Tab.PROGRESS) }
        binding.settingsButton.setOnClickListener { push(Intent(this, SettingsActivity::class.java)) }
        binding.allAppsLink.setOnClickListener { push(Intent(this, AllAppsActivity::class.java)) }
        binding.focusPill.setOnClickListener { push(Intent(this, FocusActivity::class.java)) }
        binding.progressSeg.setItems(
            listOf(getString(R.string.this_week), getString(R.string.this_month)), if (month) 1 else 0,
        ) {
            month = it == 1
            renderProgress(animate = true)
        }
        showTab(tab, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
        outState.putBoolean(KEY_MONTH, month)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        CountStore.prefs(this).registerOnSharedPreferenceChangeListener(this)
        render()
        if (isServiceEnabled(this) && CountStore.ongoingNotification(this)) KeepAliveService.start(this)
        WidgetUpdater.updateAll(this)
    }

    override fun onPause() {
        if (::binding.isInitialized) CountStore.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        // Counting writes often (time every 30 s); only what's on screen needs redrawing.
        renderHome()
        if (tab == Tab.PROGRESS && key?.startsWith("total|") == true) renderProgress(animate = false)
    }

    private fun render() {
        renderHome()
        renderProgress(animate = firstRender && tab == Tab.PROGRESS)
        firstRender = false
    }

    // Tabs

    private fun showTab(next: Tab, animate: Boolean = true) {
        val changed = next != tab
        tab = next
        val incoming = if (next == Tab.HOME) binding.pageHome else binding.pageProgress
        val outgoing = if (next == Tab.HOME) binding.pageProgress else binding.pageHome
        setNav(binding.navHome, binding.navHomePill, binding.navHomeIcon, binding.navHomeText, next == Tab.HOME, animate)
        setNav(binding.navProgress, binding.navProgressPill, binding.navProgressIcon, binding.navProgressText, next == Tab.PROGRESS, animate)
        if (!animate || !changed || CountStore.reduceMotion(this)) {
            incoming.isVisible = true
            incoming.alpha = 1f
            outgoing.isVisible = false
            return
        }
        // Fade through: the old page fades out quickly, the new one fades and rises in.
        outgoing.animate().cancel()
        incoming.animate().cancel()
        outgoing.animate().alpha(0f).setDuration(90).withEndAction {
            outgoing.isVisible = false
            outgoing.alpha = 1f
        }.start()
        incoming.alpha = 0f
        incoming.translationY = Ui.dp(this, 10f).toFloat()
        incoming.isVisible = true
        incoming.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(240).setInterpolator(Ui.EASE).start()
        if (next == Tab.PROGRESS) {
            binding.pageProgress.scrollTo(0, 0)
            Ui.riseChildren(binding.progressContent)
        } else {
            Ui.rise(listOf(binding.stats, binding.moodText, binding.appsSection))
        }
    }

    /** The design's nav: the active tab's icon sits in an accent pill that grows in. */
    private fun setNav(item: View, pill: View, icon: ImageView, label: TextView, on: Boolean, animate: Boolean) {
        item.isSelected = on
        icon.isSelected = on
        label.isSelected = on
        icon.imageTintList = getColorStateList(R.color.nav_icon)
        item.contentDescription = label.text
        // Only the pill's fill fades; the icon inside it must stay visible on the other tab.
        val fill = pill.background.mutate()
        pill.background = fill
        val target = if (on) 255 else 0
        if (!animate || CountStore.reduceMotion(this)) {
            fill.alpha = target
            icon.scaleX = 1f
            icon.scaleY = 1f
            return
        }
        android.animation.ValueAnimator.ofInt(fill.alpha, target).apply {
            duration = if (on) 260 else 150
            addUpdateListener { fill.alpha = it.animatedValue as Int }
            start()
        }
        if (on) {
            icon.scaleX = 0.7f
            icon.scaleY = 0.7f
            icon.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
    }

    // Home

    private fun renderHome() {
        val today = LocalDate.now()
        val total = CountStore.total(this, today)
        val limit = CountStore.limit(this)
        val progress = total / limit.toFloat()
        val mi = MoodColors.index(progress)
        val mood = moodColor(mi)

        binding.ring.set(progress, mood)
        if (shownTotal in 0 until total) binding.mainEyes.onReel(progress) else binding.mainEyes.setProgress(progress)
        if (firstRender && !CountStore.reduceMotion(this)) popIris()
        if (shownTotal >= 0 && shownTotal != total) Ui.bump(binding.todayCount)
        shownTotal = total

        // "18 / 30": the number gets heavier as Iris worries.
        val weight = MoodColors.heroWeight(progress)
        binding.todayCount.fontVariationSettings = "'wght' $weight"
        binding.todayTime.fontVariationSettings = "'wght' $weight"
        binding.todayCount.text = SpannableStringBuilder(total.toString()).apply {
            val start = length
            append(" / $limit")
            setSpan(AbsoluteSizeSpan(16, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(attrColor(R.attr.rrText3)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.todayTime.text = Ui.minutes(Stats.timeTodayMs(this))

        binding.moodText.setOnClickListener(null)
        binding.moodText.isClickable = false
        binding.moodText.text = when {
            !isServiceEnabled(this) -> {
                binding.moodText.setOnClickListener { push(Intent(this, PermissionsActivity::class.java)) }
                bold(getString(R.string.home_counting_off), moodColor(2), getString(R.string.home_counting_off_fix))
            }
            total < limit -> {
                val left = limit - total
                SpannableStringBuilder(getString(R.string.home_iris_is)).apply {
                    append(" ")
                    val s = length
                    append(getString(MoodColors.wordFor(progress)))
                    setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(mood), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(". ")
                    append(resources.getQuantityString(R.plurals.reels_left_today, left, left))
                }
            }
            CountStore.blockAtLimit(this) -> bold(getString(R.string.home_limit_reached), moodColor(4), getString(R.string.home_closed_till_midnight))
            else -> bold(getString(R.string.home_limit_reached), moodColor(4), resources.getQuantityString(R.plurals.reels_past_limit, total - limit, total - limit))
        }

        binding.attentionDot.isVisible = needsAttention(this)
        val focus = Blocking.activeSchedule(this)
        binding.focusPill.isVisible = focus != null
        if (focus != null) {
            binding.focusPill.text = getString(R.string.focus_till, focus.name, timeText(this, focus.endMinute))
            val dot = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(attrColor(R.attr.rrAccent))
                setSize(Ui.dp(this@MainActivity, 7f), Ui.dp(this@MainActivity, 7f))
            }
            binding.focusPill.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        }

        renderTopApps(today)
    }

    private fun bold(head: String, color: Int, tail: String) = SpannableStringBuilder(head).apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" ")
        append(tail)
    }

    /** Iris pops in when Home first opens (design facePop). */
    private fun popIris() {
        val v = binding.mainEyes
        v.scaleX = 0.6f
        v.scaleY = 0.6f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(180).setDuration(550)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()
    }

    /** The three apps with the most reels today; apps with none are left for "All apps". */
    private fun renderTopApps(today: LocalDate) {
        val list = binding.topApps
        list.removeAllViews()
        val top = Platform.entries
            .filter { CountStore.isTracked(this, it) }
            .map { it to CountStore.appCount(this, today, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
        if (top.isEmpty()) {
            list.addView(TextView(this, null, 0, R.style.Rr_Text_Body).apply {
                setText(R.string.home_no_reels)
                textSize = 13f
                setPadding(Ui.dp(context, 14f), Ui.dp(context, 12f), Ui.dp(context, 14f), Ui.dp(context, 12f))
            })
            return
        }
        for ((platform, reels) in top) {
            appRow(this, list, platform, sub = null, time = Ui.minutes(CountStore.appTime(this, today, platform)), count = reels.toString()) {
                push(AppDetailActivity.intent(this, platform))
            }
        }
    }

    // Progress

    private fun renderProgress(animate: Boolean) {
        val box = binding.progressContent
        box.removeAllViews()
        val today = LocalDate.now()
        val limit = CountStore.limit(this)
        if (month) monthCards(box, today, limit) else weekCards(box, today, limit)
        streakCard(box, today)
        hoursCard(box, today)
        appsCard(box, today)
        box.addView(MaterialButton(this).also { b ->
            b.setText(R.string.phone_screen_time)
            b.setOnClickListener { push(Intent(this, UsageActivity::class.java)) }
            styleSecondary(this, b)
        }, Ui.gap(this, 12f))
        if (animate) Ui.riseChildren(box)
    }

    private fun card(parent: LinearLayout, title: Int? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_bg)
            val p = Ui.dp(context, 14f)
            setPadding(p, p, p, p)
        }
        if (title != null) c.addView(Ui.text(this, R.style.Rr_Text_CardTitle, getString(title)))
        parent.addView(c, Ui.gap(this, 12f))
        return c
    }

    /** "4 of 7 days under your limit" with a big number first. */
    private fun kpi(parent: LinearLayout, big: String, label: String) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.BOTTOM
            addView(Ui.text(context, R.style.Rr_Text_Big, big).apply { maxLines = 1 })
            addView(Ui.text(context, R.style.Rr_Text_Muted, label).apply {
                setPadding(Ui.dp(context, 8f), 0, 0, Ui.dp(context, 3f))
            })
        })
    }

    private fun weekCards(box: LinearLayout, today: LocalDate, limit: Int) {
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val counted = Stats.counted(this, days)
        val totals = Stats.totals(this, days)
        val countedTotals = Stats.totals(this, counted)
        val c = card(box)
        kpi(c, getString(R.string.n_of_m, Stats.daysUnder(countedTotals, limit), counted.size.coerceAtLeast(1)), getString(R.string.days_under_limit))
        val mood = moodColor(MoodColors.index(CountStore.total(this, today) / limit.toFloat()))
        val sky = (moodColor(1) and 0x00FFFFFF) or (0xBF shl 24)
        val labels = days.mapIndexed { i, d ->
            if (i == days.lastIndex) getString(R.string.today) else d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
        val colors = IntArray(7) { i ->
            when {
                totals[i] >= limit -> moodColor(4)
                i == 6 -> mood
                else -> sky
            }
        }
        c.addView(WeekBarsView(this).apply { setData(totals.toIntArray(), labels, colors, limit) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 132f)).apply { topMargin = Ui.dp(this@MainActivity, 12f) })

        val previous = (13 downTo 7).map { today.minusDays(it.toLong()) }
        compareCard(box, R.string.compared_last_week, counted, Stats.counted(this, previous))
    }

    private fun monthCards(box: LinearLayout, today: LocalDate, limit: Int) {
        val ym = YearMonth.from(today)
        val first = ym.atDay(1)
        val firstDay = CountStore.firstDay(this)
        val sofar = Stats.days(first, today)
        val counted = sofar.filter { !it.isBefore(firstDay) }
        val c = card(box)
        val monthName = ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        kpi(c, getString(R.string.n_of_m, Stats.daysUnder(Stats.totals(this, counted), limit), counted.size.coerceAtLeast(1)),
            getString(R.string.month_days_under, monthName))
        val values = (1..ym.lengthOfMonth()).map { d ->
            val day = ym.atDay(d)
            if (day.isAfter(today) || day.isBefore(firstDay)) null else CountStore.total(this, day)
        }
        c.addView(CalendarView(this).apply {
            setData(values, first.dayOfWeek.value - 1, today.dayOfMonth - 1, limit)
        }, Ui.gap(this, 12f))
        c.addView(legend(), Ui.gap(this, 10f))

        val prevMonth = ym.minusMonths(1)
        val prev = Stats.days(prevMonth.atDay(1), prevMonth.atEndOfMonth())
        compareCard(box, getString(R.string.compared_with, prevMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())), counted, Stats.counted(this, prev), showAverage = true)
    }

    private fun legend() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        fun item(color: Int, outline: Boolean, label: Int) {
            addView(TextView(context, null, 0, R.style.Rr_Text_Muted).apply {
                setText(label)
                textSize = 11.5f
                val sw = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(context, 3f).toFloat()
                    if (outline) setStroke(Ui.dp(context, 2f), color) else setColor(color)
                    setSize(Ui.dp(context, 10f), Ui.dp(context, 10f))
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(sw, null, null, null)
                compoundDrawablePadding = Ui.dp(context, 6f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(context, 14f)
            })
        }
        item((moodColor(0) and 0x00FFFFFF) or (0x73 shl 24), false, R.string.legend_under)
        item((moodColor(4) and 0x00FFFFFF) or (0x99 shl 24), false, R.string.legend_over)
        item(attrColor(R.attr.rrText), true, R.string.today)
    }

    private fun compareCard(box: LinearLayout, title: Int, current: List<LocalDate>, previous: List<LocalDate>) =
        compareCard(box, getString(title), current, previous)

    /** "Compared with last week": change in reels a day and time saved. */
    private fun compareCard(box: LinearLayout, title: String, current: List<LocalDate>, previous: List<LocalDate>, showAverage: Boolean = false) {
        val c = card(box)
        c.addView(Ui.text(this, R.style.Rr_Text_CardTitle, title))
        val curAvg = Stats.totals(this, current).average().takeIf { current.isNotEmpty() } ?: 0.0
        val prevAvg = Stats.totals(this, previous).average().takeIf { previous.isNotEmpty() } ?: 0.0
        val change = Stats.changePercent(curAvg, prevAvg)
        if (change == null || previous.isEmpty()) {
            c.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.compare_not_yet)), Ui.gap(this, 8f))
            return
        }
        val curMin = Stats.minutes(this, current).average().takeIf { current.isNotEmpty() } ?: 0.0
        val prevMin = Stats.minutes(this, previous).average()
        val saved = Stats.minutesSaved(curMin, prevMin, current.size)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(big: String, arrow: String?, good: Boolean, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, R.style.Rr_Text_Big, big))
            addView(TextView(context, null, 0, R.style.Rr_Text_Muted).apply {
                text = SpannableStringBuilder().apply {
                    if (arrow != null) {
                        append(arrow)
                        setSpan(ForegroundColorSpan(moodColor(if (good) 0 else 4)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        append(" ")
                    }
                    append(label)
                }
            })
        }
        val sign = if (change > 0) "+" else if (change < 0) "−" else ""
        grid.addView(cell("$sign${abs(change)}%", if (change <= 0) "↓" else "↑", change <= 0, getString(R.string.reels_a_day)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        grid.addView(cell(Ui.minutes(saved * 60_000), null, true, getString(R.string.time_saved)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(grid, Ui.gap(this, 10f))
        if (showAverage) {
            c.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.average_from, curAvg.roundToInt(), prevAvg.roundToInt())), Ui.gap(this, 8f))
        }
    }

    /** The last seven days as squares, mint for each day under every limit. */
    private fun streakCard(box: LinearLayout, today: LocalDate) {
        val c = card(box, R.string.streak)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val firstDay = CountStore.firstDay(this)
        for (back in 6 downTo 0) {
            val d = today.minusDays(back.toLong())
            val ok = !d.isBefore(firstDay) && !CountStore.limitHitOn(this, d)
            row.addView(TextView(this).apply {
                text = d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = android.view.Gravity.CENTER
                textSize = 11f
                fontVariationSettings = "'wght' 700"
                setTextColor(if (ok) moodColor(0) else attrColor(R.attr.rrText3))
                background = getDrawable(if (ok) R.drawable.day_ok_bg else R.drawable.day_bg)
            }, LinearLayout.LayoutParams(0, Ui.dp(this, 38f), 1f).apply { if (back < 6) marginStart = Ui.dp(this@MainActivity, 6f) })
        }
        c.addView(row, Ui.gap(this, 10f))
        val streak = CountStore.streak(this)
        c.addView(Ui.text(this, R.style.Rr_Text_Muted, when {
            streak.brokenToday -> getString(R.string.streak_broken_short, streak.best)
            else -> resources.getQuantityString(R.plurals.streak_line, streak.current, streak.current, streak.best)
        }), Ui.gap(this, 8f))
    }

    /** "When reels get you": reels by hour, the heaviest three hours in amber. */
    private fun hoursCard(box: LinearLayout, today: LocalDate) {
        val c = card(box, R.string.when_reels_get_you)
        val days = if (month) Stats.days(YearMonth.from(today).atDay(1), today) else (6 downTo 0).map { today.minusDays(it.toLong()) }
        val hours = Stats.hours(this, days)
        val stretch = Stats.heaviestStretch(hours)
        val start = stretch?.first
        val hoursLong = stretch?.second ?: 0
        c.addView(HoursView(this).apply { setData(hours, if (start == null) emptySet() else (0 until hoursLong).map { (start + it) % 24 }.toSet()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54f)).apply { topMargin = Ui.dp(this@MainActivity, 10f) })
        c.addView(Ui.text(this, R.style.Rr_Text_Muted, if (start == null) getString(R.string.hours_none) else SpannableStringBuilder().apply {
            append(getString(R.string.hours_range, hourText(start), hourText((start + hoursLong) % 24)))
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(attrColor(R.attr.rrText)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" ")
            append(getString(if (month) R.string.hours_heaviest_month else R.string.hours_heaviest))
            if (start >= 19 || start <= 1) append(" " + getString(R.string.hours_bedtime_tip))
        }), Ui.gap(this, 8f))
    }

    private fun hourText(h: Int): String = if (android.text.format.DateFormat.is24HourFormat(this)) "%02d:00".format(h)
    else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "am" else "pm"}"

    /** Reels per app this week (or month), with the change since the period before. */
    private fun appsCard(box: LinearLayout, today: LocalDate) {
        val c = card(box, if (month) R.string.apps_this_month else R.string.apps_this_week)
        val cur: List<LocalDate>
        val prev: List<LocalDate>
        if (month) {
            val ym = YearMonth.from(today)
            cur = Stats.days(ym.atDay(1), today)
            // The same days of last month (1st to the same date), never spilling into this one.
            val last = ym.minusMonths(1)
            prev = Stats.days(last.atDay(1), last.atDay(minOf(cur.size, last.lengthOfMonth())))
        } else {
            cur = (6 downTo 0).map { today.minusDays(it.toLong()) }
            prev = (13 downTo 7).map { today.minusDays(it.toLong()) }
        }
        val rows = Platform.entries.map { p ->
            Triple(p, cur.sumOf { CountStore.appCount(this, it, p) }, prev.sumOf { CountStore.appCount(this, it, p) })
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (rows.isEmpty()) {
            c.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.apps_none)), Ui.gap(this, 6f))
            return
        }
        for ((p, n, before) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener { push(AppDetailActivity.intent(this@MainActivity, p)) }
            }
            row.addView(ImageView(this).apply { setImageDrawable(Ui.appIcon(context, p)) }, LinearLayout.LayoutParams(Ui.dp(this, 26f), Ui.dp(this, 26f)))
            row.addView(Ui.text(this, R.style.Rr_Text_RowTitle, p.shortName).apply { setPadding(Ui.dp(context, 10f), 0, 0, 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Stats.changePercent(n.toDouble(), before.toDouble())?.let { ch ->
                row.addView(TextView(this).apply {
                    text = getString(if (ch <= 0) R.string.trend_down else R.string.trend_up, abs(ch))
                    textSize = 12.5f
                    fontVariationSettings = "'wght' 700"
                    setTextColor(moodColor(if (ch <= 0) 0 else 4))
                })
            }
            row.addView(Ui.text(this, R.style.Rr_Text_Num, n.toString()).apply { gravity = android.view.Gravity.END; minWidth = Ui.dp(context, 40f) })
            c.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40f)).apply { topMargin = Ui.dp(this@MainActivity, 4f) })
        }
    }

    /** Iris on Home watches the finger wherever it moves. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::binding.isInitialized && tab == Tab.HOME &&
            (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE)
        ) {
            binding.mainEyes.watch(ev.rawX, ev.rawY)
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        private const val KEY_TAB = "tab"
        private const val KEY_MONTH = "month"

        /** The "Rokoreels" wordmark: "Roko" in the text colour, "reels" in the accent. */
        fun wordmark(ctx: Context): CharSequence = SpannableStringBuilder("Roko").apply {
            val s = length
            append("reels")
            setSpan(ForegroundColorSpan(ctx.attrColor(R.attr.rrAccent)), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /** An app row (design ".row") added to [parent]. */
        fun appRow(
            ctx: Context,
            parent: ViewGroup,
            platform: Platform,
            sub: CharSequence?,
            time: String?,
            count: String?,
            meter: Pair<Float, Int>? = null,
            chevron: Boolean = false,
            onClick: (() -> Unit)?,
        ): View {
            val row = LayoutInflater.from(ctx).inflate(R.layout.row_app, parent, false)
            row.findViewById<ImageView>(R.id.appIcon).setImageDrawable(Ui.appIconOrBadge(ctx, platform))
            row.findViewById<TextView>(R.id.appName).text = platform.shortName
            row.findViewById<TextView>(R.id.appSub).apply {
                text = sub
                isVisible = !sub.isNullOrEmpty()
            }
            row.findViewById<TextView>(R.id.appTime).apply {
                text = time
                isVisible = time != null
            }
            row.findViewById<TextView>(R.id.appCount).apply {
                text = count
                isVisible = count != null
            }
            row.findViewById<View>(R.id.appChevron).isVisible = chevron
            if (meter != null) {
                val box = row.findViewById<View>(R.id.appMeter)
                box.isVisible = true
                val fill = row.findViewById<View>(R.id.appMeterFill)
                fill.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(ctx, 2f).toFloat()
                    setColor(meter.second)
                }
                box.post {
                    fill.layoutParams.width = (box.width * meter.first.coerceIn(0f, 1f)).toInt()
                    fill.requestLayout()
                }
            }
            if (onClick != null) row.setOnClickListener { onClick() } else row.isClickable = false
            parent.addView(row)
            return row
        }

        /** Grey, outlined button look for buttons made in code. */
        fun styleSecondary(ctx: Context, b: MaterialButton) {
            b.backgroundTintList = ColorStateList.valueOf(ctx.attrColor(R.attr.rrRaised))
            b.setTextColor(ctx.attrColor(R.attr.rrText))
            b.strokeColor = ColorStateList.valueOf(ctx.attrColor(R.attr.rrLine))
            b.strokeWidth = Ui.dp(ctx, 1f)
            b.cornerRadius = Ui.dp(ctx, 16f)
            b.minHeight = Ui.dp(ctx, 52f)
            b.insetTop = 0
            b.insetBottom = 0
            Ui.pressable(b)
        }

        fun timeText(ctx: Context, minute: Int): String =
            android.text.format.DateFormat.getTimeFormat(ctx).format(
                java.util.Date.from(
                    java.time.LocalTime.of(minute / 60, minute % 60).atDate(LocalDate.now())
                        .atZone(java.time.ZoneId.systemDefault()).toInstant(),
                ),
            )

        fun isServiceEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, ReelCounterService::class.java)
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun notificationsAllowed(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Something stops reliable counting: shows the dot on Home's gear and Settings' Fix card. */
        fun needsAttention(ctx: Context) = !isServiceEnabled(ctx)

        /**
         * Opens Accessibility settings, scrolled to and highlighting Rokoreels on phones that
         * support it (Pixel and others); elsewhere the extras are ignored and the list opens.
         */
        fun openAccessibilitySettings(ctx: Context) {
            val service = ComponentName(ctx, ReelCounterService::class.java).flattenToString()
            // A separate task: reopening Rokoreels then shows Rokoreels, not Settings.
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(":settings:fragment_args_key", service)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", service) })
            ctx.startActivity(intent)
        }

        /**
         * Android 13+ blocks Accessibility for apps installed from an APK file ("Restricted
         * setting") until the user allows it in App info → ⋮. Explains that and opens App info.
         */
        fun showAccessibilityHelp(ctx: Context) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.a11y_help_title)
                .setMessage(R.string.a11y_help_text)
                .setPositiveButton(R.string.a11y_help_open) { _, _ ->
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        fun openUsageAccessSettings(ctx: Context) {
            val forThisApp = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(forThisApp)
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun openNotificationSettings(ctx: Context) {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
