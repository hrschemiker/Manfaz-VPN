package com.manfaz.vpn.vpn

import com.manfaz.vpn.data.model.ServerConfig

enum class ConnStatus { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, FAILED }

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
    val statusFa: String get() = when (status) {
        ConnStatus.DISCONNECTED -> "قطع"
        ConnStatus.SCANNING -> "در حال یافتن IP تمیز…"
        ConnStatus.CONNECTING -> "در حال اتصال…"
        ConnStatus.CONNECTED -> "متصل"
        ConnStatus.FAILED -> "اتصال ناموفق"
    }
}
