package com.bhaskar.reelcounter

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import com.bhaskar.reelcounter.databinding.ActivityMainBinding
import com.bhaskar.reelcounter.widget.WidgetUpdater
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The main screen, in three tabs like the design mockups:
 * - Today: Iris in the progress ring, the count (in the mood colour, heavier near the limit),
 *   what Iris says, reels by app with limit chips, and the streak.
 * - Insights: social media screen time and the 7-day chart.
 * - Settings: the daily limit, blocking, the island, theme and permissions.
 */
class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private var tab = Tab.TODAY
    private var moodColor = 0

    private enum class Tab { TODAY, INSIGHTS, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!CountStore.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tab = savedInstanceState?.getString(KEY_TAB)?.let(Tab::valueOf) ?: Tab.TODAY

        binding.navToday.setOnClickListener { showTab(Tab.TODAY) }
        binding.navInsights.setOnClickListener { showTab(Tab.INSIGHTS) }
        binding.navSettings.setOnClickListener { showTab(Tab.SETTINGS) }

        binding.enableButton.setOnClickListener { openAccessibilitySettings(this) }
        binding.a11yHelpButton.setOnClickListener { showAccessibilityHelp(this) }
        binding.bannerFix.setOnClickListener { openAccessibilitySettings(this) }
        binding.batteryButton.setOnClickListener { requestBackgroundRunning() }
        binding.usageAccessButton.setOnClickListener { openUsageAccessSettings(this) }
        binding.usageDetailsButton.setOnClickListener { startActivity(Intent(this, UsageActivity::class.java)) }

        setUpLimitStepper()
        setUpTrackedApps()
        binding.addSchedule.setOnClickListener {
            val id = (Blocking.schedules(this).maxOfOrNull { it.id } ?: 0) + 1
            editSchedule(NoReelSchedule(id, getString(R.string.schedule_custom), 21 * 60, 23 * 60, NoReelSchedule.EVERY_DAY, true), isNew = true)
        }
        setUpMoodChips()
        binding.islandPreview.cycle = true
        setUpThemeToggle()
        binding.islandUp.setOnClickListener { moveIsland(-2) }
        binding.islandDown.setOnClickListener { moveIsland(2) }
        showIslandOffset()

        binding.blockSwitch.isChecked = CountStore.blockAtLimit(this)
        binding.badgeSwitch.isChecked = CountStore.showBadge(this)
        binding.pauseSwitch.isChecked = CountStore.pauseOnOpen(this)
        binding.blockSwitch.setOnCheckedChangeListener { _, on -> CountStore.setBlockAtLimit(this, on) }
        binding.badgeSwitch.setOnCheckedChangeListener { _, on -> CountStore.setShowBadge(this, on) }
        binding.widgetsSwitch.isChecked = CountStore.widgetsEnabled(this)
        binding.widgetsSwitch.setOnCheckedChangeListener { _, on -> WidgetUpdater.setEnabled(this, on) }
        setUpCounterSize()
        binding.reduceMotionSwitch.isChecked = CountStore.reduceMotion(this)
        binding.reduceMotionSwitch.setOnCheckedChangeListener { _, on ->
            CountStore.setReduceMotion(this, on)
            // Iris picks the setting up when she's next drawn from scratch.
            recreate()
        }
        binding.playDayButton.setOnClickListener { playDay() }
        binding.pauseSwitch.setOnCheckedChangeListener { _, on -> CountStore.setPauseOnOpen(this, on) }

        binding.versionText.text = getString(R.string.version, packageManager.getPackageInfo(packageName, 0).versionName)

        // Replays onboarding; counts and settings are kept, each step starts from them.
        binding.tourButton.setOnClickListener {
            CountStore.setOnboarded(this, false)
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }

        binding.resetButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.reset) { _, _ -> CountStore.resetDay(this, LocalDate.now()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        CountStore.prefs(this).registerOnSharedPreferenceChangeListener(this)
        render()
        loadUsage()
        if (isServiceEnabled(this)) KeepAliveService.start(this)
        WidgetUpdater.updateAll(this)
    }

    override fun onPause() {
        if (::binding.isInitialized) CountStore.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = render()

    // Tabs

    private fun showTab(tab: Tab) {
        this.tab = tab
        binding.pageToday.isVisible = tab == Tab.TODAY
        binding.pageInsights.isVisible = tab == Tab.INSIGHTS
        binding.pageSettings.isVisible = tab == Tab.SETTINGS
        val items = listOf(
            Triple(Tab.TODAY, binding.navTodayBar, binding.navTodayText),
            Triple(Tab.INSIGHTS, binding.navInsightsBar, binding.navInsightsText),
            Triple(Tab.SETTINGS, binding.navSettingsBar, binding.navSettingsText),
        )
        for ((t, bar, label) in items) {
            val on = t == tab
            bar.visibility = if (on) View.VISIBLE else View.INVISIBLE
            bar.backgroundTintList = ColorStateList.valueOf(moodColor)
            label.setTextColor(getColor(if (on) R.color.fg else R.color.faint))
            label.fontVariationSettings = if (on) "'wght' 600" else "'wght' 400"
        }
    }

    // Today

    private fun render() {
        val today = LocalDate.now()
        val total = CountStore.total(this, today)
        val limit = CountStore.limit(this)
        val progress = total / limit.toFloat()
        moodColor = MoodColors.forProgress(this, progress)

        binding.dateText.text = today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

        // Hero: one mood colour on screen; the number gets heavier as the day does.
        binding.ring.set(progress, moodColor)
        if (shownTotal in 0 until total) binding.mainEyes.onReel(progress) else binding.mainEyes.setProgress(progress)
        shownTotal = total
        binding.todayCount.text = total.toString()
        binding.todayCount.setTextColor(moodColor)
        binding.todayCount.fontVariationSettings = "'wght' ${MoodColors.heroWeight(progress)}"
        binding.ofText.text = getString(R.string.of_reels, limit)
        binding.saysText.setText(MoodColors.sayingFor(progress))
        binding.limitText.text = when {
            total < limit -> getString(R.string.left_today, limit - total, limit)
            CountStore.blockAtLimit(this) -> getString(R.string.at_limit)
            else -> getString(R.string.over_limit, total - limit, limit)
        }

        // Counting status: a banner on Today, details in Settings.
        val enabled = isServiceEnabled(this)
        binding.banner.isVisible = !enabled
        binding.bannerText.text = getString(R.string.banner_counting_paused)
        binding.statusText.setText(if (enabled) R.string.status_on else R.string.status_off)
        binding.statusDetail.setText(if (enabled) R.string.status_on_detail else R.string.status_off_detail)
        binding.enableButton.isVisible = !enabled
        renderHealth()
        binding.a11yHelpButton.isVisible = !enabled
        binding.batteryCard.isVisible = !isIgnoringBatteryOptimizations()

        renderAppRows(today)
        renderAppPill(today)
        renderLimitSuggestion(today, limit)
        renderStreak()

        val opens = CountStore.opens(this, today)
        binding.opensText.isVisible = CountStore.pauseOnOpen(this)
        binding.opensText.text = if (opens < CountStore.FREE_OPENS) {
            getString(R.string.opens_free, opens, CountStore.FREE_OPENS - opens)
        } else {
            getString(R.string.opens_paused, opens, CountStore.pauseSecondsFor(opens + 1))
        }

        // Insights: last 7 days of reels.
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val values = days.map { CountStore.total(this, it) }
        val labels = days.mapIndexed { i, d ->
            if (i == days.lastIndex) getString(R.string.today)
            else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
        binding.weekChart.setData(labels, values, limit)
        val weekTotal = values.sum()
        // Average only over days the app was counting (a new install has no history yet).
        val firstDay = CountStore.firstDay(this)
        val countedDays = days.count { !it.isBefore(firstDay) }.coerceAtLeast(1)
        binding.weekStats.text = getString(R.string.week_stats, weekTotal / countedDays, weekTotal)
        renderReelInsights(today, total)

        // Settings: limit stepper and island preview.
        binding.limitValue.text = limit.toString()
        updateQuickChips(limit)
        updateIslandPreview()
        renderSchedules()

        showTab(tab)
    }

    /**
     * Diagnostics in Settings: when the counting service last started and last saw a reel app,
     * and the last crash (if any) with a Copy button, so a problem can be reported from the phone.
     */
    private fun renderHealth() {
        val format = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        fun at(millis: Long) = if (millis <= 0L) getString(R.string.health_never)
        else java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(format)
        val seen = CountStore.lastSeenAt(this)
        binding.healthText.text = getString(
            R.string.health_line,
            at(CountStore.serviceStartedAt(this)),
            if (seen > 0L) "${CountStore.lastSeenApp(this)}, ${at(seen)}" else getString(R.string.health_never),
        )
        val crash = CrashLog.read(this)
        binding.crashText.isVisible = crash != null
        binding.crashButtons.isVisible = crash != null
        if (crash != null) {
            binding.crashText.text = getString(R.string.crash_title, crash.substringBefore(" ·").take(16).replace('T', ' '))
            binding.crashCopy.setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Reel Counter crash", crash))
                android.widget.Toast.makeText(this, R.string.crash_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            binding.crashClear.setOnClickListener {
                CrashLog.clear(this)
                renderHealth()
            }
        }
    }

    /** Under the big number: each counted app's icon and reels today, e.g. [IG] 13 | [YT] 6. */
    private fun renderAppPill(today: LocalDate) {
        val pill = binding.appPill
        pill.removeAllViews()
        val dp = resources.displayMetrics.density
        val apps = Platform.entries.filter { CountStore.isTracked(this, it) && iconFor(it) != null }
        pill.isVisible = apps.isNotEmpty()
        apps.forEachIndexed { i, platform ->
            if (i > 0) pill.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                LinearLayout.LayoutParams((1 * dp).toInt(), (18 * dp).toInt()).apply { marginStart = (12 * dp).toInt(); marginEnd = (12 * dp).toInt() })
            pill.addView(ImageView(this).apply { setImageDrawable(iconFor(platform)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
            val count = CountStore.appCount(this, today, platform)
            pill.addView(TextView(this).apply {
                text = count.toString()
                setTextColor(getColor(R.color.fg))
                textSize = 15f
                typeface = resources.getFont(R.font.bricolage_grotesque)
                fontVariationSettings = "'wght' 700"
                setPadding((6 * dp).toInt(), 0, 0, 0)
                contentDescription = "${platform.shortName} " + resources.getQuantityString(R.plurals.reels_today_short, count, count)
            })
        }
        pill.setOnClickListener { showTab(Tab.INSIGHTS) }
    }

    /**
     * Settings → a suggested overall limit (idea from Regain): about three quarters of the
     * average on recent days, shown only when it's below the current limit.
     */
    private fun renderLimitSuggestion(today: LocalDate, limit: Int) {
        val firstDay = CountStore.firstDay(this)
        val past = (1..7).map { today.minusDays(it.toLong()) }.filter { !it.isBefore(firstDay) }
        val avg = if (past.isEmpty()) 0.0 else past.map { CountStore.total(this, it) }.average()
        val suggested = ((avg * 0.75 / 5).roundToInt() * 5).coerceAtLeast(5)
        val show = past.size >= 2 && avg >= 5 && suggested < limit
        binding.limitSuggestion.isVisible = show
        if (show) {
            binding.limitSuggestion.text = getString(R.string.limit_suggestion, avg.roundToInt(), suggested)
            binding.limitSuggestion.setOnClickListener { CountStore.setLimit(this, suggested) }
        }
    }

    /** "Reels by app": icon, name, time today, and a chip with the app's own limits ("5 / 20"). */
    private fun renderAppRows(today: LocalDate) {
        binding.appRows.removeAllViews()
        Platform.entries.forEach { platform ->
            val icon = iconFor(platform)
            val count = CountStore.appCount(this, today, platform)
            val timeMs = CountStore.appTime(this, today, platform)
            if (icon == null && count == 0 && timeMs < 60_000) return@forEach
            val row = layoutInflater.inflate(R.layout.row_app_limit, binding.appRows, false)
            row.findViewById<ImageView>(R.id.appIcon).setImageDrawable(icon)
            row.findViewById<TextView>(R.id.appName).text = platform.shortName
            // Reels and time together, like "12 reels · 4 min" (the count used to vanish once the
            // app had a minute of use).
            row.findViewById<TextView>(R.id.appTime).text = listOfNotNull(
                resources.getQuantityString(R.plurals.reels_today_short, count, count),
                formatDuration(timeMs).takeIf { timeMs >= 60_000 },
            ).joinToString(" · ")

            val own = CountStore.appLimit(this, platform)
            val timeLimit = CountStore.appTimeLimitMinutes(this, platform)
            val share = maxOf(
                if (own > 0) count / own.toFloat() else 0f,
                if (timeLimit > 0) timeMs / (timeLimit * 60_000f) else 0f,
            )
            row.findViewById<TextView>(R.id.appLimit).apply {
                text = listOfNotNull(
                    own.takeIf { it > 0 }?.let { "$count / $it" },
                    timeLimit.takeIf { it > 0 }?.let { getString(R.string.app_limit_time_short, it) },
                ).joinToString(" · ").ifEmpty { getString(R.string.app_limit_none) }
                setTextColor(if (own > 0 || timeLimit > 0) MoodColors.forProgress(context, share) else getColor(R.color.muted))
                setOnClickListener { AppDetailActivity.open(this@MainActivity, platform) }
            }
            row.setOnClickListener { AppDetailActivity.open(this@MainActivity, platform) }
            binding.appRows.addView(row)
        }
    }

    /**
     * Insights → Reels today: the total, reels and minutes per app (most first), and reels in
     * each hour of the day, the current hour highlighted.
     */
    private fun renderReelInsights(today: LocalDate, total: Int) {
        val apps = Platform.entries
            .map { Triple(it, CountStore.appCount(this, today, it), CountStore.appTime(this, today, it)) }
            .filter { (_, reels, ms) -> reels > 0 || ms >= 60_000 }
            .sortedByDescending { it.second }
        binding.reelsTotal.text = resources.getQuantityString(R.plurals.reels_today_short, total, total)
        binding.reelsSub.text = if (apps.isEmpty()) getString(R.string.reels_none_yet)
        else getString(R.string.reels_sub, formatDuration(apps.sumOf { it.third }), CountStore.opens(this, today))
        binding.reelsApps.removeAllViews()
        for ((platform, reels, ms) in apps) {
            val row = layoutInflater.inflate(R.layout.row_usage, binding.reelsApps, false)
            row.findViewById<ImageView>(R.id.usageIcon).setImageDrawable(iconFor(platform))
            row.findViewById<TextView>(R.id.usageName).text = platform.shortName
            row.findViewById<TextView>(R.id.usageOpens).text = formatDuration(ms)
            row.findViewById<TextView>(R.id.usageTime).text = resources.getQuantityString(R.plurals.reels_today_short, reels, reels)
            binding.reelsApps.addView(row)
        }
        val hours = CountStore.hourly(this, today)
        val is24 = android.text.format.DateFormat.is24HourFormat(this)
        val hourLabels = (0 until 24).map { h ->
            when {
                h % 6 != 0 -> ""
                is24 -> "%02d".format(h)
                h == 0 -> "12am"
                h == 12 -> "12pm"
                else -> if (h < 12) "${h}am" else "${h - 12}pm"
            }
        }
        binding.hourChart.setData(
            hours.map { it.toFloat() }.toFloatArray(),
            hourLabels,
            highlight = java.time.LocalTime.now().hour,
        )
    }

    private fun iconFor(platform: Platform) = platform.packages.firstNotNullOfOrNull { pkg ->
        try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun renderStreak() {
        val streak = CountStore.streak(this)
        binding.streakIcon.text = if (streak.brokenToday) "💔" else "🔥"
        binding.streakTitle.text = resources.getQuantityString(R.plurals.streak_days, streak.current, streak.current)
        binding.streakDetail.text = when {
            streak.brokenToday -> getString(R.string.streak_broken, streak.best)
            else -> getString(R.string.streak_detail, streak.best)
        }
    }

    // Insights: social media screen time

    private fun loadUsage() {
        val granted = UsageRepository.hasAccess(this)
        binding.usageAccessHint.isVisible = !granted
        binding.usageAccessButton.isVisible = !granted
        binding.usageContent.isVisible = granted
        if (!granted) return

        val app = applicationContext
        io.execute {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val now = System.currentTimeMillis()
            fun startOf(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()

            val todayUsage = UsageRepository.socialUsage(app, startOf(today), now)
            val weekMs = (0..6).sumOf { back ->
                val day = today.minusDays(back.toLong())
                val end = if (back == 0) now else startOf(day.plusDays(1))
                UsageRepository.socialUsage(app, startOf(day), end).sumOf { it.foregroundMs }
            }
            runOnUiThread { if (!isDestroyed) showUsage(todayUsage, weekMs) }
        }
    }

    private fun showUsage(apps: List<AppUsage>, weekMs: Long) {
        val totalMs = apps.sumOf { it.foregroundMs }
        binding.usageTotal.text = if (apps.isEmpty()) getString(R.string.usage_none)
        else getString(R.string.usage_total_today, formatDuration(totalMs))
        binding.usageTotal.textSize = if (apps.isEmpty()) 15f else 32f
        binding.usageOpensTotal.isVisible = apps.isNotEmpty()
        binding.usageOpensTotal.text = getString(R.string.usage_opens_total, apps.sumOf { it.opens })

        binding.usageList.removeAllViews()
        apps.forEach { usage ->
            val row = layoutInflater.inflate(R.layout.row_usage, binding.usageList, false)
            row.findViewById<TextView>(R.id.usageName).text = usage.label
            row.findViewById<TextView>(R.id.usageOpens).text = getString(R.string.usage_app_opens, usage.opens)
            row.findViewById<TextView>(R.id.usageTime).text = formatDuration(usage.foregroundMs)
            val icon = try {
                packageManager.getApplicationIcon(usage.packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            row.findViewById<ImageView>(R.id.usageIcon).setImageDrawable(icon)
            binding.usageList.addView(row)
        }

        binding.usageWeek.text = getString(R.string.usage_week, formatDuration(weekMs), formatDuration(weekMs / 7))
    }

    // Settings

    /** The daily limit as a stepper (±5) with quick picks, like onboarding's "Set a limit". */
    private fun setUpLimitStepper() {
        binding.limitMinus.setOnClickListener { CountStore.setLimit(this, (CountStore.limit(this) - 5).coerceAtLeast(5)) }
        binding.limitPlus.setOnClickListener { CountStore.setLimit(this, (CountStore.limit(this) + 5).coerceAtMost(500)) }
        QUICK_LIMITS.forEach { value ->
            binding.limitQuick.addView(filterChip(this).apply {
                id = View.generateViewId()
                text = value.toString()
                isCheckable = true
                setOnClickListener { CountStore.setLimit(this@MainActivity, value) }
            })
        }
    }

    // No-reel hours

    private fun timeText(minute: Int): String =
        android.text.format.DateFormat.getTimeFormat(this).format(
            java.util.Date.from(java.time.LocalTime.of(minute / 60, minute % 60).atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant()),
        )

    private fun daysText(days: Int): String = when (days) {
        NoReelSchedule.EVERY_DAY -> getString(R.string.schedule_every_day)
        NoReelSchedule.WEEKDAYS -> getString(R.string.schedule_weekdays)
        0b1100000 -> getString(R.string.schedule_weekends)
        else -> java.time.DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }
            .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    /** Settings → No-reel hours: one row per schedule with its times, days and an on/off switch. */
    private fun renderSchedules() {
        binding.scheduleRows.removeAllViews()
        val now = java.time.LocalDateTime.now()
        for (schedule in Blocking.schedules(this)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
                isClickable = true
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { editSchedule(schedule, isNew = false) }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = schedule.name
                setTextColor(getColor(R.color.fg))
                textSize = 15f
            })
            texts.addView(TextView(this).apply {
                text = if (schedule.isActive(now)) getString(R.string.schedule_active_now, timeText(schedule.endMinute))
                else "${timeText(schedule.startMinute)} – ${timeText(schedule.endMinute)} · ${daysText(schedule.days)}"
                setTextColor(getColor(if (schedule.isActive(now)) R.color.accent else R.color.muted))
                textSize = 13f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                isChecked = schedule.enabled
                contentDescription = schedule.name
                setOnCheckedChangeListener { _, on -> Blocking.saveSchedule(this@MainActivity, schedule.copy(enabled = on)) }
            })
            binding.scheduleRows.addView(row)
        }
    }

    /** Change a schedule's start, end and days (and delete it). */
    private fun editSchedule(original: NoReelSchedule, isNew: Boolean) {
        var edited = original
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val startButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val endButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun refresh() {
            startButton.text = getString(R.string.schedule_starts, timeText(edited.startMinute))
            endButton.text = getString(R.string.schedule_ends, timeText(edited.endMinute))
        }
        fun pick(minute: Int, onPicked: (Int) -> Unit) {
            val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTimeFormat(
                    if (android.text.format.DateFormat.is24HourFormat(this)) com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                    else com.google.android.material.timepicker.TimeFormat.CLOCK_12H,
                )
                .setHour(minute / 60).setMinute(minute % 60).build()
            picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
            picker.show(supportFragmentManager, "time")
        }
        startButton.setOnClickListener { pick(edited.startMinute) { edited = edited.copy(startMinute = it); refresh() } }
        endButton.setOnClickListener { pick(edited.endMinute) { edited = edited.copy(endMinute = it); refresh() } }
        refresh()
        box.addView(startButton)
        box.addView(endButton)
        val days = com.google.android.material.chip.ChipGroup(this)
        java.time.DayOfWeek.entries.forEach { day ->
            days.addView(filterChip(this).apply {
                text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                isChecked = edited.runsOn(day)
                setOnCheckedChangeListener { _, on ->
                    val bit = 1 shl (day.value - 1)
                    edited = edited.copy(days = if (on) edited.days or bit else edited.days and bit.inv())
                }
            })
        }
        box.addView(days)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(original.name)
            .setView(box)
            .setPositiveButton(R.string.schedule_save) { _, _ ->
                Blocking.saveSchedule(this, edited.copy(enabled = true))
                renderSchedules()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (!isNew) dialog.setNeutralButton(R.string.schedule_delete) { _, _ ->
            Blocking.deleteSchedule(this, original.id)
            renderSchedules()
        }
        dialog.show()
    }

    /** Settings → Apps I count: one tick per supported app (onboarding's choice, changeable). */
    private fun setUpTrackedApps() {
        Platform.entries.forEach { platform ->
            binding.trackedApps.addView(filterChip(this).apply {
                id = View.generateViewId()
                text = platform.shortName
                isChecked = CountStore.isTracked(this@MainActivity, platform)
                setOnCheckedChangeListener { _, on -> CountStore.setTracked(this@MainActivity, platform, on) }
            })
        }
    }

    private fun updateQuickChips(limit: Int) {
        for (i in 0 until binding.limitQuick.childCount) {
            val chip = binding.limitQuick.getChildAt(i) as Chip
            chip.isChecked = chip.text.toString().toIntOrNull() == limit
        }
    }

    // Island preview: one chip per mood; the island also shows a sample 3-minute timer.

    private var previewMood = Mood.CURIOUS
    private val previewSessionStart = SystemClock.elapsedRealtime() - 192_000L

    /** Count shown in the hero last time, so a new reel makes Iris hop; -1 before the first. */
    private var shownTotal = -1

    private fun setUpMoodChips() {
        val names = mapOf(
            Mood.HAPPY to R.string.mood_happy,
            Mood.CURIOUS to R.string.mood_curious,
            Mood.WORRIED to R.string.mood_worried,
            Mood.SAD to R.string.mood_sad,
            Mood.SLEEPY to R.string.mood_sleepy,
            Mood.SURPRISED to R.string.mood_surprised,
            Mood.PLEADING to R.string.mood_pleading,
            Mood.ELECTRIC to R.string.mood_electric,
            Mood.ANGRY to R.string.mood_angry,
        )
        names.forEach { (mood, name) ->
            binding.moodChips.addView(Chip(this).apply {
                id = View.generateViewId()
                setText(name)
                isCheckable = true
                isChecked = mood == previewMood
                setOnClickListener {
                    stopPlayDay()
                    previewMood = mood
                    if (mood == Mood.PLEADING) binding.islandPreview.kick()
                    updateIslandPreview()
                }
            })
        }
    }

    /** Settings → Counter size: the preview grows and shrinks with the slider. */
    private fun setUpCounterSize() {
        val size = CountStore.counterSizeDp(this)
        binding.counterSizeSlider.value = size.toFloat()
        binding.counterSizeText.text = getString(R.string.counter_size, size)
        binding.counterSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val dp = value.toInt()
            CountStore.setCounterSizeDp(this, dp)
            binding.counterSizeText.text = getString(R.string.counter_size, dp)
            binding.islandPreview.sizeDp = dp
        }
    }

    private fun updateIslandPreview() {
        if (playing != null) return
        binding.islandPreview.lockedMood = previewMood
        binding.islandPreview.setState(CountStore.total(this, LocalDate.now()), previewSessionStart, 0f)
    }

    /**
     * The demo's "Play a full day" on the preview: 30 reels from happy to angry, with a binge
     * (electrocuted), a doze (sleepy) and a pause screen (pleading) on the way.
     */
    private var playing: Runnable? = null

    private fun playDay() {
        stopPlayDay()
        binding.moodChips.clearCheck()
        val island = binding.islandPreview
        // Each step: wait (ms) then do something.
        val steps = ArrayList<Pair<Long, () -> Unit>>()
        var n = 0
        fun reels(count: Int, gap: Long, firstGap: Long = gap) = repeat(count) { k ->
            steps += (if (k == 0) firstGap else gap) to {
                n++
                island.setState(n, previewSessionStart, n / PLAY_LIMIT)
                island.onReel(n / PLAY_LIMIT)
            }
        }
        steps += 0L to {
            island.lockedMood = null
            island.sleepy = false
            island.setState(0, previewSessionStart, 0f)
        }
        reels(8, 700, firstGap = 900)
        reels(4, 330)
        reels(6, 800, firstGap = 1800)
        steps += 800L to { island.sleepy = true }
        reels(7, 800, firstGap = 4200)
        steps += 800L to { island.lockedMood = Mood.PLEADING; island.kick() }
        steps += 3000L to { island.lockedMood = null }
        reels(5, 900)
        var i = 0
        val runner = object : Runnable {
            override fun run() {
                if (i >= steps.size) {
                    playing = null
                    return
                }
                steps[i].second()
                i++
                if (i < steps.size) binding.root.postDelayed(this, steps[i].first) else playing = null
            }
        }
        playing = runner
        runner.run()
    }

    private fun stopPlayDay() {
        playing?.let { binding.root.removeCallbacks(it) }
        playing = null
    }

    private fun moveIsland(dp: Int) {
        CountStore.setIslandOffsetDp(this, CountStore.islandOffsetDp(this) + dp)
        showIslandOffset()
    }

    private fun showIslandOffset() {
        val offset = CountStore.islandOffsetDp(this)
        binding.islandOffsetText.text = when {
            offset < 0 -> getString(R.string.island_offset_up, -offset)
            offset > 0 -> getString(R.string.island_offset_down, offset)
            else -> getString(R.string.island_offset_none)
        }
    }

    private fun setUpThemeToggle() {
        val buttons = mapOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.id.themeSystem,
            AppCompatDelegate.MODE_NIGHT_NO to R.id.themeLight,
            AppCompatDelegate.MODE_NIGHT_YES to R.id.themeDark,
        )
        binding.themeToggle.check(buttons[CountStore.themeMode(this)] ?: R.id.themeDark)
        binding.themeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = buttons.entries.first { it.value == id }.key
            if (mode != CountStore.themeMode(this)) {
                CountStore.setThemeMode(this, mode)
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBackgroundRunning() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> getString(R.string.duration_under_minute)
            minutes < 60 -> getString(R.string.duration_minutes, minutes.toInt())
            else -> getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    /** Iris on the Today screen watches the finger wherever it moves. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // (binding isn't set when this screen closes right away to show onboarding.)
        if (::binding.isInitialized &&
            (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE)
        ) {
            binding.mainEyes.watch(ev.rawX, ev.rawY)
            binding.islandPreview.watch(ev.rawX, ev.rawY)
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        /** The sample limit "Play a full day" runs to. */
        private const val PLAY_LIMIT = 30f

        private const val KEY_TAB = "tab"
        private val QUICK_LIMITS = listOf(20, 40, 60, 100)

        fun isServiceEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, ReelCounterService::class.java)
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        /**
         * Opens Accessibility settings, scrolled to and highlighting Reel Counter on phones that
         * support it (Pixel and others); elsewhere the extras are ignored and the list opens.
         */
        fun openAccessibilitySettings(ctx: Context) {
            val service = ComponentName(ctx, ReelCounterService::class.java).flattenToString()
            // A separate task: reopening Reel Counter then shows Reel Counter, not Settings.
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
    }
}
