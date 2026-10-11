package com.bhaskar.reelcounter

import android.animation.AnimatorInflater
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.roundToInt

/**
 * Small building blocks shared by the Rokoreels screens, matching the design's components:
 * list cards with rows, chips, tags, switches, the ± stepper, quick-pick buttons, bottom
 * sheets, and the staggered "rise" that cards enter with.
 */
object Ui {

    /** The design's ease: cubic-bezier(.2, .8, .2, 1). */
    val EASE = PathInterpolator(.2f, .8f, .2f, 1f)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    // Formatting

    /** "45m", "1h 5m" (the design's fmtMin). */
    fun minutes(ms: Long): String {
        val m = (ms / 60_000).toInt()
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    /** "Instagram", "Instagram and YouTube", "Instagram, YouTube and Snapchat". */
    fun joinNames(ctx: Context, names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(ctx.getString(R.string.list_sep)) + ctx.getString(R.string.and_sep) + names.last()
    }

    /** Ordinal for "6th open today". */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    // App icons: looked up once per process; the package manager call is slow.

    private val icons = LruCache<String, Drawable>(32)

    fun appIcon(ctx: Context, platform: Platform): Drawable? = platform.packages.firstNotNullOfOrNull { pkg ->
        icons[pkg] ?: try {
            ctx.packageManager.getApplicationIcon(pkg).also { icons.put(pkg, it) }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** The app's icon, or a two-letter badge in its brand colour when it isn't installed. */
    fun appIconOrBadge(ctx: Context, platform: Platform): Drawable =
        appIcon(ctx, platform) ?: LetterBadge(
            when (platform) {
                Platform.INSTAGRAM -> "IG"
                Platform.YOUTUBE -> "YT"
                Platform.SNAPCHAT -> "SC"
                Platform.TIKTOK -> "TT"
                Platform.MOJ -> "MJ"
                Platform.JOSH -> "JO"
            },
            when (platform) {
                Platform.INSTAGRAM -> 0xFFDD2A7B.toInt()
                Platform.YOUTUBE -> 0xFFE62117.toInt()
                Platform.SNAPCHAT -> 0xFFFFC400.toInt()
                Platform.TIKTOK -> 0xFF111111.toInt()
                Platform.MOJ -> 0xFFF2A900.toInt()
                Platform.JOSH -> 0xFF6C2BD9.toInt()
            },
            ctx.resources.getFont(R.font.bricolage_grotesque),
        )

    /** The reel apps worth listing: installed, or with reels counted today. */
    fun listedPlatforms(ctx: Context): List<Platform> {
        val today = java.time.LocalDate.now()
        return Platform.entries.filter { isInstalled(ctx, it) || CountStore.appCount(ctx, today, it) > 0 }
    }

    private class LetterBadge(val text: String, val color: Int, typeface: android.graphics.Typeface) : Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LetterBadge.color }
        private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            this.typeface = typeface
            fontVariationSettings = "'wght' 800"
            textAlign = android.graphics.Paint.Align.CENTER
        }
        private val box = android.graphics.RectF()
        override fun draw(canvas: android.graphics.Canvas) {
            box.set(bounds)
            val r = box.width() * 0.28f
            canvas.drawRoundRect(box, r, r, fill)
            ink.textSize = box.height() * 0.38f
            canvas.drawText(text, box.centerX(), box.centerY() - (ink.descent() + ink.ascent()) / 2, ink)
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha; ink.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { fill.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun isInstalled(ctx: Context, platform: Platform) = platform.packages.any { pkg ->
        try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // Motion

    /** Cards rise into place one after another (design: .42s, 40ms apart). */
    fun rise(views: List<View>, distanceDp: Float = 12f) {
        if (views.isEmpty()) return
        val d = dp(views[0].context, distanceDp).toFloat()
        val calm = CountStore.reduceMotion(views[0].context)
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            if (calm) {
                v.alpha = 1f
                v.translationY = 0f
                return@forEachIndexed
            }
            v.alpha = 0f
            v.translationY = d
            v.animate().alpha(1f).translationY(0f).setDuration(420)
                .setStartDelay(minOf(i, 6) * 40L).setInterpolator(EASE).start()
        }
    }

    fun riseChildren(group: ViewGroup) = rise((0 until group.childCount).map { group.getChildAt(it) }.filter { it.isVisible })

    /** Shrinks under the finger and springs back, like the design's buttons and tiles. */
    fun pressable(v: View) {
        v.stateListAnimator = AnimatorInflater.loadStateListAnimator(v.context, R.animator.press_scale)
    }

    // Lists

    /** A list card: rows separated by hairlines. */
    fun listCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ctx.getDrawable(R.drawable.card_bg)
        clipToOutline = true
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = ctx.getDrawable(R.drawable.list_divider)
    }

    /** A settings row (design ".set") added to [parent]. */
    fun setRow(
        parent: ViewGroup,
        icon: Int?,
        title: CharSequence,
        sub: CharSequence? = null,
        end: List<View> = emptyList(),
        onClick: (() -> Unit)? = null,
    ): View {
        val row = LayoutInflater.from(parent.context).inflate(R.layout.row_set, parent, false)
        val box = row.findViewById<View>(R.id.rowIconBox)
        if (icon == null) box.isVisible = false else row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSub).apply {
            text = sub
            isVisible = !sub.isNullOrEmpty()
        }
        val endBox = row.findViewById<LinearLayout>(R.id.rowEnd)
        end.forEach { endBox.addView(it) }
        if (onClick != null) row.setOnClickListener { onClick() } else {
            row.background = null
            row.isClickable = false
        }
        parent.addView(row)
        return row
    }

    /** A settings row with [platform]'s own icon in place of a line icon. */
    fun appSetRow(
        parent: ViewGroup,
        platform: Platform,
        sub: CharSequence? = null,
        end: List<View> = emptyList(),
        onClick: (() -> Unit)? = null,
    ): View {
        val row = setRow(parent, R.drawable.ic_apps, platform.shortName, sub, end, onClick)
        row.findViewById<View>(R.id.rowIconBox).background = null
        row.findViewById<ImageView>(R.id.rowIcon).apply {
            imageTintList = null
            setImageDrawable(appIconOrBadge(parent.context, platform))
            layoutParams = FrameLayout.LayoutParams(dp(context, 30f), dp(context, 30f))
        }
        return row
    }

    /** Puts [tags] under a row's title (design ".tags"); the first one in the accent colour. */
    fun setTags(row: View, tags: List<String>) {
        val box = row.findViewById<LinearLayout>(R.id.rowTags)
        box.removeAllViews()
        box.isVisible = tags.isNotEmpty()
        tags.forEachIndexed { i, t ->
            box.addView(tag(row.context, t, accent = i == 0), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(row.context, 4f) })
        }
    }

