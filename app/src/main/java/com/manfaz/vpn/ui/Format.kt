package com.manfaz.vpn.ui

import android.os.SystemClock
import java.util.Locale

/** Wrap a technical string (IP, host:port) in Unicode LTR isolate so RTL doesn't reorder it. */
fun ltr(s: String): String = "⁦$s⁩"

/** Convert Latin digits to Persian digits. */
fun String.toFarsiDigits(): String {
    val fa = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')
    val sb = StringBuilder(length)
    for (c in this) sb.append(if (c in '0'..'9') fa[c - '0'] else c)
    return sb.toString()
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B".toFarsiDigits()
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i]).toFarsiDigits()
}

fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

fun formatDuration(sinceElapsedMs: Long): String {
    if (sinceElapsedMs <= 0L) return "۰۰:۰۰:۰۰"
    val secs = (SystemClock.elapsedRealtime() - sinceElapsedMs) / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s).toFarsiDigits()
}
