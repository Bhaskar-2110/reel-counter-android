package com.reelcounter

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * App rules (Settings → App rules tile): apps first, rules second. Each app shows its rules as
 * small tags; tapping one opens its page.
 */
class AppRulesActivity : PageActivity() {

    override fun title(): CharSequence = getString(R.string.app_rules)

    override fun build() {
        intro(getString(R.string.app_rules_intro))
        val list = list()
        for (platform in Ui.listedPlatforms(this)) {
            val tags = AppDetailActivity.ruleTags(this, platform)
            val row = Ui.appSetRow(list, platform, if (tags.isEmpty()) getString(R.string.no_rules) else null, listOf(Ui.chevron(this))) {
                push(AppDetailActivity.intent(this, platform))
            }
            Ui.setTags(row, tags)
        }
        val guide = card(12f)
        guide.addView(Ui.text(this, R.style.Rr_Text_CardTitle, getString(R.string.which_rule)))
        guide.addView(Ui.text(this, R.style.Rr_Text_Muted, SpannableStringBuilder().apply {
            fun bold(s: Int) {
                val start = length
                append(getString(s))
                setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(attrColor(R.attr.rrText)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            bold(R.string.rule_block)
            append(" " + getString(R.string.rule_block_when) + " ")
            bold(R.string.reel_limit)
            append(" " + getString(R.string.rule_limit_when) + " ")
            bold(R.string.rule_timer)
            append(" " + getString(R.string.rule_timer_when))
        }), Ui.gap(this, 6f))
    }
}