    fun tag(ctx: Context, text: String, accent: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = 10.5f
        fontVariationSettings = "'wght' 600"
        setTextColor(ctx.attrColor(if (accent) R.attr.rrAccent else R.attr.rrText2))
        background = ctx.getDrawable(if (accent) R.drawable.tag_accent_bg else R.drawable.tag_bg)
        setPadding(dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f))
    }

    fun chevron(ctx: Context) = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(ctx.attrColor(R.attr.rrText3))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 16f), dp(ctx, 16f)).apply { marginStart = dp(ctx, 6f) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A grey value at the end of a row ("30 reels", "On"). */
    fun value(ctx: Context, text: CharSequence) = TextView(ctx).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(ctx.attrColor(R.attr.rrText2))
        maxLines = 1
    }

    /** "On" in calm green, or "Fix" in amber. */
    fun status(ctx: Context, text: CharSequence, ok: Boolean) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        fontVariationSettings = "'wght' 700"
        setTextColor(ctx.moodColor(if (ok) 0 else 2))
    }

    /** The design's link button ("All apps", "Edit"). */
    fun link(ctx: Context, text: CharSequence, onClick: () -> Unit) = TextView(ctx, null, 0, R.style.Rr_Text_Link).apply {
        this.text = text
        setPadding(dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f))
        setOnClickListener { onClick() }
    }

    fun switch(ctx: Context, checked: Boolean, description: CharSequence, onChange: (Boolean) -> Unit) = MaterialSwitch(ctx).apply {
        isChecked = checked
        contentDescription = description
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /** A row whose whole surface flips its switch. */
    fun switchRow(
        parent: ViewGroup,
        icon: Int?,
        title: CharSequence,
        sub: CharSequence?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): MaterialSwitch {
        val sw = switch(parent.context, checked, title, onChange)
        setRow(parent, icon, title, sub, listOf(sw)) { sw.toggle() }
        return sw
    }

    // Chips and quick picks

    /** The design's ".chip": outlined, accent-tinted when [selected]. */
    fun chip(ctx: Context, text: CharSequence, selected: Boolean, icon: Drawable? = null, onClick: (TextView) -> Unit) =
        TextView(ctx).apply {
            this.text = text
            textSize = 13.5f
            fontVariationSettings = "'wght' 600"
            setTextColor(ctx.attrColor(R.attr.rrText))
            background = ctx.getDrawable(R.drawable.chip_bg)
            isSelected = selected
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(ctx, 40f)
            setPadding(dp(ctx, 12f), dp(ctx, 9f), dp(ctx, 12f), dp(ctx, 9f))
            if (icon != null) {
                val s = dp(ctx, 22f)
                icon.setBounds(0, 0, s, s)
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(ctx, 8f)
            }
            pressable(this)
            setOnClickListener { onClick(this) }
        }

    /** A wrapping row of chips. */
    fun chipFlow(ctx: Context) = com.google.android.material.chip.ChipGroup(ctx).apply {
        chipSpacingHorizontal = dp(ctx, 8f)
        chipSpacingVertical = dp(ctx, 8f)
    }

    /**
     * The sheets' quick-pick buttons (design ".opts"), [columns] to a row; the one equal to
     * [selected] is filled with the accent colour.
     */
    fun <T> opts(ctx: Context, values: List<T>, selected: T?, columns: Int, label: (T) -> String, onPick: (T) -> Unit): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val buttons = ArrayList<TextView>()
        values.chunked(columns).forEachIndexed { r, chunk ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until columns) {
                val v = chunk.getOrNull(c)
                val b = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    textSize = 15f
                    typeface = ctx.resources.getFont(R.font.bricolage_grotesque)
                    fontVariationSettings = "'wght' 700"
                    setTextColor(ctx.getColorStateList(R.color.opt_text))
                    background = ctx.getDrawable(R.drawable.opt_bg)
                    setPadding(0, dp(ctx, 12f), 0, dp(ctx, 12f))
                    if (v == null) visibility = View.INVISIBLE else {
                        text = label(v)
                        isSelected = v == selected
                        pressable(this)
                        setOnClickListener {
                            buttons.forEach { it.isSelected = false }
                            isSelected = true
                            onPick(v)
                        }
                        buttons += this
                    }
                }
                line.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (c > 0) marginStart = dp(ctx, 8f)
                })
            }
            box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = dp(ctx, 8f)
            })
        }
        return box
    }

    /**
     * The design's stepper: round − and + around a big number. With [onType], tapping the number
     * lets you type one (a hint under it says so). Returns the number's view.
     */
    fun stepper(ctx: Context, value: Int, onType: ((Int) -> Unit)? = null, onStep: (Int) -> Unit): Pair<LinearLayout, TextView> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun button(label: String, delta: Int, desc: Int) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 22f
            fontVariationSettings = "'wght' 700"
            setTextColor(ctx.attrColor(R.attr.rrText))
            background = ctx.getDrawable(R.drawable.stepper_round)
            contentDescription = ctx.getString(desc)
            pressable(this)
            setOnClickListener { onStep(delta) }
        }
        val number = TextView(ctx).apply {
            text = value.toString()
            gravity = Gravity.CENTER
            textSize = 58f
            typeface = ctx.resources.getFont(R.font.bricolage_grotesque)
            fontVariationSettings = "'wght' 800"
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            setTextColor(ctx.attrColor(R.attr.rrText))
            minWidth = dp(ctx, 96f)
        }
        val size = dp(ctx, 46f)
        row.addView(button("−", -5, R.string.limit_minus), LinearLayout.LayoutParams(size, size))
        row.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(ctx, 22f)
            marginEnd = dp(ctx, 22f)
        })
        row.addView(button("+", 5, R.string.limit_plus), LinearLayout.LayoutParams(size, size))
        if (onType == null) return row to number
        number.contentDescription = ctx.getString(R.string.limit_type_hint)
        pressable(number)
        number.setOnClickListener {
            askNumber(ctx, ctx.getString(R.string.daily_reel_limit), number.text.toString().toIntOrNull() ?: value, 5, 500, onType)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(row)
        box.addView(text(ctx, R.style.Rr_Text_Muted, ctx.getString(R.string.limit_type_hint)).apply { textSize = 12f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 6f) })
        return box to number
    }

    /** A small dialog to type a whole number from [min] to [max]; anything outside is clamped. */
    fun askNumber(ctx: Context, title: CharSequence, current: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(max.toString().length))
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            hint = ctx.getString(R.string.number_range, min, max)
            if (current > 0) {
                setText(current.toString())
                selectAll()
            }
        }
        val pad = dp(ctx, 22f)
        val holder = FrameLayout(ctx).apply {
            setPadding(pad, dp(ctx, 8f), pad, 0)
            addView(input)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { onSet(it.coerceIn(min, max)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick()
            true
        }
        dialog.show()
        input.requestFocus()
    }

    /** Pops a number that just changed (design: values bounce in place). */
    fun bump(v: View) {
        if (CountStore.reduceMotion(v.context)) return
        v.animate().cancel()
        v.scaleX = 0.88f
        v.scaleY = 0.88f
        v.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
    }

    // Bottom sheets

    /**
     * A bottom sheet (design ".sheet"): a grab handle, [title], the content [build] adds, and a
     * Done button. [onDone] runs however the sheet closes.
     */
    fun sheet(activity: Activity, title: CharSequence, onDone: () -> Unit = {}, build: (LinearLayout) -> Unit): BottomSheetDialog {
        val ctx = activity
        val dialog = BottomSheetDialog(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18f), dp(ctx, 10f), dp(ctx, 18f), dp(ctx, 24f))
        }
        box.addView(View(ctx).apply { background = ctx.getDrawable(R.drawable.sheet_grab) },
            LinearLayout.LayoutParams(dp(ctx, 36f), dp(ctx, 4f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(TextView(ctx, null, 0, R.style.Rr_Text_ScreenTitle).apply {
            text = title
            textSize = 19f
        }, gap(ctx, 14f))
        build(box)
        box.addView(MaterialButton(ctx).apply {
            setText(R.string.done)
            setOnClickListener { dialog.dismiss() }
        }, gap(ctx, 16f))
        dialog.setContentView(box)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { onDone() }
        dialog.show()
        return dialog
    }

    /** Match-width layout params with [topDp] of space above. */
    fun gap(ctx: Context, topDp: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(ctx, topDp) }

    fun text(ctx: Context, style: Int, text: CharSequence? = null) = TextView(ctx, null, 0, style).apply { this.text = text }

    /** A meter (design ".mbar"): a raised track filled to [share] in [color]. */
    fun meter(ctx: Context, share: Float, color: Int, heightDp: Float = 6f): FrameLayout = FrameLayout(ctx).apply {
        background = ctx.getDrawable(R.drawable.mbar_track)
        clipToOutline = true
        val fill = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 3f).toFloat()
                setColor(color)
            }
            pivotX = 0f
            scaleX = 0f
        }
        addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp))
        fill.animate().scaleX(share.coerceIn(0f, 1f)).setDuration(600).setInterpolator(EASE).start()
    }
}
