package com.manfaz.vpn.data.model

import java.util.UUID

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val userAgent: String = "ManfazVPN/1.0",
    val enabled: Boolean = true,
    val serverCount: Int = 0,
    val lastUpdated: Long = 0L,          // epoch millis, 0 = never
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,           // plan traffic limit, 0 = unknown
    val expireEpoch: Long = 0L,          // seconds, 0 = unknown
    val lastError: String? = null,
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
    val remainingDays: Int? get() =
        if (expireEpoch <= 0) null
        else ((expireEpoch - System.currentTimeMillis() / 1000) / 86400).toInt().coerceAtLeast(0)
}
