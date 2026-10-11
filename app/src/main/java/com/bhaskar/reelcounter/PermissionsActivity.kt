package com.bhaskar.reelcounter

import android.Manifest
import android.os.Build
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Counting health (design "Permissions"): a checklist of what Rokoreels needs, green when fine
 * and with a Fix button that jumps straight to the right system screen when not. Also the
 * brand tip for phones that close apps, and diagnostics (last start, last crash).
 */
class PermissionsActivity : PageActivity() {

    override fun title(): CharSequence = getString(R.string.counting_health)

    override fun build() {
        intro(getString(R.string.counting_health_intro))
        val list = list()
        check(list, R.drawable.ic_eye, R.string.accessibility, R.string.accessibility_sub, MainActivity.isServiceEnabled(this)) {
            MainActivity.openAccessibilitySettings(this)
        }
        check(list, R.drawable.ic_progress, R.string.usage_access, R.string.usage_access_sub, UsageRepository.hasAccess(this)) {
            MainActivity.openUsageAccessSettings(this)
        }
        check(list, R.drawable.ic_bell, R.string.notifications, R.string.notifications_sub, MainActivity.notificationsAllowed(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                MainActivity.openNotificationSettings(this)
            }
        }
        if (!MainActivity.isServiceEnabled(this)) {
            content.addView(Ui.link(this, getString(R.string.a11y_help_link)) { MainActivity.showAccessibilityHelp(this) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ui.dp(this@PermissionsActivity, 6f)
                    marginStart = Ui.dp(this@PermissionsActivity, 4f)
                })
        }

        if (OnboardingActivity.closesBackgroundApps()) {
            val c = card(12f)
            c.addView(Ui.text(this, R.style.Rr_Text_CardTitle, getString(R.string.keep_open_title)))
            c.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.lock_in_recents)), Ui.gap(this, 4f))
        }

        // Diagnostics, so a problem can be reported from the phone.
        val format = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        fun at(millis: Long) = if (millis <= 0L) getString(R.string.health_never)
        else Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(format)
        val seen = CountStore.lastSeenAt(this)
        val d = card(12f)
        d.addView(Ui.text(this, R.style.Rr_Text_CardTitle, getString(R.string.diagnostics)))
        d.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(
            R.string.health_line,
            at(CountStore.serviceStartedAt(this)),
            if (seen > 0L) "${CountStore.lastSeenApp(this)}, ${at(seen)}" else getString(R.string.health_never),
        )), Ui.gap(this, 4f))
        CrashLog.read(this)?.let { crash ->
            d.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.crash_title, crash.substringBefore(" ·").take(16).replace('T', ' '))).apply {
                setTextColor(moodColor(3))
            }, Ui.gap(this, 8f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.link(this, getString(R.string.crash_copy)) {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Rokoreels crash", crash))
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            })
            row.addView(Ui.link(this, getString(R.string.crash_clear)) {
                CrashLog.clear(this)
                rebuild()
            }.apply { setPadding(Ui.dp(context, 20f), paddingTop, paddingRight, paddingBottom) })
            d.addView(row)
        }
    }

    private fun check(parent: LinearLayout, icon: Int, title: Int, sub: Int, ok: Boolean, fix: () -> Unit) {
        Ui.setRow(parent, icon, getString(title), getString(sub),
            listOf(Ui.status(this, getString(if (ok) R.string.on else R.string.fix), ok)), if (ok) null else fix)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Android shows no prompt once it's been refused for good; send people to Settings then.
        val denied = grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (denied && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            MainActivity.openNotificationSettings(this)
        }
        rebuild()
    }
}
