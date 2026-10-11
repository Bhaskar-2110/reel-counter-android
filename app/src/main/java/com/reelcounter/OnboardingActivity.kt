package com.reelcounter

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.reelcounter.databinding.ActivityOnboardingBinding
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * First open (design/Rokoreels App Flow.html): one promise and one button, then six steps with
 * one job each, easy asks first.
 *
 * Welcome → Pick your apps → Your week in reels (usage access, the low-risk ask with an
 * instant payoff) → Set your limit → Turn on counting (the Accessibility disclosure, with an
 * explicit "I understand") → Keep it running (notifications) → Meet Iris → Home.
 *
 * Progress is saved, so it resumes where it was when the phone closes the app while the user is
 * in Settings granting a permission (OnePlus and others do this often).
 */
class OnboardingActivity : BaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val io = Executors.newSingleThreadExecutor()

    private var step = Step.WELCOME
    private var limit = 30
    private var agreed = false
    private var revealed = false
    private var weekMs = -1L
    private var weekApps: List<String> = emptyList()
    private val faces = ArrayList<EyesView>()

    private enum class Step { WELCOME, APPS, WEEK, LIMIT, ACCESS, RUNNING, IRIS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Replaying the tour (or restored from a backup): start from the saved limit.
        if (CountStore.guess(this) > 0 || CountStore.prefs(this).contains("daily_limit")) limit = CountStore.limit(this)
        binding.obBack.setOnClickListener { back() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        restoreProgress()
        show(firstMissing(step), direction = 0)
    }

    override fun onResume() {
        super.onResume()
        // Back from a system screen: move on once the permission is granted.
        when {
            step == Step.WEEK && !revealed && UsageRepository.hasAccess(this) -> {
                revealed = true
                show(Step.WEEK, direction = 0)
            }
            step == Step.ACCESS && MainActivity.isServiceEnabled(this) -> show(Step.RUNNING, direction = 1)
            step == Step.RUNNING -> show(Step.RUNNING, direction = 0)
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun back() {
        when (step) {
            Step.WELCOME -> finish()
            Step.IRIS -> {}
            else -> show(Step.entries[step.ordinal - 1], direction = -1)
        }
    }

    // Progress

    private fun restoreProgress() {
        val parts = CountStore.onboardingProgress(this)?.split('|') ?: return
        step = Step.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return
        limit = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(5, 500) ?: limit
        agreed = parts.getOrNull(2) == "1"
        revealed = parts.getOrNull(3) == "1"
    }

    private fun saveProgress() = CountStore.setOnboardingProgress(
        this, listOf(step.name, limit, if (agreed) 1 else 0, if (revealed) 1 else 0).joinToString("|"),
    )

    // Rendering

    /** Shows [s]; [direction] 1 slides it in from the right, -1 from the left, 0 not at all. */
    private fun show(s: Step, direction: Int) {
        step = s
        saveProgress()
        faces.clear()
        val body = binding.body
        body.removeAllViews()
        binding.ghost.isVisible = false
        binding.primary.isEnabled = true
        binding.primary.alpha = 1f
        binding.obTop.visibility = if (s == Step.WELCOME) View.INVISIBLE else View.VISIBLE
        binding.obBack.visibility = if (s == Step.IRIS) View.INVISIBLE else View.VISIBLE
        renderProgress(s.ordinal)
        when (s) {
            Step.WELCOME -> welcome()
            Step.APPS -> apps()
            Step.WEEK -> week()
            Step.LIMIT -> limitStep()
            Step.ACCESS -> access()
            Step.RUNNING -> running()
            Step.IRIS -> meetIris()
        }
        if (direction != 0 && !CountStore.reduceMotion(this)) {
            body.translationX = body.width * (if (direction > 0) 0.28f else -0.18f)
            body.alpha = 0f
            body.animate().translationX(0f).alpha(1f).setDuration(if (direction > 0) 340 else 300).setInterpolator(Ui.EASE).start()
        }
    }

    /** The six-step bar (design ".prog"): segments up to this step in the accent colour. */
    private fun renderProgress(n: Int) {
        val bar = binding.progress
        bar.removeAllViews()
        for (i in 1..6) {
            bar.addView(View(this).apply {
                background = getDrawable(if (i <= n) R.drawable.progress_seg_on else R.drawable.progress_seg_off)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 1) marginStart = Ui.dp(this@OnboardingActivity, 5f) })
        }
        binding.stepText.text = getString(R.string.step_of, n.coerceIn(1, 6))
    }

    private fun title(text: CharSequence, center: Boolean = false) = binding.body.addView(
        Ui.text(this, R.style.Rr_Text_OnbTitle, text).apply { if (center) gravity = Gravity.CENTER },
        Ui.gap(this, 0f),
    )

    private fun para(text: CharSequence, center: Boolean = false) = binding.body.addView(
        Ui.text(this, R.style.Rr_Text_Body, text).apply {
            textSize = 15f
            if (center) gravity = Gravity.CENTER
        },
        Ui.gap(this, 12f),
    )

    private fun primary(text: CharSequence, enabled: Boolean = true, onClick: () -> Unit) {
        binding.primary.text = text
        binding.primary.isEnabled = enabled
        binding.primary.alpha = if (enabled) 1f else 0.4f
        binding.primary.setOnClickListener { onClick() }
    }

    private fun face(mood: Mood, sizeDp: Float): EyesView = EyesView(this).apply {
        setMood(mood)
        contentDescription = getString(R.string.iris_description)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(context, sizeDp), Ui.dp(context, sizeDp))
        faces += this
    }

    // Steps

    /** One promise, one button. Iris says hello first, so her later faces mean something. */
    private fun welcome() {
        binding.body.gravity = Gravity.CENTER_HORIZONTAL
        binding.body.addView(face(Mood.HAPPY, 230f).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = Ui.dp(context, 40f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        binding.body.addView(wordmarkView(), Ui.gap(this, 10f))
        title(getString(R.string.onb_welcome_title), center = true)
        para(getString(R.string.onb_welcome_text), center = true)
        primary(getString(R.string.get_started)) { show(Step.APPS, 1) }
    }

    private fun wordmarkView() = TextView(this).apply {
        text = MainActivity.wordmark(context)
        gravity = Gravity.CENTER
        textSize = 18f
        typeface = resources.getFont(R.font.bricolage_grotesque)
        fontVariationSettings = "'wght' 800"
        setTextColor(attrColor(R.attr.rrText))
    }

    /** Recognition over recall: logos people know, installed apps ticked already. */
    private fun apps() {
        binding.body.gravity = Gravity.NO_GRAVITY
        title(getString(R.string.onb_apps_title))
        para(getString(R.string.onb_apps_text))
        val chips = Ui.chipFlow(this)
        if (CountStore.prefs(this).all.keys.none { it.startsWith("tracked|") }) {
            // New installs start with the apps on this phone ticked.
            Platform.entries.forEach { CountStore.setTracked(this, it, Ui.isInstalled(this, it)) }
        }
        fun refresh() {
            val any = Platform.entries.any { CountStore.isTracked(this, it) }
            primary(getString(R.string.onb_continue), enabled = any) { show(Step.WEEK, 1) }
        }
        for (p in Platform.entries) {
            val icon = Ui.appIconOrBadge(this, p).let { it.constantState?.newDrawable()?.mutate() ?: it }
            chips.addView(Ui.chip(this, p.shortName, CountStore.isTracked(this, p), icon) { chip ->
                val on = !chip.isSelected
                chip.isSelected = on
                CountStore.setTracked(this, p, on)
                refresh()
            })
        }
        binding.body.addView(chips, Ui.gap(this, 18f))
        refresh()
    }

    /** Value before the big ask: usage access shows their real number, last week. */
    private fun week() {
        binding.body.gravity = Gravity.NO_GRAVITY
        val hasAccess = UsageRepository.hasAccess(this)
        // Already allowed (a replay, or granted earlier): show the number straight away.
        if (hasAccess) revealed = true
        if (!revealed || !hasAccess) {
            title(getString(R.string.onb_week_title))
            para(getString(R.string.onb_week_text))
            binding.body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.card_bg)
                setPadding(0, Ui.dp(context, 26f), 0, Ui.dp(context, 26f))
                addView(Ui.text(context, R.style.Rr_Text_Muted, getString(R.string.onb_number_here)).apply { gravity = Gravity.CENTER })
                addView(Ui.text(context, R.style.Rr_Text_Big, "9h 20m").apply {
                    textSize = 44f
                    setTextColor(attrColor(R.attr.rrText3))
                    // A blurred number: something is waiting for you.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(22f, 22f, android.graphics.Shader.TileMode.DECAL))
                    } else {
                        alpha = 0.15f
                    }
                    contentDescription = getString(R.string.onb_hidden_number)
                }, Ui.gap(context, 6f).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
            }, Ui.gap(this, 18f))
            primary(getString(R.string.usage_access_button)) {
                if (hasAccess) {
                    revealed = true
                    show(Step.WEEK, 0)
                } else {
                    MainActivity.openUsageAccessSettings(this)
                }
            }
            // No Skip: screen time and per-app minutes need usage access.
            return
        }
        title(getString(R.string.onb_week_revealed))
        if (weekMs < 0) {
            loadWeek()
            para(getString(R.string.onb_reveal_loading))
            primary(getString(R.string.onb_continue)) { show(Step.LIMIT, 1) }
            return
        }
        val hours = weekMs / 3_600_000.0
        binding.body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.card_bg)
            setPadding(Ui.dp(context, 18f), Ui.dp(context, 18f), Ui.dp(context, 18f), Ui.dp(context, 18f))
            addView(Ui.text(context, R.style.Rr_Text_Big, Ui.minutes(weekMs)).apply {
                textSize = 48f
                setTextColor(moodColor(3))
            })
            addView(Ui.text(context, R.style.Rr_Text, if (weekApps.isEmpty()) getString(R.string.onb_week_none)
            else getString(R.string.onb_week_on, Ui.joinNames(this@OnboardingActivity, weekApps))).apply { gravity = Gravity.CENTER }, Ui.gap(context, 4f))
            addView(Ui.text(context, R.style.Rr_Text_Muted, when {
                // 23 hours used to be called "more than a full working day".
                hours >= 16 -> getString(R.string.onb_week_days, (hours / 8).toInt())
                hours >= 8 -> getString(R.string.onb_week_workday)
                hours >= 3 -> getString(R.string.onb_week_films)
                else -> getString(R.string.onb_week_light)
            }).apply { gravity = Gravity.CENTER }, Ui.gap(context, 2f))
        }, Ui.gap(this, 16f))
        para(getString(R.string.onb_week_help))
        primary(getString(R.string.onb_continue)) { show(Step.LIMIT, 1) }
    }

    /** Last week's time in the apps picked on the previous step. */
    private fun loadWeek() {
        val app = applicationContext
        io.execute {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val start = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val picked = Platform.entries.filter { CountStore.isTracked(app, it) }
            val usage = UsageRepository.socialUsage(app, start, end)
            val byApp = picked.map { p -> p to usage.filter { it.packageName in p.packages }.sumOf { it.foregroundMs } }
                .filter { it.second >= 60_000 }
            runOnUiThread {
                weekMs = byApp.sumOf { it.second }
                weekApps = byApp.sortedByDescending { it.second }.map { it.first.shortName }
                if (!isDestroyed && step == Step.WEEK) show(Step.WEEK, 0)
            }
        }
    }

    /** A default, not a blank: 30, with "most pick" as a hint, and reels turned into minutes. */
    private fun limitStep() {
        binding.body.gravity = Gravity.NO_GRAVITY
        title(getString(R.string.onb_limit_question))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = getDrawable(R.drawable.card_bg)
            setPadding(Ui.dp(context, 14f), Ui.dp(context, 22f), Ui.dp(context, 14f), Ui.dp(context, 18f))
        }
        val minutes = Ui.text(this, R.style.Rr_Text_Muted, "")
        val chips = ArrayList<TextView>()
        lateinit var number: TextView
        fun set(v: Int) {
            limit = v.coerceIn(5, 500)
            number.text = limit.toString()
            Ui.bump(number)
            minutes.text = getString(R.string.onb_minutes_a_day, Math.round(limit * 2.3f))
            chips.forEach { it.isSelected = it.tag == limit }
            primary(getString(R.string.onb_set_n, limit)) {
                CountStore.setLimit(this, limit)
                show(Step.ACCESS, 1)
            }
            saveProgress()
        }
        val (stepper, num) = Ui.stepper(this, limit, onType = { set(it) }) { set(limit + it) }
        number = num
        card.addView(stepper)
        card.addView(minutes, Ui.gap(this, 12f).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
        binding.body.addView(card, Ui.gap(this, 18f))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (v in listOf(20, 30, 50)) {
            row.addView(Ui.chip(this, if (v == 30) getString(R.string.most_pick, v) else v.toString(), limit == v) { set(v) }.also {
                it.tag = v
                chips += it
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(this@OnboardingActivity, 4f)
                marginEnd = Ui.dp(this@OnboardingActivity, 4f)
            })
        }
        binding.body.addView(row, Ui.gap(this, 14f))
        set(limit)
    }

    /**
     * The prominent disclosure Google Play asks for before sending people to Accessibility
     * settings: what it can and can't see, side by side, and an explicit agreement.
     */
    private fun access() {
        binding.body.gravity = Gravity.NO_GRAVITY
        title(getString(R.string.onb_access_title))
        para(getString(R.string.onb_access_text))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_bg)
            val p = Ui.dp(context, 14f)
            setPadding(p, p, p, p)
        }
        fun line(ok: Boolean, text: Int) = card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply {
                this.text = if (ok) "✓" else "✕"
                textSize = 13f
                fontVariationSettings = "'wght' 800"
                // Reassurances ("never reads…") in a quiet colour: red read as a warning.
                setTextColor(if (ok) moodColor(0) else attrColor(R.attr.rrText3))
            }, LinearLayout.LayoutParams(Ui.dp(context, 22f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.text(context, R.style.Rr_Text_Body, getString(text)).apply { textSize = 13f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, Ui.gap(this, if (card.childCount == 0) 0f else 8f))
        line(true, R.string.onb_access_yes1)
        line(true, R.string.onb_access_yes2)
        line(false, R.string.onb_access_no1)
        line(false, R.string.onb_access_no2)
        binding.body.addView(card, Ui.gap(this, 16f))

        if (MainActivity.isServiceEnabled(this)) {
            primary(getString(R.string.onb_continue)) { show(Step.RUNNING, 1) }
            return
        }
        val box = View(this).apply { background = getDrawable(R.drawable.checkbox_bg) }
        val tick = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = android.content.res.ColorStateList.valueOf(attrColor(R.attr.rrOnAccent))
        }
        val check = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.FrameLayout(context).apply {
                addView(box, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 20f), Ui.dp(context, 20f)))
                addView(tick, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 14f), Ui.dp(context, 14f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(Ui.dp(context, 20f), Ui.dp(context, 20f)))
            addView(Ui.text(context, R.style.Rr_Text_Body, getString(R.string.onb_access_agree)).apply {
                textSize = 13.5f
                setPadding(Ui.dp(context, 10f), 0, 0, 0)
            })
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 8f))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        fun sync() {
            box.isSelected = agreed
            tick.isVisible = agreed
            check.contentDescription = getString(R.string.onb_access_agree) + if (agreed) " ✓" else ""
            primary(getString(R.string.enable_button), enabled = agreed) { MainActivity.openAccessibilitySettings(this) }
        }
        check.setOnClickListener {
            agreed = !agreed
            saveProgress()
            sync()
        }
        binding.body.addView(check, Ui.gap(this, 14f))
        binding.body.addView(Ui.link(this, getString(R.string.a11y_help_link)) { MainActivity.showAccessibilityHelp(this) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sync()
        // No "Do it later": nothing can be counted without the Accessibility service. The step
        // moves on by itself once it's on (see onResume).
        binding.body.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_access_required)).apply {
            setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
        }, Ui.gap(this, 10f))
    }

    /** Notifications keep the counter alive; status shows in place. */
    private fun running() {
        binding.body.gravity = Gravity.NO_GRAVITY
        title(getString(R.string.onb_running_title))
        para(getString(R.string.onb_running_text))
        val list = Ui.listCard(this)
        val notif = MainActivity.notificationsAllowed(this)
        Ui.setRow(list, R.drawable.ic_bell, getString(R.string.notifications), getString(R.string.onb_notif_sub),
            listOf(if (notif) Ui.status(this, getString(R.string.allowed), true) else Ui.link(this, getString(R.string.allow)) { askNotifications() }))
        binding.body.addView(list, Ui.gap(this, 18f))
        if (closesBackgroundApps()) {
            binding.body.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_brand_tip)).apply {
                setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
            }, Ui.gap(this, 12f))
        }
        // The ongoing notification keeps counting alive, so Continue waits for it.
        if (!notif) {
            binding.body.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_running_required)).apply {
                setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
            }, Ui.gap(this, 12f))
        }
        primary(getString(R.string.onb_continue), enabled = notif) { show(Step.IRIS, 1) }
    }

    private fun deviceState() = getSharedPreferences(CountStore.DEVICE_PREFS, MODE_PRIVATE)

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (deviceState().getBoolean(KEY_NOTIF_ASKED, false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Refused for good: Android won't show its prompt again, so open the settings.
            MainActivity.openNotificationSettings(this)
            return
        }
        deviceState().edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF && step == Step.RUNNING) show(Step.RUNNING, 0)
    }

    /**
     * The first permission step whose permission is missing, at or before [s]: progress saved by
     * an older version (where these steps could be skipped) lands back on the missing one.
     */
    private fun firstMissing(s: Step): Step = when {
        s > Step.WEEK && !UsageRepository.hasAccess(this) -> Step.WEEK
        s > Step.ACCESS && !MainActivity.isServiceEnabled(this) -> Step.ACCESS
        s > Step.RUNNING && !MainActivity.notificationsAllowed(this) -> Step.RUNNING
        else -> s
    }

    /** Teach the one idea: Iris gets worried as you scroll. Three faces, no tutorial. */
    private fun meetIris() {
        binding.body.gravity = Gravity.CENTER_HORIZONTAL
        title(getString(R.string.onb_iris_title), center = true)
        para(getString(R.string.onb_iris_text), center = true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((mood, label) in listOf(Mood.HAPPY to R.string.face_calm, Mood.WORRIED to R.string.face_worried, Mood.ANGRY to R.string.face_limit)) {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(face(mood, 104f))
                addView(Ui.text(context, R.style.Rr_Text_Muted, getString(label)), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 4f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        binding.body.addView(row, Ui.gap(this, 22f))
        primary(getString(R.string.go_home)) { finishOnboarding() }
    }

    private fun finishOnboarding() {
        CountStore.setLimit(this, limit)
        CountStore.setOnboarded(this)
        if (MainActivity.isServiceEnabled(this)) KeepAliveService.start(this)
        startActivity(Intent(this, MainActivity::class.java))
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Every Iris on the screen watches the finger. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
            faces.forEach { it.watch(ev.rawX, ev.rawY) }
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        private const val REQUEST_NOTIF = 7
        /** Kept on this phone only (not backed up): a new phone should get the real prompt. */
        private const val KEY_NOTIF_ASKED = "notif_asked"

        /** Phones from brands known for closing background apps. */
        fun closesBackgroundApps(): Boolean {
            val brand = Build.MANUFACTURER.lowercase()
            return listOf("oneplus", "oppo", "realme", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung").any { brand.contains(it) }
        }
    }
}
