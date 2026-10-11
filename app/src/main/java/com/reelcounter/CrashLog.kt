package com.reelcounter

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Remembers the last crash so it can be read on the phone without a computer: Settings shows
 * it with a Copy button. A crash in the counting service makes Android switch the service off,
 * so this is often the only clue to why counting stopped.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(app.filesDir, FILE).writeText(
                    "${LocalDateTime.now()} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL} · app ${versionName(app)} · thread ${thread.name}\n\n$trace",
                )
            } catch (_: Exception) {
                // Never let the crash log itself get in the way.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash report, or null if the app hasn't crashed since it was cleared. */
    fun read(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }

    private fun versionName(ctx: Context): String =
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
}
