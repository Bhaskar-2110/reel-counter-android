package com.reelcounter

import android.content.Intent
import com.reelcounter.widget.WidgetUpdater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * More (design): the things you touch once a year, one level down so Settings stays short:
 * the ongoing notification, permissions, widgets, the welcome tour, help, privacy and
 * about.
 */
class MoreActivity : PageActivity() {

    override fun title(): CharSequence = getString(R.string.more)

    override fun build() {
        val main = list(4f)
        Ui.switchRow(main, R.drawable.ic_bell, getString(R.string.ongoing_title), getString(R.string.ongoing_sub),
            CountStore.ongoingNotification(this)) { on ->
            CountStore.setOngoingNotification(this, on)
            if (on) KeepAliveService.start(this) else KeepAliveService.stop(this)
        }
        val ok = !MainActivity.needsAttention(this)
        Ui.setRow(main, R.drawable.ic_shield, getString(R.string.permissions),
            getString(if (ok) R.string.all_good else R.string.one_needs_fixing),
            listOf(Ui.status(this, getString(if (ok) R.string.ok else R.string.fix), ok), Ui.chevron(this))) {
            push(Intent(this, PermissionsActivity::class.java))
        }
        Ui.switchRow(main, R.drawable.ic_apps, getString(R.string.setting_widgets), getString(R.string.widgets_sub),
            CountStore.widgetsEnabled(this)) { WidgetUpdater.setEnabled(this, it) }

        val tour = list(12f)
        Ui.setRow(tour, R.drawable.ic_eye, getString(R.string.replay_onboarding), null, listOf(Ui.chevron(this))) {
            // Counts and settings are kept; each step starts from them.
            CountStore.setOnboarded(this, false)
            startActivity(Intent(this, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }

        val info = list(12f)
        Ui.setRow(info, R.drawable.ic_help, getString(R.string.help), null, listOf(Ui.chevron(this))) {
            dialog(R.string.help, getString(R.string.help_text))
        }
        Ui.setRow(info, R.drawable.ic_shield, getString(R.string.privacy), getString(R.string.privacy_sub), listOf(Ui.chevron(this))) {
            dialog(R.string.privacy, getString(R.string.privacy_text))
        }
        Ui.switchRow(info, R.drawable.ic_progress, getString(R.string.share_stats), getString(R.string.share_stats_sub),
            Analytics.enabled(this)) { Analytics.setEnabled(this, it) }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        Ui.setRow(info, R.drawable.ic_user, getString(R.string.about), null, listOf(Ui.value(this, version.substringBefore(" ")))) {
            dialog(R.string.about, getString(R.string.about_text, version))
        }
    }

    private fun dialog(title: Int, text: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }
}
