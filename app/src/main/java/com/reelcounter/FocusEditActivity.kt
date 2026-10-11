package com.reelcounter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Add or edit focus hours (design): name presets first, big tappable From and To, the days, the
 * apps to lock (the same chips as onboarding), then Save.
 */
class FocusEditActivity : PageActivity() {

    private lateinit var edited: NoReelSchedule
    private var isNew = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val existing = Blocking.schedules(this).firstOrNull { it.id == id }
        isNew = existing == null
        edited = savedInstanceState?.getString(KEY_STATE)?.let(NoReelSchedule::decode)
            ?: existing
            ?: NoReelSchedule(
                Blocking.nextScheduleId(this), freshName(), 10 * 60, 18 * 60, NoReelSchedule.WEEKDAYS, true,
                NoReelSchedule.appsMask(listOf(Platform.INSTAGRAM, Platform.YOUTUBE, Platform.SNAPCHAT)),
            )
        super.onCreate(savedInstanceState)
    }

    /** The first preset name not used yet, so a new schedule isn't a second "Work". */
    private fun freshName(): String {
        val used = Blocking.schedules(this).map { it.name }.toSet()
        return listOf(R.string.focus_work, R.string.focus_study, R.string.focus_sleep, R.string.focus_gym)
            .map(::getString).firstOrNull { it !in used } ?: getString(R.string.custom)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, edited.encode())
    }

    override fun title(): CharSequence = if (isNew) getString(R.string.new_focus_hours) else getString(R.string.named_focus, edited.name)

    override fun onSharedPreferenceChanged(prefs: android.content.SharedPreferences?, key: String?) {
        // The draft lives here until Save; nothing outside changes it.
    }

    override fun build() {
        setPageTitle(title())

        section(R.string.name)
        val presets = listOf(R.string.focus_work, R.string.focus_study, R.string.focus_sleep, R.string.focus_gym).map(::getString)
        val names = Ui.chipFlow(this)
        val isCustom = edited.name !in presets
        for (n in presets) names.addView(Ui.chip(this, n, edited.name == n) { edited = edited.copy(name = n); rebuild() })
        names.addView(Ui.chip(this, if (isCustom) edited.name else getString(R.string.custom), isCustom) { askName() })
        content.addView(names, Ui.gap(this, 8f))

        // From → To, the biggest things on the screen.
        val times = card(12f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun timeCell(label: Int, minute: Int, onPick: (Int) -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Ui.text(context, R.style.Rr_Text_Muted, getString(label)))
            addView(Ui.text(context, R.style.Rr_Text_Big, MainActivity.timeText(context, minute)).apply { textSize = 26f })
            contentDescription = "${getString(label)} ${MainActivity.timeText(context, minute)}"
            Ui.pressable(this)
            setOnClickListener { pickTime(minute, onPick) }
        }
        row.addView(timeCell(R.string.from, edited.startMinute) { edited = edited.copy(startMinute = it); rebuild() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(this, R.style.Rr_Text_Muted, "→").apply { setPadding(0, Ui.dp(context, 18f), 0, 0) })
        row.addView(timeCell(R.string.to_cap, edited.endMinute) { edited = edited.copy(endMinute = it); rebuild() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(row)

        section(R.string.days)
        val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        DayOfWeek.entries.forEachIndexed { i, d ->
            val on = edited.runsOn(d)
            days.addView(TextView(this).apply {
                text = d.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                contentDescription = d.getDisplayName(TextStyle.FULL, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 12f
                fontVariationSettings = "'wght' 700"
                isSelected = on
                setTextColor(attrColor(if (on) R.attr.rrAccent else R.attr.rrText3))
                background = getDrawable(if (on) R.drawable.day_on_bg else R.drawable.day_bg)
                Ui.pressable(this)
                setOnClickListener {
                    val bit = 1 shl (d.value - 1)
                    edited = edited.copy(days = if (on) edited.days and bit.inv() else edited.days or bit)
                    rebuild()
                }
            }, LinearLayout.LayoutParams(0, Ui.dp(this, 40f), 1f).apply { if (i > 0) marginStart = Ui.dp(this@FocusEditActivity, 6f) })
        }
        content.addView(days, Ui.gap(this, 8f))

        section(R.string.lock_these_apps)
        val apps = Ui.chipFlow(this)
        for (p in Ui.listedPlatforms(this)) {
            apps.addView(Ui.chip(this, p.shortName, edited.locks(p), Ui.appIconOrBadge(this, p).let { it.constantState?.newDrawable()?.mutate() ?: it }) {
                val bit = 1 shl p.ordinal
                edited = edited.copy(apps = if (edited.locks(p)) edited.apps and bit.inv() else edited.apps or bit)
                rebuild()
            })
        }
        content.addView(apps, Ui.gap(this, 8f))

        val canSave = edited.days != 0 && edited.apps != 0 && edited.startMinute != edited.endMinute
        content.addView(MaterialButton(this).apply {
            setText(R.string.save_focus_hours)
            isEnabled = canSave
            alpha = if (canSave) 1f else 0.4f
            setOnClickListener {
                Blocking.saveSchedule(this@FocusEditActivity, edited.copy(enabled = true))
                finish()
            }
        }, Ui.gap(this, 18f))
        if (!canSave) content.addView(Ui.text(this, R.style.Rr_Text_Sub, getString(R.string.focus_needs)).apply { gravity = Gravity.CENTER }, Ui.gap(this, 6f))
        if (!isNew) {
            content.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setText(R.string.schedule_delete)
                setTextColor(moodColor(4))
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@FocusEditActivity)
                        .setTitle(getString(R.string.delete_focus_title, edited.name))
                        .setPositiveButton(R.string.schedule_delete) { _, _ ->
                            Blocking.deleteSchedule(this@FocusEditActivity, edited.id)
                            finish()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }, Ui.gap(this, 4f))
        }
    }

    private fun pickTime(minute: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (android.text.format.DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(minute / 60).setMinute(minute % 60).build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "time")
    }

    private fun askName() {
        val input = EditText(this).apply {
            setText(edited.name)
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(24))
            isSingleLine = true
        }
        val pad = Ui.dp(this, 22f)
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, Ui.dp(context, 8f), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.focus_name)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().replace(";", " ").replace("|", " ")
                if (name.isNotEmpty()) {
                    edited = edited.copy(name = name)
                    rebuild()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val KEY_STATE = "draft"

        fun intent(ctx: Context, id: Int?) = Intent(ctx, FocusEditActivity::class.java).apply { if (id != null) putExtra(EXTRA_ID, id) }
    }
}
