package com.manfaz.vpn.util

import android.content.Context
import java.io.File

/**
 * Simple cross-process log buffer backed by a capped file in filesDir. The VPN core runs
 * in the ":core" process, so file-backed logging is the reliable way to surface its events
 * to the UI (Diagnostics screen) in the main process.
 */
object LogBuffer {
    private const val MAX_BYTES = 64 * 1024
    private var file: File? = null

    fun init(context: Context) { file = File(context.applicationContext.filesDir, "manfaz_log.txt") }

    private fun ensure(context: Context): File =
        file ?: File(context.applicationContext.filesDir, "manfaz_log.txt").also { file = it }

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        val f = ensure(context)
        runCatching {
            if (f.exists() && f.length() > MAX_BYTES) {
                // Keep only the second half when it grows too big.
                val tail = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(tail)
            }
            f.appendText("[$tag] $message\n")
        }
    }

    fun read(context: Context): String =
        runCatching { ensure(context).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: "گزارشی ثبت نشده است."

    fun clear(context: Context) { runCatching { ensure(context).writeText("") } }
}
