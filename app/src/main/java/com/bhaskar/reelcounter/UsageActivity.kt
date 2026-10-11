package com.bhaskar.reelcounter

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import com.bhaskar.reelcounter.databinding.ActivityUsageBinding
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Phone screen time (Progress → Phone screen time), from Android's usage data (what Digital
 * Wellbeing is built on): the day's total, unlocks, notifications and first pickup; social apps
 * or every app, longest first, with bars; use by hour; and the last 7 days. Social apps open
 * their app page so you can act on what you see.
 */
class UsageActivity : BaseActivity() {

    private lateinit var binding: ActivityUsageBinding
    private val io = Executors.newSingleThreadExecutor()
    private var daysBack = 0
    private var social = true
    private var report: DayReport? = null
    private var firstShow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.prevDay.setOnClickListener { daysBack = minOf(daysBack + 1, 6); load() }
        binding.nextDay.setOnClickListener { daysBack = maxOf(daysBack - 1, 0); load() }
        binding.listSeg.setItems(listOf(getString(R.string.social_apps), getString(R.string.all_apps)), 0) {
            social = it == 0
            report?.let { r -> showList(r, animate = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        val today = LocalDate.now()
        val day = today.minusDays(daysBack.toLong())
        binding.dayLabel.text = when (daysBack) {
            0 -> getString(R.string.today)
            1 -> getString(R.string.usage_yesterday)
            else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        }
        binding.prevDay.alpha = if (daysBack < 6) 1f else 0.3f
        binding.nextDay.alpha = if (daysBack > 0) 1f else 0.3f

        if (!UsageRepository.hasAccess(this)) {
            showNoAccess()
            return
        }
        val app = applicationContext
        io.execute {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            fun endOf(d: LocalDate) = if (d == today) now else startOf(d.plusDays(1))

            val dayReport = UsageRepository.dayReport(app, startOf(day), endOf(day))
            val week = (6 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                d to UsageRepository.dayReport(app, startOf(d), endOf(d)).totalMs
            }
            runOnUiThread { if (!isDestroyed) show(dayReport, week) }
        }
    }

    private fun showNoAccess() {
        listOf(binding.totalTime, binding.unlocks, binding.notifications, binding.firstPickup).forEach { it.text = "–" }
        binding.appList.removeAllViews()
        Ui.setRow(binding.appList, R.drawable.ic_progress, getString(R.string.usage_access_button), getString(R.string.usage_access_why),
            listOf(Ui.status(this, getString(R.string.allow), ok = false))) { MainActivity.openUsageAccessSettings(this) }
        binding.listNote.text = ""
    }

    private fun show(report: DayReport, week: List<Pair<LocalDate, Long>>) {
        this.report = report
        binding.totalTime.text = Ui.minutes(report.totalMs)
        binding.unlocks.text = report.unlocks.toString()
        binding.notifications.text = if (report.notifications >= 0) report.notifications.toString() else "–"
        binding.firstPickup.text = report.firstPickup?.let { DateFormat.getTimeFormat(this).format(Date(it)) } ?: "–"
        showList(report, animate = firstShow)

        // By hour: bars for each hour, labels every 6 hours.
        val hours = FloatArray(24) { report.hourlyMs[it] / 60_000f }
        val hourLabels = List(24) { h -> if (h % 6 == 0) hourLabel(h) else "" }
        val peak = hours.indices.maxByOrNull { hours[it] }
        binding.hourChart.setData(hours, hourLabels, highlight = peak ?: -1)
        binding.peakHour.text = if (peak != null && hours[peak] > 0f) {
            getString(R.string.usage_peak, hourLabel(peak), hourLabel((peak + 1) % 24), Ui.minutes(report.hourlyMs[peak]))
        } else {
            getString(R.string.usage_none_day)
        }

        // Last 7 days of total screen time, in hours.
        val values = FloatArray(week.size) { week[it].second / 3_600_000f }
        val labels = week.mapIndexed { i, (d, _) ->
            if (i == week.lastIndex) getString(R.string.today) else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
        val valueLabels = week.map { (_, ms) -> if (ms > 0) formatShort(ms) else "" }
        binding.weekChart.setData(values, labels, valueLabels, highlight = week.lastIndex - daysBack)
        val daysWithData = week.count { it.second > 0 }.coerceAtLeast(1)
        binding.weekAverage.text = getString(R.string.usage_week_average, Ui.minutes(week.sumOf { it.second } / daysWithData))
        if (firstShow) Ui.riseChildren(binding.usageContent)
        firstShow = false
    }

    /** Social apps (the ones Rokoreels watches) or every app used, longest first, with bars. */
    private fun showList(report: DayReport, animate: Boolean) {
        val list = binding.appList
        list.removeAllViews()
        val apps = (if (social) report.social else report.apps).take(25)
        if (apps.isEmpty()) {
            Ui.setRow(list, null, getString(if (social) R.string.usage_social_none else R.string.usage_none_day))
        }
        val max = apps.maxOfOrNull { it.foregroundMs }?.coerceAtLeast(1L) ?: 1L
        for (usage in apps) {
            val platform = Platform.forPackage(usage.packageName)
            val row = layoutInflater.inflate(R.layout.row_app, list, false)
            row.findViewById<android.widget.ImageView>(R.id.appIcon).setImageDrawable(
                try {
                    packageManager.getApplicationIcon(usage.packageName)
                } catch (e: Exception) {
                    null
                },
            )
            row.findViewById<android.widget.TextView>(R.id.appName).text = usage.label
            row.findViewById<android.widget.TextView>(R.id.appSub).apply {
                text = resources.getQuantityString(R.plurals.usage_opens, usage.opens, usage.opens)
                visibility = View.VISIBLE
            }
            row.findViewById<android.widget.TextView>(R.id.appTime).text = Ui.minutes(usage.foregroundMs)
            row.findViewById<View>(R.id.appCount).visibility = View.GONE
            row.findViewById<View>(R.id.appChevron).visibility = if (platform != null) View.VISIBLE else View.GONE
            val meter = row.findViewById<View>(R.id.appMeter)
            meter.visibility = View.VISIBLE
            val fill = row.findViewById<View>(R.id.appMeterFill)
            fill.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(this@UsageActivity, 2f).toFloat()
                setColor(if (platform != null) moodColor(1) else attrColor(R.attr.rrText3))
            }
            meter.post {
                fill.layoutParams.width = (meter.width * usage.foregroundMs / max.toFloat()).toInt()
                fill.requestLayout()
            }
            if (platform != null) row.setOnClickListener { push(AppDetailActivity.intent(this, platform)) } else row.isClickable = false
            list.addView(row)
        }
        binding.listNote.setText(if (social) R.string.usage_social_note else R.string.usage_all_note)
        if (animate) Ui.riseChildren(list)
    }

    /** "12 AM", "6 PM" or "18:00", following the phone's 12/24-hour setting. */
    private fun hourLabel(hour: Int): String =
        if (DateFormat.is24HourFormat(this)) "%02d:00".format(hour)
        else "${if (hour % 12 == 0) 12 else hour % 12} ${if (hour < 12) "AM" else "PM"}"

    /** Compact form for chart labels: "45m", "2h", "2.5h". */
    private fun formatShort(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "${minutes}m"
            minutes % 60 < 15 -> "${minutes / 60}h"
            else -> "%.1fh".format(minutes / 60f)
        }
    }

}
