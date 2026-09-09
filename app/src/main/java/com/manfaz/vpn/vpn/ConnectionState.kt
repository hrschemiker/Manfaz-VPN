package com.manfaz.vpn.vpn

import com.manfaz.vpn.data.model.ServerConfig

/**
 * [BLOCKED] is the kill-switch state: the tunnel interface is deliberately still installed
 * with no working proxy behind it, so no traffic can escape unprotected. It is a distinct
 * state from [FAILED] because the user has to be offered an explicit way back online.
 */
enum class ConnStatus { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, FAILED, BLOCKED }

data class ConnectionState(
    val status: ConnStatus = ConnStatus.DISCONNECTED,
    val server: ServerConfig? = null,
    val ip: String = "—",
    val pingMs: Int = 0,
    val connectedSinceMs: Long = 0L,      // System uptime marker
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val totalDownloaded: Long = 0L,
    val totalUploaded: Long = 0L,
    val exitCountry: String = "",
    val error: String? = null,
) {
    val isBusy: Boolean get() = status == ConnStatus.CONNECTING || status == ConnStatus.SCANNING

    val isActive: Boolean get() = status == ConnStatus.CONNECTED || isBusy

    val statusFa: String get() = when (status) {
        ConnStatus.DISCONNECTED -> "قطع"
        ConnStatus.SCANNING -> "در حال یافتن IP تمیز…"
        ConnStatus.CONNECTING -> "در حال اتصال…"
        ConnStatus.CONNECTED -> "متصل"
        ConnStatus.FAILED -> "اتصال ناموفق"
        ConnStatus.BLOCKED -> "اینترنت مسدود است"
    }
}
