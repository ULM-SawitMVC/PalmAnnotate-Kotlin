package dev.sawitulm.palmannotate

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught-exception handler.
 *
 * Field data-collection runs offline on devices we can't watch live, so a silent
 * crash means lost work with no trail. This writes a self-contained crash report
 * (timestamp + device/app info + full stack trace) to
 * `<app-external>/PalmAnnotate/crash/` — the same tree the rest of the app data
 * lives in, so it can be pulled with the existing `adb pull` flow — then chains to
 * the platform default handler so the OS still tears the process down normally.
 *
 * Installation is best-effort and never throws; if logging itself fails we still
 * delegate to the previous handler.
 */
class CrashHandler private constructor(
    private val context: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            writeReport(thread, throwable)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write crash report", t)
        } finally {
            // Let the platform (or any previously-installed handler) finish the kill.
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(10)
                }
        }
    }

    private fun writeReport(thread: Thread, throwable: Throwable) {
        val dir = File(File(context.getExternalFilesDir(null), ROOT_DIR), "crash").apply { mkdirs() }
        pruneOldReports(dir)

        val now = Date()
        val stamp = FILE_FORMAT.format(now)
        val report = buildString {
            appendLine("PalmAnnotate crash report")
            appendLine("time      : ${HUMAN_FORMAT.format(now)}")
            appendLine("thread    : ${thread.name}")
            appendLine("app       : ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("--- stack trace ---")
            append(stackTraceToString(throwable))
        }
        File(dir, "crash_$stamp.txt").writeText(report)
        Log.e(TAG, "Uncaught exception on '${thread.name}' — report saved (crash_$stamp.txt)", throwable)
    }

    /** Keep only the most recent [MAX_REPORTS] reports so the folder can't grow unbounded. */
    private fun pruneOldReports(dir: File) {
        val reports = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") } ?: return
        if (reports.size < MAX_REPORTS) return
        reports.sortedByDescending { it.lastModified() }
            .drop(MAX_REPORTS - 1)
            .forEach { runCatching { it.delete() } }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        return sw.toString()
    }

    companion object {
        private const val TAG = "CrashHandler"
        private const val ROOT_DIR = "PalmAnnotate"
        private const val MAX_REPORTS = 20
        private val FILE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val HUMAN_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** Install once, early in [android.app.Application.onCreate]. Idempotent and non-throwing. */
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, current)
            )
        }
    }
}
