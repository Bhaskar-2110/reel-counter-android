package com.reelcounter

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Settings (design): a Fix card only when something is broken; your rules as four tiles that
 * show their current value (Daily limit, App rules, Focus hours, Pause before opening); Iris;
 * the theme as four swatches; and everything rare under More.
 */
class SettingsActivity : PageActivity() {

    override fun title(): CharSequence = getString(R.string.settings)

    override fun build() {
        if (MainActivity.needsAttention(this)) {
            val off = !MainActivity.isServiceEnabled(this)
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.alert_bg)
                setPadding(Ui.dp(context, 14f), Ui.dp(context, 12f), Ui.dp(context, 14f), Ui.dp(context, 12f))
                addView(iconBox(R.drawable.ic_shield, moodColor(2)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(context, 12f), 0, Ui.dp(context, 8f), 0)
                    addView(Ui.text(context, R.style.Rr_Text_RowTitle, getString(if (off) R.string.alert_counting_off else R.string.alert_may_stop)))
                    addView(Ui.text(context, R.style.Rr_Text_Sub, getString(R.string.alert_one_fix)))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.status(context, getString(R.string.fix), ok = false))
                Ui.pressable(this)
                setOnClickListener { push(Intent(this@SettingsActivity, PermissionsActivity::class.java)) }
            }, Ui.gap(this, 4f))
        }

        section(R.string.account)
        val user = Account.current(this)
        if (user == null) {
            val sub = getString(if (Account.isSetUp(this)) R.string.sign_in_google_sub else R.string.sign_in_not_set_up)
            Ui.setRow(list(), R.drawable.ic_user, getString(R.string.sign_in_google), sub, listOf(Ui.chevron(this))) {
                Account.signIn(this) { signedIn, error ->
                    if (signedIn != null) rebuild()
                    else if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            val email = user.email.orEmpty()
            Ui.setRow(list(), R.drawable.ic_user, Account.label(user), email.takeIf { it != Account.label(user) },
                listOf(Ui.value(this, getString(R.string.sign_out)))) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.sign_out)
                    .setMessage(getString(R.string.sign_out_confirm, email))
                    .setPositiveButton(R.string.sign_out) { _, _ ->
                        Account.signOut(this)
                        rebuild()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        section(R.string.your_rules)
        val limit = CountStore.limit(this)
        val rules = Platform.entries.count { AppDetailActivity.ruleTags(this, it).isNotEmpty() }
        val focusOn = Blocking.schedules(this).count { it.enabled }
        val free = CountStore.freeOpens(this)
        tiles(
            Tile(R.drawable.ic_limit, attrColor(R.attr.rrAccent), resources.getQuantityString(R.plurals.n_reels, limit, limit), getString(R.string.daily_limit)) { dailyLimitSheet(this) },
            Tile(R.drawable.ic_apps, moodColor(1), resources.getQuantityString(R.plurals.n_apps, rules, rules), getString(R.string.app_rules)) {
                push(Intent(this, AppRulesActivity::class.java))
            },
            Tile(R.drawable.ic_focus, moodColor(2), getString(R.string.n_on, focusOn), getString(R.string.focus_hours)) {
                push(Intent(this, FocusActivity::class.java))
            },
            Tile(R.drawable.ic_pause, moodColor(3),
                if (CountStore.pauseOnOpen(this)) getString(R.string.after_n, free) else getString(R.string.off),
                getString(R.string.pause_before_opening)) { pauseSheet(this) },
        )

        section(R.string.iris)
        val size = sizePercent(CountStore.counterSizeDp(this))
        Ui.setRow(list(), R.drawable.ic_eye, getString(R.string.iris_over_apps), getString(R.string.iris_size_sub, size),
            listOf(Ui.value(this, getString(if (CountStore.showBadge(this)) R.string.on else R.string.off)), Ui.chevron(this))) {
            push(Intent(this, IrisSettingsActivity::class.java))
        }

        section(R.string.appearance)
        val themes = card(8f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val current = CountStore.theme(this)
        for (t in AppTheme.entries) {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                contentDescription = getString(R.string.theme_cd, themeName(t))
                isSelected = t == current
                addView(ThemeDot(context).apply {
                    theme = t
                    isSelected = t == current
                }, LinearLayout.LayoutParams(Ui.dp(context, 46f), Ui.dp(context, 46f)))
                addView(Ui.text(context, R.style.Rr_Text, themeName(t)).apply {
                    textSize = 11.5f
                    fontVariationSettings = "'wght' 600"
                    setTextColor(attrColor(if (t == current) R.attr.rrText else R.attr.rrText2))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ui.dp(context, 6f)
                })
                Ui.pressable(this)
                setOnClickListener { pickTheme(t) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        themes.addView(row)

        Ui.setRow(list(12f), R.drawable.ic_help, getString(R.string.more), getString(R.string.more_sub), listOf(Ui.chevron(this))) {
            push(Intent(this, MoreActivity::class.java))
        }
        content.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.version, versionName())).apply {
            gravity = Gravity.CENTER
        }, Ui.gap(this, 14f))
    }

    private fun versionName() = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun themeName(t: AppTheme) = getString(
        when (t) {
            AppTheme.SYSTEM -> R.string.theme_system
            AppTheme.DARK -> R.string.theme_dark
            AppTheme.LIGHT -> R.string.theme_light
            AppTheme.ROYAL -> R.string.theme_royal
        },
    )

    /** The app changes instantly (design: "Themes are four circles you tap right here"). */
    private fun pickTheme(t: AppTheme) {
        if (t == CountStore.theme(this)) return
        CountStore.setTheme(this, t)
        AppTheme.applyNightMode(this)
        com.reelcounter.widget.WidgetUpdater.updateAll(this)
        // A change of the effective night mode recreates every screen by itself; Dark ↔ Royal,
        // or System ↔ the mode the phone is in already, doesn't. Then redraw this screen here
        // (screens behind it notice when they come back, see BaseActivity).
        window.decorView.post { if (!isFinishing && !isDestroyed) recreate() }
    }

    private class Tile(val icon: Int, val color: Int, val value: String, val label: String, val onClick: () -> Unit)

    /** The rules as a 2 × 2 grid of tiles (design ".rtiles"). */
    private fun tiles(vararg tiles: Tile) {
        tiles.toList().chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, t ->
                line.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = getDrawable(R.drawable.card_bg)
                    setPadding(Ui.dp(context, 12f), Ui.dp(context, 12f), Ui.dp(context, 12f), Ui.dp(context, 12f))
                    minimumHeight = Ui.dp(context, 98f)
                    addView(iconBox(t.icon, t.color))
                    addView(Ui.text(context, R.style.Rr_Text_Big, t.value).apply {
                        textSize = 19f
                        fontVariationSettings = "'wght' 700"
                        maxLines = 1
                    }, Ui.gap(context, 8f))
                    addView(Ui.text(context, R.style.Rr_Text, t.label).apply {
                        textSize = 12f
                        fontVariationSettings = "'wght' 600"
                        setTextColor(attrColor(R.attr.rrText2))
                    }, Ui.gap(context, 2f))
                    contentDescription = "${t.label}: ${t.value}"
                    Ui.pressable(this)
                    isClickable = true
                    setOnClickListener { t.onClick() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 1) marginStart = Ui.dp(this@SettingsActivity, 8f)
                })
            }
            content.addView(line, Ui.gap(this, 8f))
        }
    }

    private fun iconBox(icon: Int, color: Int) = android.widget.FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dp(context, 9f).toFloat()
            setColor((color and 0x00FFFFFF) or (0x29 shl 24))
        }
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color)
        }, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 17f), Ui.dp(context, 17f), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 30f), Ui.dp(context, 30f))
    }

    companion object {
        /** Iris's size as a percentage of the design's 56dp. */
        fun sizePercent(dp: Int) = (dp * 100f / CountStore.COUNTER_SIZE_DEFAULT / 10f).let { Math.round(it) * 10 }

        /** "Daily reel limit": the stepper, quick picks and whether reaching it blocks reels. */
        fun dailyLimitSheet(activity: Activity) {
            val ctx = activity
            Ui.sheet(activity, ctx.getString(R.string.daily_reel_limit)) { box ->
                val sub = Ui.text(ctx, R.style.Rr_Text_Muted)
                fun subText(v: Int) = ctx.getString(R.string.daily_limit_sub, Math.round(v * 2.3f))
                sub.text = subText(CountStore.limit(ctx))
                box.addView(sub, Ui.gap(ctx, 6f))
                var number: TextView? = null
                val chips = ArrayList<TextView>()
                fun set(v: Int) {
                    val value = v.coerceIn(5, 500)
                    CountStore.setLimit(ctx, value)
                    Analytics.limitSetSoon(ctx, value)
                    number?.text = value.toString()
                    number?.let(Ui::bump)
                    sub.text = subText(value)
                    chips.forEach { it.isSelected = it.tag == value }
                }
                val (stepper, num) = Ui.stepper(ctx, CountStore.limit(ctx), onType = { set(it) }) { set(CountStore.limit(ctx) + it) }
                number = num
                box.addView(stepper, Ui.gap(ctx, 16f))
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (v in listOf(20, 30, 50)) {
                    val label = if (v == 30) ctx.getString(R.string.suggested_n, v) else v.toString()
                    row.addView(Ui.chip(ctx, label, CountStore.limit(ctx) == v) { set(v) }.also {
                        it.tag = v
                        chips += it
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = Ui.dp(ctx, 4f)
                        marginEnd = Ui.dp(ctx, 4f)
                    })
                }
                box.addView(row, Ui.gap(ctx, 16f))
                val sw = MaterialSwitch(ctx).apply {
                    setText(R.string.setting_block)
                    setTextColor(ctx.attrColor(R.attr.rrText))
                    textSize = 14f
                    isChecked = CountStore.blockAtLimit(ctx)
                    setOnCheckedChangeListener { _, on -> CountStore.setBlockAtLimit(ctx, on) }
                }
                box.addView(sw, Ui.gap(ctx, 14f))
            }
        }

        /** "Pause before opening": on/off, and how many free opens a day. */
        fun pauseSheet(activity: Activity) {
            val ctx = activity
            Ui.sheet(activity, ctx.getString(R.string.pause_before_opening)) { box ->
                val sw = MaterialSwitch(ctx).apply {
                    text = ctx.getString(R.string.pause_reel_apps)
                    setTextColor(ctx.attrColor(R.attr.rrText))
                    textSize = 14f
                    isChecked = CountStore.pauseOnOpen(ctx)
                    setOnCheckedChangeListener { _, on -> CountStore.setPauseOnOpen(ctx, on) }
                }
                box.addView(sw, Ui.gap(ctx, 6f))
                box.addView(Ui.text(ctx, R.style.Rr_Text_Sub, ctx.getString(R.string.pause_reel_apps_sub)))
                box.addView(Ui.text(ctx, R.style.Rr_Text_Muted, ctx.getString(R.string.free_opens_a_day)), Ui.gap(ctx, 16f))
                box.addView(Ui.opts(ctx, CountStore.FREE_OPENS_CHOICES, CountStore.freeOpens(ctx), 3, { it.toString() }) {
                    CountStore.setFreeOpens(ctx, it)
                }, Ui.gap(ctx, 8f))
                box.addView(Ui.text(ctx, R.style.Rr_Text_Muted, ctx.getString(R.string.pause_steps)), Ui.gap(ctx, 12f))
            }
        }
    }
}
