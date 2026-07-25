package com.manfaz.vpn.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes uncaught JVM exceptions to filesDir/manfaz_crash.log before the process dies,
 * then delegates to the default handler. (Native Go panics are NOT captured here — those
 * only appear in `adb logcat`.)
 */
object CrashLogger {
    fun install(context: Context) {
        val file = File(context.filesDir, "manfaz_crash.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                file.appendText("=== crash on ${thread.name} ===\n$sw\n")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
